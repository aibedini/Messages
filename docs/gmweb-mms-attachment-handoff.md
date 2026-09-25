# MMS attachments: Android status and the GMweb contract handoff

**Status:** specification for work that spans two repositories. Nothing in this document is
implemented on the server side, and no Android-side change here pretends MMS attachments are
replicated.

**The one-line summary:** this app already knows *which* attachments an MMS has, *what kind* they
are and *how to identify them deterministically* — but it has **never uploaded one**, and there is no
protocol for doing so. MMS messages therefore replicate as text-only messages, and GMweb cannot tell
a genuinely text-only MMS from one whose photos were left behind.

**Explicit non-goals (and reasons)**

- **No endpoint was invented.** There is no `POST /api/v1/agent/attachments` in this repository and
  none is assumed. The contract below is a *proposal for agreement*, not a client for a guessed API.
- **No attachment bytes travel inside ordinary event batches.** A history batch is bounded at 100
  events / 512 KB; embedding media there would starve realtime replication and blow the batch bound.
  Attachments need their own channel, agreed below.
- **MMS is never reported as attachment-complete.** Today nothing claims completeness (see
  "Completeness" below), and the diagnostic gap is reported rather than hidden.

---

## 1. What Android already emits today (verified, with sources)

### 1.1 The MMS message

An MMS is materialised into `MessageEntity` by `SmsRepository.readMmsMaterializedStrict`
(`SmsRepository.kt:453`) and replicated through the **same** path as SMS: one
`MESSAGE_CREATED` event whose payload is the canonical envelope
(`GatewayEventFactory.messageCreated`, `GatewayEventFactory.kt:130`) carrying
`messageId`, `direction`, `body`, `dateMs`, `status`, `address`, `read` and optionally
`contactName`. There is **no attachment field in that payload**, and adding one would change the
event schema for every event type — which is why the contract below proposes a **separate event**.

Text parts are folded into the message `body`, and are explicitly **not** treated as attachments
(`MmsAssetMapper.NON_ATTACHMENTS`, `MmsAssetMapper.kt:47`) — a `text/plain` part *is* the message.

### 1.2 Attachment metadata (exists, local only)

Read from the provider by `MessageAssetIndexer.readMmsPartMetadata` (`MessageAssetIndexer.kt:211`,
`_size` column at `:264`) into:

```kotlin
MmsPartMetadata(partId, messageId, contentType, name, fileName, size)   // MmsAssetMapper.kt:14
```

Mapped by `MmsAssetMapper.toAssets` (`MmsAssetMapper.kt:95`) into `message_assets`
(`UxEntities.kt:283`):

| column | today's value |
|---|---|
| `assetKey` | SHA-256 of `source\|providerId\|kind\|value` — **deterministic** (`MessageAssetKeys.of`, `UxEntities.kt:311`) |
| `source`, `providerId`, `threadId`, `date` | the owning message |
| `kind` | `MEDIA` (image/video/audio) or `FILE` (`MmsAssetMapper.kindFor`) |
| `value` | `content://mms/part/<partId>` — a **device-local** provider URI |
| `mimeType` | base content type, e.g. `image/jpeg` |
| `displayName` | `fn` then `name` |

### 1.3 Binary attachments: deliberately never read

`MmsAssetMapper` states it: *"Deliberately carries NO bytes: a 360K-message database must not become
a media archive, and Android already owns the attachment data behind the part URI."*

So there is **no local blob, no local content hash, and no local size** for an attachment. Only the
provider URI, the MIME type, the display name and (transiently, not persisted) `_size` exist.

### 1.4 Replication: nothing

`message_assets` is referenced by the media/UI stack only (`MessageAssetIndexer`,
`MessageAssetRepository`, `ConversationMediaViewModel`, `MessageAssetBackfillWorker`). The only
gateway-path reference is `TelephonySyncCoordinator.kt:735`, which **deletes** assets when a message
is removed. **No asset is ever uploaded, queued, counted or reported.**

---

## 2. The gap, stated precisely

1. **An MMS replicates as text.** A photo-only MMS reaches GMweb as a `MESSAGE_CREATED` whose body
   may be empty, with nothing indicating an attachment exists.
2. **The web cannot distinguish the two cases.** "MMS with no attachment" and "MMS whose attachments
   were not replicated" look identical.
3. **There is no upload channel, no retry, no resume, no size policy and no deletion semantics** for
   attachment bytes on either side.
4. **`value` is not portable.** `content://mms/part/<n>` is meaningful only on the device that has
   the provider row; it cannot be resolved by GMweb and must never be treated as a server-side key.

