package com.autonomousone.messages.repository

import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import com.autonomousone.messages.model.Contact

class ContactRepository(
    private val context: Context
) {

    fun getContacts(): List<Contact> {
        val contacts = mutableListOf<Contact>()
        val addedNumbers = HashSet<String>()

        // 1. Fetch system address book contacts from ContactsContract
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

                while (cursor.moveToNext()) {
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
            }
        } catch (e: Exception) {
            Log.e("CONTACT_DEBUG", "Error fetching ContactsContract contacts", e)
        }

        // 2. Merge recent SMS senders from SmsRepository so SMS contacts always appear
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

    /**
     * Map of normalized phone numbers to contact names.
     */
    fun getContactNameMap(): Map<String, String> {
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
        return map
    }

    companion object {
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
}