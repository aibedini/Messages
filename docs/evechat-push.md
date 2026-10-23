# EveChat push (FCM) — app ⇄ server contract

The Android app delivers support messages through three channels, in order of preference:

1. **WebSocket** `/ws/agent` while the app is open (and for 2 minutes after it goes to the background).
2. **FCM push** (this document) when the app is in the background or closed.
3. **Periodic sync** (WorkManager): every 15 minutes without push, every 30 minutes when push is active. Push can be delayed or dropped, so polling never stops.

A push is a **hint**, not the data. On every push the app re-reads the conversation over REST and builds the notification from what it fetched. The `title`/`body` fields are only shown when that fetch is impossible (no network within the time budget).

The app side is complete. Push stays inactive until **both** of these exist:

- a Firebase config in the app build (`google-services.json`), and
- the two endpoints below plus FCM sending on the EveChat server.

Until then the app logs `server has no push endpoint` once a day and keeps using the socket and polling.

---

## 1. Firebase setup

1. Create a Firebase project and add an Android app with package name `com.autonomousone.messages`.
2. Download `google-services.json` and put it in `app/` (or `app/src/debug/` / `app/src/release/` for separate projects). The build applies the Google Services plugin only when this file exists; without it the build is unchanged.
3. For the server: in *Project settings → Service accounts*, generate a private key (JSON). The server uses it to call the FCM HTTP v1 API. **This key is a secret: never ship it in the app or commit it.**
4. CI: store `google-services.json` as a secret and write it to `app/google-services.json` before `assembleRelease` if release builds should have push.

Privacy defaults in the app: FCM auto-init is **off** until an agent signs in to EveChat (SMS-only users never get a token), Analytics collection is off, and the token is deleted on sign-out.

---

## 2. Device registration API

All calls use the agent's normal `Authorization: Bearer <access token>`.

### `POST /api/v1/push/devices`

Upsert keyed by `device_id`. The device belongs to the agent identified by the bearer token; if the same `device_id` was registered by another agent before, move it to the caller.

```json
{
  "device_id": "4f0a…-uuid",
  "platform": "android",
  "provider": "fcm",
  "token": "<FCM registration token>",
  "app_version": "3.9.0",
  "app_build": 155,
  "os_version": "Android 14 (34)",
  "device_model": "samsung SM-A546E",
  "locale": "fa-IR",
  "timezone": "Asia/Tehran"
}
```

Responses: `200`/`201` (body ignored). Validation errors as the usual `{"code","message"}`.

When the app re-registers: on sign-in, when FCM issues a new token, when the server URL, agent or app build changes, and at least every 7 days.

### `DELETE /api/v1/push/devices/{device_id}`

Called on sign-out (best effort, 5 s budget). `200`/`204`; `404` is fine.

### Signalling "not supported"

If the server answers `404`, `405` or `501` to `POST /push/devices`, the app treats push as unsupported for that server and asks again after 24 hours. Any other failure is retried with exponential backoff.

### Suggested table

| column | notes |
|---|---|
| `device_id` (PK) | from the app |
| `agent_id` | from the token, indexed |
| `project_id` | from the agent |
| `token` | unique |
| `platform`, `provider`, `app_version`, `app_build`, `os_version`, `device_model`, `locale`, `timezone` | diagnostics |
| `created_at`, `updated_at`, `last_success_at` | for pruning |

Delete rows whose token FCM reports as `UNREGISTERED` or `INVALID_ARGUMENT` (404/400 from the v1 API), and rows not refreshed for 60 days.

---

## 3. Push payload

**Data-only messages.** Do not send a `notification` block: Android would display it itself and the app could not dedupe, mark read or deep-link. All values are strings (FCM requirement).

| key | required | value |
|---|---|---|
| `source` | yes | always `evechat` (the app ignores anything else) |
| `v` | yes | payload version, currently `1`. A higher version makes the app do a full sync instead of trusting the fields |
| `type` | yes | `message.received`, `conversation.new`, `conversation.updated`, `conversation.resolved`, `agent.assigned` or `sync`. Unknown types trigger a full sync |
| `conversation_id` | for all but `sync` | |
| `message_id` | for `message.received` / `conversation.new` | lets the app skip messages it already has |
| `agent_id` | yes | the **recipient** agent. The app drops pushes for a different agent and re-registers |
| `sent_at` | recommended | unix milliseconds, used for latency logging |
| `title` | optional | visitor display name, for the offline fallback notification |
| `body` | optional | message preview, for the offline fallback. Omit it if previews must not transit Google |

### Android options (FCM HTTP v1)

| event | `android.priority` | `android.ttl` | `android.collapse_key` |
|---|---|---|---|
| `message.received`, `conversation.new` | `HIGH` | `3600s` | — |
| `conversation.updated`, `conversation.resolved`, `agent.assigned` | `NORMAL` | `600s` | `conversation_id` |
| `sync` | `NORMAL` | `600s` | `sync` |

Only use `HIGH` for messages a person should see; Android lowers the priority of apps that send high-priority pushes that do not result in a notification (the app logs this as `deprioritized`).

Example:

```json
POST https://fcm.googleapis.com/v1/projects/<project-id>/messages:send
{
  "message": {
    "token": "<device token>",
    "android": { "priority": "HIGH", "ttl": "3600s" },
    "data": {
      "source": "evechat",
      "v": "1",
      "type": "message.received",
      "conversation_id": "c_123",
      "message_id": "m_456",
      "agent_id": "a_789",
      "sent_at": "1792663200000",
      "title": "Sara",
      "body": "Hello, my order…"
    }
  }
}
```

---

## 4. Who gets which push

Mirror the socket rules the server already applies:

| server event | recipients |
|---|---|
| new conversation (unassigned) | every active agent of the project with a registered device |
| visitor message in an unassigned conversation | every active agent of the project |
| visitor message in an assigned conversation | the assigned agent only |
| assignment / claim | the new assignee (`agent.assigned`), plus the other agents with `conversation.updated` so their notification is withdrawn |
| resolve / reopen | the assigned agent (`conversation.resolved` / `conversation.updated`) |
| internal notes, agent messages, system messages | nobody |

Skip sending to an agent whose WebSocket is currently connected **and** in the conversation room only if you can be sure the socket is healthy; when unsure, send — the app deduplicates by `message_id`.

---

## 5. What the app does on a push

1. Drops it unless `source=evechat`, the session is signed in, and `agent_id` matches.
2. If the app is open with a live socket: runs a catch-up only (the socket already delivered it).
3. Otherwise, within an 8 s budget: fetches the conversation and any messages newer than the cache, then notifies new visitor messages that were not already cached (so socket, push and polling never announce the same message twice) and only if the conversation is still unread and open.
4. If the fetch fails or the budget runs out: shows a fallback notification from `title`/`body` and schedules a sync as soon as the network allows.
5. `onDeletedMessages` (FCM dropped pending messages): schedules a full sync.

Everything is logged under the `EVECHAT` tag with `c=push` in the diagnostics export (Settings → export diagnostics), with tokens redacted.