**Completeness today:** nothing in the app asserts that an MMS is attachment-complete, so there is
no false claim to correct — but there is also no signal, which is the gap this contract closes. The
rule the implementation must obey, and which the contract below is shaped by:

> An MMS may be marked attachment-complete **only** when every attachment the device knows about has
> been accepted by the server. Until then it is *incomplete*, and that must be visible rather than
> implied.

---

## 3. Minimum cross-repository contract (proposal — requires GMweb agreement)

The eleven points the mission requires, each with the Android-side answer that already exists and
the server-side answer that must be agreed.

### 3.1 Attachment identity

**Proposal:** the server keys an attachment by the **client-generated `assetKey`**, an opaque
64-character lowercase hex SHA-256:

```text
assetKey = sha256("${source}|${providerId}|${kind}|${value}")
```

- Deterministic and stable: re-indexing the same part yields the same key, so ingesting twice is an
  UPSERT, never a duplicate.
- Collision-resistant across messages: two identical URIs in two different messages differ, because
  `(source, providerId)` is in the hash.
- Opaque to the server: it must **not** parse it, and must treat it as the idempotency key.
- **Not** `content://mms/part/<n>`, which is device-local (gap 4 above).

### 3.2 MIME type

`mimeType` is the base content type with parameters stripped and lowercased (e.g. `image/jpeg`).
The server should store it verbatim and must not infer type from the display name.

### 3.3 Size

**Android prerequisite, not yet done:** `MmsPartMetadata.size` is read from the provider's `_size`
column but is **dropped** by `MmsAssetMapper.toAssets` — `message_assets` has no size column. The
device will therefore need a schema addition (a `sizeBytes` column, `0` = unknown) and to send
`sizeBytes` with each attachment, with `0` meaning *unknown* rather than *empty*. A server must
tolerate `0`.

### 3.4 Crypto behaviour

Attachment bytes are **user content** and fall under the same rules as message bodies:

- Encrypted **on the device, before transmission**; the server stores ciphertext only.
- Must use the **existing per-conversation / history key material** rather than introducing a second
  key hierarchy; the `keyRef` discipline already used for events applies unchanged.
- **No plaintext fallback, ever.** If the key is unavailable the attachment is *not* sent and is
  reported as blocked — a plaintext upload is not an acceptable degradation.
- Chunked AEAD (each chunk independently authenticated with its index bound in) is proposed, because
  an attachment can be seconds long to upload and a partial upload must not yield a partially
  trusted file.

### 3.5 Upload mechanism

**Proposal:** a **separate** binary channel, explicitly **not** the event batch:

- The device streams ciphertext chunks to a per-attachment resource, resumable by chunk index.
- The event stream carries only *metadata* (an `MMS_ATTACHMENT_ADDED` event referencing `assetKey`),
  so the realtime/history event path keeps its current size and latency bounds.
- Ordering: the metadata event may arrive **before** the bytes finish; the server must accept
  metadata for an attachment whose bytes are still in flight and mark it pending, not broken.

### 3.6 Event reference

**Proposal:** a new event type `MMS_ATTACHMENT_ADDED`, carrying:

```text
assetKey          (identity, 3.1)
messageId         (the owning message's canonical messageId, so the web can attach it)
source, providerId, threadId, date
kind, mimeType, displayName, sizeBytes
contentHash       (optional; needed for dedupe and integrity — see 3.7)
```

It is a **new type**, not a new field on `MESSAGE_CREATED`, so existing event schemas and the
`SyncErrorCode`/retry rules for message events are untouched.

### 3.7 Retry / resume semantics

- **Resumable by chunk**, keyed by `assetKey` + chunk index; a resumed upload must not restart at
  zero.
- **Idempotent by `assetKey`**: re-uploading an attachment the server already holds must be
  answerable with `DUPLICATE` and treated by the device as success — the same rule the event path
  already uses (`ACCEPTED` and `DUPLICATE` both acknowledge).
- Failures are **classified, not retried blindly**: a 401/403 pauses the whole channel (never the
  attachment only), 413 means the size policy refused it (see 3.8), 5xx/429 retry with backoff.
- A permanently refused attachment becomes a **visible dead letter for that asset**, and the owning
  message stays *incomplete* rather than being quietly declared done.

### 3.8 Maximum size policy

- **Local first:** the app already refuses to *send* a single part over **900,000 bytes**
  (`MmsPayloadPolicy.MAX_PART_BYTES`), and reads at most one byte past the cap to decide
  (`READ_LIMIT_BYTES`). The same constant should govern upload eligibility so the device never
  uploads bytes for an attachment it would refuse to carry.
