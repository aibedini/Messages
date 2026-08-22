package com.autonomousone.messages.repository

import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import com.autonomousone.messages.model.Contact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ContactRepository(
    private val context: Context
) {

    companion object {
        @Volatile
        private var cachedMap: Map<String, String>? = null

        fun clearCache() {
            cachedMap = null
        }

        fun normalizePhone(phone: String): String {
            var p = phone
                .replace(" ", "")
                .replace("-", "")
                .replace("(", "")
                .replace(")", "")

            if (p.startsWith("+")) {
                p = "+" + p.substring(1).replace("+", "")
            } else {
                p = p.replace("+", "")
            }
            return p
        }
    }

    suspend fun getContactNameMapAsync(): Map<String, String> = withContext(Dispatchers.IO) {
        val existing = cachedMap
        if (existing != null) return@withContext existing

        val map = getContactNameMap()
        cachedMap = map
        map
    }

    fun getContactNameMap(): Map<String, String> {
        val existing = cachedMap
        if (existing != null) return existing

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
                        map[normalizePhone(phone)] = name
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CONTACT_DEBUG", "Error querying contact name map", e)
        }
        cachedMap = map
        return map
    }

    fun getCachedDisplayName(phone: String): String {
        if (phone.isBlank()) return "Unknown"
        val map = cachedMap ?: return phone
        val norm = normalizePhone(phone)
        return map[norm] ?: map[phone] ?: phone
    }

    fun getContacts(progress: ProgressListener? = null): List<Contact> {
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
                }
                if (progress != null && total == 0) {
                    progress.onProgress(LoadProgress("contacts", 0, 0))
                }
            }
        } catch (e: Exception) {
            Log.e("CONTACT_DEBUG", "Error fetching ContactsContract contacts", e)
        }

        try {
            val smsRepo = SmsRepository(context)
            val conversations = smsRepo.getConversations()
            var fallbackId = -100L
            for (sms in conversations) {
                val rawSender = sms.sender.trim()
                if (rawSender.isBlank()) continue
                val normalized = normalizePhone(rawSender)
                if (addedNumbers.add(normalized)) {
                    contacts.add(
                        Contact(
                            id = fallbackId--,
                            name = rawSender,
                            phone = normalized
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("CONTACT_DEBUG", "Error merging SMS senders into contacts", e)
        }

        return contacts.sortedBy { it.name.lowercase() }
    }
}