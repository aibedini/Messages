package com.autonomousone.messages.repository

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlSerializer
import java.io.OutputStream

/**
 * Full SMS backup / restore.
 *
 * Format: Android-compatible XML (same shape as the classic "SMS Backup &
 * Restore" app), so backups stay portable between apps and are human-readable:
 *
 * <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
 * <smses count="2">
 *   <sms date="1755..." body="hi" type="1" address="+98..." ... />
 *   ...
 * </smses>
 */
class BackupRepository(private val context: Context) {

    companion object {
        private val PROJECTION = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.DATE_SENT,
            Telephony.Sms.READ,
            Telephony.Sms.TYPE,
            Telephony.Sms.STATUS,
            Telephony.Sms.THREAD_ID
        )
    }

    /** Streams every stored SMS into [out] as Android-style XML. Returns row count. */
    fun backupTo(out: OutputStream): Int {
        val serializer: XmlSerializer = Xml.newSerializer()
        var count = 0
        try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI, PROJECTION, null, null,
                "${Telephony.Sms.DATE} ASC"
            )?.use { cursor ->
                serializer.setOutput(out, "UTF-8")
                serializer.startDocument("UTF-8", true)
                serializer.startTag(null, "smses")
                serializer.attribute(null, "count", cursor.count.toString())

                while (cursor.moveToNext()) {
                    serializer.startTag(null, "sms")
                    for (col in PROJECTION.drop(1)) { // skip _ID (restored rows get new ids)
                        val idx = cursor.getColumnIndexOrThrow(col)
                        if (!cursor.isNull(idx)) {
                            serializer.attribute(null, col, cursor.getString(idx))
                        }
                    }
                    serializer.endTag(null, "sms")
                    count++
                }

                serializer.endTag(null, "smses")
                serializer.endDocument()
                serializer.flush()
            }
        } catch (e: Exception) {
            Log.e("BackupRepository", "backupTo failed after $count rows", e)
            throw e
        }
        return count
    }

    /** Reads XML from [input] and inserts rows into Telephony.Sms. Returns inserted count. */
    fun restoreFrom(input: java.io.InputStream): Int {
        val parser: XmlPullParser = Xml.newPullParser()
        var inserted = 0
        try {
            parser.setInput(input, null)
            var event = parser.eventType
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (event == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "sms") {
                    val values = ContentValues()
                    for (i in 0 until parser.attributeCount) {
                        val name = parser.getAttributeName(i)
                        val value = parser.getAttributeValue(i) ?: continue
                        when (name) {
                            Telephony.Sms.ADDRESS -> values.put(Telephony.Sms.ADDRESS, value)
                            Telephony.Sms.BODY -> values.put(Telephony.Sms.BODY, value)
                            Telephony.Sms.DATE -> values.put(Telephony.Sms.DATE, value.toLongOrNull() ?: 0L)
                            Telephony.Sms.DATE_SENT -> values.put(Telephony.Sms.DATE_SENT, value.toLongOrNull() ?: 0L)
                            Telephony.Sms.READ -> values.put(Telephony.Sms.READ, value.toIntOrNull() ?: 1)
                            Telephony.Sms.TYPE -> values.put(Telephony.Sms.TYPE, value.toIntOrNull())
                            Telephony.Sms.STATUS -> values.put(Telephony.Sms.STATUS, value.toIntOrNull() ?: -1)
                            // THREAD_ID is device-specific — let the provider recompute it.
                        }
                    }
                    if (values.containsKey(Telephony.Sms.ADDRESS)) {
                        try {
                            context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
                                ?.let { inserted++ }
                        } catch (e: Exception) {
                            Log.w("BackupRepository", "Row insert failed", e)
                        }
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            Log.e("BackupRepository", "restoreFrom failed after $inserted rows", e)
            throw e
        }
        return inserted
    }
}