- Large media should be **chunked with a resume point**, not rejected outright, once a server-side
  chunk limit is agreed.
- **To be agreed:** the server's maximum accepted attachment size and chunk size. Until they are
  agreed the device must treat "unknown limit" as *do not start*, not as *try it and see*.

### 3.9 Server storage behaviour

**Must be agreed. The device assumes:**

- Server stores ciphertext + `assetKey` + `mimeType` + `sizeBytes` + `contentHash`, scoped to the
  owning message and conversation.
- **Retention follows the message**: deleting the message deletes its attachments (see 3.11).
- The server must be able to answer "does `assetKey` already exist for this account?" so a re-index
  or a reinstall does not re-upload.
- No server-side transcoding, thumbnailing or re-compression: it cannot — it holds ciphertext — and
  any derived plaintext would break the E2EE claim.

### 3.10 Web retrieval / decryption behaviour

**Must be agreed. The device assumes:**

- The web fetches **ciphertext** and decrypts **in the client**, using the key material granted to
  that device (`FULL_HISTORY` / per-category grants already modelled).
- A viewer without the key must be shown "attachment unavailable" — never a broken image and never a
  server-side plaintext rendition.
- Range requests for partial media playback operate on the **encrypted** object; the client decrypts
  the ranges it needs. This constrains the chunking scheme (3.4) and must be settled with the
  server, since it determines whether byte-range access is usable at all.
- Download of a large attachment should not be required merely to show a thumbnail; if thumbnails are
  wanted, they must be **generated on the device and uploaded as their own encrypted asset**.

### 3.11 Deletion / revocation behaviour

- **Message deleted on the device** → the attachment becomes garbage. Today
  `TelephonySyncCoordinator.kt:735` already deletes the local `message_assets` rows; the contract
  must extend that to a server-side delete of the attachment (or let server retention follow the
  message).
- **Deleted while an upload is in flight** → the device must abort and the server must not retain a
  partially uploaded orphan; both sides need the same rule, so this is called out rather than
  assumed.
- **Device revoked / trust withdrawn** → previously uploaded attachments remain readable by
  devices that still hold the key; revocation stops *future* uploads and *future* key grants. Key
  rotation must not require re-uploading attachments, which is a constraint on 3.4.
- Deletion is **not** a security boundary against a party that already copied the ciphertext; the
  contract should say so rather than imply otherwise.

---

## 4. Android-side prerequisites (protocol-neutral, safe to implement now)

These need no server endpoint and change no protocol. In the order they should be done:

1. **Diagnostics visibility (the most valuable, and the one that upholds "never silently
   incomplete"):** report the attachment gap as a *measured* fact — how many MMS messages in the
   mirror have local attachments, and that zero have been replicated. Following this project's
   diagnostics discipline it must be reported as measured / not-measured / not-run, never as `0`
   standing for "fine".
2. **Persist `sizeBytes`** on `message_assets` (schema addition; `0` = unknown, matching
   `MmsPartMetadata.size`'s existing contract) so the field exists before the upload path needs it.
   **A size is stored, not read, by anything yet — recorded explicitly so this is a deliberate,
   documented prerequisite rather than the unwired abstraction this project has already removed
   once.**
3. **A completeness rule with a real caller**, not a floating policy: an MMS is attachment-complete
   only when every known attachment is accepted; until the upload channel exists the truthful answer
   is always "incomplete", and it should be derived where the message's replication status is
   already computed rather than in a new object nothing calls.
4. **A content hash** computed at upload time (never stored as plaintext-derived state before that),
   so the server can dedupe and verify.

**Not to be done:** any client for an endpoint that has not been agreed; any attachment bytes in the
event batch; any "complete" flag that can be set while attachments are un-uploaded.

---

## 5. What GMweb must decide before Android work continues

1. The attachment upload endpoint(s): chunk size, resume protocol, and whether byte-range reads are
   supported.
2. The server's maximum attachment size, and the response for one that exceeds it.
3. Whether `MMS_ATTACHMENT_ADDED` (3.6) is accepted as a new event type, and its schema.
4. Storage/retention rules, including delete-on-message-delete and in-flight-delete.
5. How a web viewer without the key renders an attachment (the "unavailable" state), so the device
   and the web agree on what a keyless view looks like.

Until 1–5 are answered, the Android side can only do Section 4 — and must keep reporting MMS
attachments as **not replicated** rather than approximating them.
