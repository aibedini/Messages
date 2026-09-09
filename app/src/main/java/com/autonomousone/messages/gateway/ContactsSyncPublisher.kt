package com.autonomousone.messages.gateway

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import com.autonomousone.messages.data.GatewayEventFactory
import com.autonomousone.messages.data.MessagesDatabase
import com.autonomousone.messages.repository.ContactRepository
import com.autonomousone.messages.security.ConversationKeyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Publishes the phone contact book as opaque CONTACTS events. */
class ContactsSyncPublisher(
    private val context: Context,
    private val prefs: GatewayPreferences,
    private val scope: CoroutineScope,
) {
    companion object {
        const val CONTACTS_AGGREGATE = "contacts"
        const val CONTACTS_CAPABILITY = "CONTACTS_READ"
        const val SNAPSHOT = "CONTACTS_SNAPSHOT"
        const val CHANGED = "CONTACTS_CHANGED"
        const val MAX_CONTACTS_PER_EVENT = 100

        internal fun <T> chunks(items: List<T>): List<List<T>> =
            if (items.isEmpty()) listOf(emptyList()) else items.chunked(MAX_CONTACTS_PER_EVENT)
    }

    private data class ContactRecord(
        val normalizedPhone: String,
        val displayName: String,
        val starred: Boolean,
        val photoThumbBase64: String?,
        val lastUpdateMs: Long,
    ) {
        fun json(): JSONObject = JSONObject()
            .put("normalizedPhone", normalizedPhone)
            .put("displayName", displayName)
            .put("starred", starred)
            .put("photoThumbBase64", photoThumbBase64 ?: JSONObject.NULL)
            .put("lastUpdateMs", lastUpdateMs)
    }

    private val db = MessagesDatabase.get(context.applicationContext)
    private var job: Job? = null
    private var debounce: Job? = null
    private var previous = emptyMap<String, ContactRecord>()
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            ContactRepository.clearCache()
            debounce?.cancel()
            debounce = scope.launch {
                delay(5_000)
                publishChanges()
            }
        }
    }

    fun start() {
        if (job?.isActive == true) return
        context.contentResolver.registerContentObserver(
            ContactsContract.Contacts.CONTENT_URI, true, observer
        )
        job = scope.launch {
            while (isActive && (!prefs.isEnabled || !prefs.identityRegistered || !hasPermission())) delay(5_000)
            if (isActive) publishSnapshot()
        }
    }

    fun stop() {
        runCatching { context.contentResolver.unregisterContentObserver(observer) }
        debounce?.cancel()
        job?.cancel()
        job = null
    }

    private suspend fun publishSnapshot() {
        val contacts = readContacts().associateBy { it.normalizedPhone }
        previous = contacts
        publish(SNAPSHOT, contacts.values.toList(), emptyList())
    }

    private suspend fun publishChanges() {
        if (!prefs.isEnabled || !prefs.identityRegistered || !hasPermission()) return
        val current = readContacts().associateBy { it.normalizedPhone }
        val upserts = current.values.filter { previous[it.normalizedPhone] != it }
        val deleted = previous.keys.filterNot(current::containsKey)
        previous = current
        if (upserts.isNotEmpty() || deleted.isNotEmpty()) publish(CHANGED, upserts, deleted)
    }

    private suspend fun publish(type: String, contacts: List<ContactRecord>, deleted: List<String>) {
        val snapshotId = UUID.randomUUID().toString()
        val pages = chunks(contacts)
        pages.forEachIndexed { index, page ->
            val payload = JSONObject()
                .put("snapshotId", snapshotId)
                .put("chunkIndex", index)
                .put("chunkCount", pages.size)
                .put("replaceAll", type == SNAPSHOT)
                .put("contacts", JSONArray().apply { page.forEach { put(it.json()) } })
                .put("deleted", JSONArray().apply { if (index == 0) deleted.forEach(::put) })
            val eventId = UUID.nameUUIDFromBytes("contacts:$type:$snapshotId:$index".toByteArray()).toString()
            db.withTransaction {
                val plain = GatewayEventFactory.outboxRow(
                    eventUuid = eventId,
                    eventType = type,
                    conversationId = CONTACTS_AGGREGATE,
                    payloadJson = payload.toString(),
                )
                val encrypted = ConversationKeyRepository(db).encrypt(
                    plain, CONTACTS_CAPABILITY
                )
                db.gatewayEventOutboxDao().insertOrIgnore(encrypted)
            }
        }
        Log.i("CONTACTS_SYNC", "SYNC_REPORT type=$type contacts=${contacts.size} deleted=${deleted.size} chunks=${pages.size}")
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun readContacts(): List<ContactRecord> {
        val rows = LinkedHashMap<String, ContactRecord>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.STARRED,
            ContactsContract.CommonDataKinds.Phone.CONTACT_LAST_UPDATED_TIMESTAMP,
            ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
        )
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, null
            )?.use { cursor ->
                val name = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val number = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val starred = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.STARRED)
                val updated = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_LAST_UPDATED_TIMESTAMP)
                val photo = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI)
                while (cursor.moveToNext()) {
                    val normalized = ContactRepository.normalizePhone(cursor.getString(number) ?: "")
                    if (normalized.isBlank()) continue
                    val photoUri = cursor.getString(photo)
                    rows[normalized] = ContactRecord(
                        normalizedPhone = normalized,
                        displayName = cursor.getString(name)?.trim().orEmpty().ifBlank { normalized },
                        starred = cursor.getInt(starred) != 0,
                        photoThumbBase64 = readThumbnail(photoUri),
                        lastUpdateMs = cursor.getLong(updated),
                    )
                }
            }
        }.onFailure { Log.w("CONTACTS_SYNC", "contact query failed", it) }
        return rows.values.toList()
    }

    /** 3 KiB raw remains below the 4 KiB encoded contract. */
    private fun readThumbnail(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            context.contentResolver.openInputStream(Uri.parse(value))?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (output.size() <= 3 * 1024) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
                val bytes = output.toByteArray()
                if (bytes.size > 3 * 1024) null else Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
        }.getOrNull()
    }
}
