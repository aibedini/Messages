package com.autonomousone.messages.repository

import android.content.Context
import android.provider.ContactsContract
import com.autonomousone.messages.model.Contact

class ContactRepository(
    private val context: Context
) {

    fun getContacts(): List<Contact> {

        val contacts = mutableListOf<Contact>()

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
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )?.use { cursor ->

            val idIndex = cursor.getColumnIndexOrThrow(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID
            )

            val nameIndex = cursor.getColumnIndexOrThrow(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            )

            val phoneIndex = cursor.getColumnIndexOrThrow(
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )

            val addedNumbers = HashSet<String>()

            while (cursor.moveToNext()) {

                val id = cursor.getLong(idIndex)

                val name = cursor.getString(nameIndex)
                    ?.trim()
                    ?.ifBlank { "Unknown" }
                    ?: "Unknown"

                var phone = cursor.getString(phoneIndex)
                    ?.trim()
                    ?: ""

                if (phone.isBlank()) continue

                // Normalize phone number
                phone = phone
                    .replace(" ", "")
                    .replace("-", "")
                    .replace("(", "")
                    .replace(")", "")

                // Keep only the first '+' if present
                if (phone.startsWith("+")) {
                    phone = "+" + phone.substring(1).replace("+", "")
                } else {
                    phone = phone.replace("+", "")
                }

                if (!addedNumbers.add(phone)) {
                    continue
                }

                contacts.add(
                    Contact(
                        id = id,
                        name = name,
                        phone = phone
                    )
                )
            }
        }

        return contacts.sortedBy {
            it.name.lowercase()
        }
    }
}