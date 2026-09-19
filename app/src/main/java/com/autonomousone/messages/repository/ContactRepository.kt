package com.autonomousone.messages.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import com.autonomousone.messages.model.Contact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class ContactRepository(
    private val context: Context
) {

    companion object {
        @Volatile
        private var cachedMap: Map<String, String>? = null
        private val participantCache = ConcurrentHashMap<String, ConversationParticipantState>()

        fun clearCache() {
            cachedMap = null
            participantCache.clear()
        }

        /**
         * Formatting-only normalization. Delegates to [PhoneIdentity] so there is
         * exactly ONE definition of "the same number written differently" in the app.
         * The previous hand-rolled version stripped spaces/dashes/parentheses but
         * carried no equivalence rules at all, which is half of the Home-name bug.
         */
        fun normalizePhone(phone: String): String = PhoneIdentity.normalize(phone)

        /**
         * True when [a] and [b] plausibly belong to the same conversation:
         * equal after normalization, or one is a suffix of the other (same
         * number seen with/without country code). Suffix matching requires a
         * minimum length so short fragments ("12", "911") can never falsely
         * join two unrelated conversations.
         */
        fun sameConversation(a: String, b: String): Boolean {
            val na = normalizePhone(a)
            val nb = normalizePhone(b)
            if (na.isBlank() || nb.isBlank()) return false
            if (na == nb) return true
            // Suffix match only when the shorter side carries real signal
            // (≥ 7 digits ≈ subscriber number without country code).
            val minLen = minOf(na.length, nb.length)
            if (minLen < 7) return false
            return na.endsWith(nb) || nb.endsWith(na)
        }

        /**
         * THE display-name resolver. Every surface that shows a contact name for an
         * address goes through this: the Home row, Home search hits, the navigation
         * snapshot, confirmation dialogs, archive/delete labels.
         *
         * Static on purpose — it is a pure function of (directory, address), so callers
         * need no repository instance and no ContentResolver. It tries every spelling
         * of the address (see [PhoneIdentity.lookupKeys]) so the local `0912…` form
         * finds a contact stored as `+98912…`, and it falls back to the address itself
         * — never to a blank string — so an unknown sender still renders.
         */
        fun displayNameFrom(map: Map<String, String>, address: String): String {
            if (address.isBlank()) return address
            PhoneIdentity.lookupKeys(address).forEach { key ->
                map[key]?.let { return it }
            }
            return address
        }
    }

    /** One indexed PhoneLookup query; never scans all contacts during composition. */
    suspend fun getParticipantState(phone: String): ConversationParticipantState =
        withContext(Dispatchers.IO) {
            val normalized = normalizePhone(phone)
            if (normalized.isBlank() || normalized.contains(',') || normalized.contains(';')) {
                return@withContext ConversationParticipantState(
                    phone = phone,
                    normalizedPhone = normalized,
                    displayName = phone,
                    isKnownContact = false
                )
            }
            participantCache[normalized]?.let { return@withContext it }

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return@withContext unknownParticipant(phone, normalized)
            }

            val lookup = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(normalized)
            )
            val state = runCatching {
                context.contentResolver.query(
                    lookup,
                    arrayOf(
                        ContactsContract.PhoneLookup._ID,
                        ContactsContract.PhoneLookup.LOOKUP_KEY,
                        ContactsContract.PhoneLookup.DISPLAY_NAME
                    ),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val contactId = cursor.getLong(0)
                    val lookupKey = cursor.getString(1).orEmpty()
                    val displayName = cursor.getString(2)?.trim().orEmpty()
                    ConversationParticipantState(
                        phone = phone,
                        normalizedPhone = normalized,
                        displayName = displayName.ifBlank { phone },
                        isKnownContact = true,
                        contactLookupUri = ContactsContract.Contacts
                            .getLookupUri(contactId, lookupKey)
                            ?.toString()
                    )
                }
            }.onFailure {
                Log.w("CONTACT_DEBUG", "Participant lookup failed", it)
            }.getOrNull() ?: unknownParticipant(phone, normalized)

            participantCache[normalized] = state
            state
        }

    private fun unknownParticipant(phone: String, normalized: String) =
        ConversationParticipantState(
            phone = phone,
            normalizedPhone = normalized,
            displayName = phone,
            isKnownContact = false
        )

    suspend fun getContactNameMapAsync(): Map<String, String> = withContext(Dispatchers.IO) {
        val existing = cachedMap
        if (existing != null) return@withContext existing

        val map = getContactNameMap()
        if (map.isNotEmpty()) cachedMap = map
        map
    }

    fun getContactNameMap(): Map<String, String> {
        val existing = cachedMap
        if (existing != null) return existing

        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) return emptyMap()

        val map = mutableMapOf<String, String>()
        try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val phoneIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex)?.trim() else null
                    val phone = if (phoneIndex >= 0) cursor.getString(phoneIndex)?.trim() else null
                    if (!name.isNullOrEmpty() && !phone.isNullOrEmpty()) {
                        // Register EVERY spelling of the contact's number, so a
                        // conversation whose provider address is the local `0912…`
                        // form still finds a contact stored as `+98912…`.
                        // `putIfAbsent` keeps the FIRST contact for a shared line
                        // instead of letting cursor order decide the winner.
                        PhoneIdentity.lookupKeys(phone).forEach { key ->
                            map.putIfAbsent(key, name)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CONTACT_DEBUG", "Error querying contact name map", e)
        }
        if (map.isNotEmpty()) cachedMap = map
        return map
    }

    fun getCachedDisplayName(phone: String): String {
        if (phone.isBlank()) return "Unknown"
        val map = cachedMap ?: return phone
        return displayNameFrom(map, phone)
    }

    fun getContacts(
        progress: ProgressListener? = null,
        onPartial: ((List<Contact>) -> Unit)? = null
    ): List<Contact> {
        val contacts = mutableListOf<Contact>()
        val addedNumbers = HashSet<String>()

        try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )

            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val phoneIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                val total = cursor.count
                var readCount = 0
                var lastEmitted = -1
                while (cursor.moveToNext()) {
                    readCount++
                    if (progress != null && (readCount == total || readCount - lastEmitted >= 50)) {
                        lastEmitted = readCount
                        progress.onProgress(LoadProgress("contacts", readCount, total))
                    }

                    val id = if (idIndex >= 0) cursor.getLong(idIndex) else 0L
                    val rawName = if (nameIndex >= 0) cursor.getString(nameIndex)?.trim() else null
                    var phone = if (phoneIndex >= 0) cursor.getString(phoneIndex)?.trim() ?: "" else ""

                    if (phone.isBlank()) continue
                    phone = normalizePhone(phone)

                    val displayName = if (!rawName.isNullOrEmpty()) rawName else phone

                    if (addedNumbers.add(phone)) {
                        contacts.add(
                            Contact(
                                id = id,
                                name = displayName,
                                phone = phone
                            )
                        )
                    }
                    if (readCount == 50 || readCount == total || readCount % 500 == 0) {
                        onPartial?.invoke(contacts.toList())
                    }
                }
                if (progress != null && total == 0) {
                    progress.onProgress(LoadProgress("contacts", 0, 0))
                }
            }
        } catch (e: Exception) {
            Log.e("CONTACT_DEBUG", "Error fetching ContactsContract contacts", e)
        }

        val sorted = contacts.sortedBy { it.name.lowercase() }
        onPartial?.invoke(sorted)
        return sorted
    }
}
