# MMS oldest watermark repair

The real phone's Full Test reported 86 MMS rows in both the provider and Room, with the initial mirror and history scan marked complete, but `oldestDate` and `oldestId` still at `Long.MAX_VALUE`. A legacy completed cursor made `backfillOlderKeyset` return before it could repair the oldest provider watermark.

Android 3.4.15 repeats the provider keyset scan only for a completed MMS cursor whose oldest value remains untouched. It keeps the regular completed SMS cursor closed. Existing Room rows classify as unchanged; any actual provider changes still flow through the normal durable ingest. The oldest cursor is advanced from the provider page and the scan is marked complete again. With 86 MMS rows and a 500-row page, this device should need one page. The real-device result is NOT VERIFIED until Full Test runs after installation.
