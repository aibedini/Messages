# EveChat visitor profile — app ⇄ server contract

Status: **verified against `aibedini/evechat` @ `9bfce12` (2026-10-23)**.
The Android app now renders the visitor profile inside the support chat. This document records
exactly which server fields it reads, so the server project can treat them as a contract — and
lists the four changes that would make the profile *live* instead of *fetched*.

> **TL;DR** — no server work is required for the feature to ship. `GET /api/v1/conversations/{id}`
> already returns device, OS, browser, language, IP, country, EVE user/reseller, the subscription
> block and host-page `metadata`. The gaps are freshness and localisation, not missing fields.

---

## 1. What the app reads

`GET /api/v1/conversations/{id}` answers:

```json
{
  "conversation": {
    "id": "c_123",
    "channel": "web",
    "page_url": "https://eve.rootteam.ir/s/9/…",
    "page_title": "خرید اشتراک",
    "referrer": "https://google.com/",
    "visitor": { … },
    "agent":   { … }
  },
  "messages": [ … ],
  "ticket": null
}
```

The embedded `visitor` is the whole `models.Visitor` struct
(`internal/models/models.go:352`), assembled by `enrichConversations`
(`internal/handlers/conversations.go:38`) and serialised by `GetConversation`
(`internal/handlers/conversations.go:119`). The same object is attached to every row of
`GET /api/v1/conversations`, so the inbox already carries it too.

| JSON (under `conversation.visitor`) | App field | Sheet section | Source on the server |
|---|---|---|---|
| `name` | `name` | Identity | host page → `services/visitor.go:108` |
| `phone`, `eve_mobile` | `mobile` | Identity | `merge()`, `services/visitor.go:116` |
| `email` | `email` | Identity | `services/visitor.go:113` |
| `eve_user_id` | `eveUserId` | Identity | widget payload, `handlers/widget.go:134` |
| `eve_reseller_id` | `eveResellerId` | Identity | widget payload |
| `eve_subscription_info` | `subscription` | Plan and subscription | widget payload, merged key-by-key |
| `device_type` | `deviceType` | Device | UA parse, `services/visitor.go:82` |
| `os` | `os` | Device | UA parse |
| `browser` | `browser` | Device | UA parse |
| `language` | `language` | Device | `Accept-Language` of the widget call |
| `ip` | `ip` | Network | request IP, `services/visitor.go:86` |
| `country` | `country` | Network | optional (currently unfilled) |
| `user_agent` | `userAgent` | (kept, not shown) | raw UA |
| `first_seen_at`, `last_seen_at` | same | Activity | visitor row |
| `is_banned` | `isBanned` | header pill | moderation |
| `metadata` | `metadata` | Extra data | arbitrary host-page object |
| `conversation.page_url`, `page_title`, `referrer` | same | Current page | `services/conversation.go:66` |

**Nothing is persisted.** The app keeps the last successful fetch in memory and shows
"Updated 14:03". A field the server omits is hidden, not rendered as "—", so the sheet cannot
claim knowledge the server did not send.

### Subscription key resolution

`eve_subscription_info` is an untyped map, so the app accepts the spellings the integrations
actually send:

| Meaning | Accepted keys (case/underscore-insensitive) |
|---|---|
| plan | `plan`, `subscription_plan`, `plan_name`, `package` |
| expiry | `expiry`, `subscription_expiry`, `expire`, `expires_at`, `end_date` |
| days left | `days_left`, `remaining_days`, `days_remaining` |

Expiry accepts RFC3339, `YYYY-MM-DD`, `YYYY-MM-DD HH:MM:SS` and epoch (seconds or millis).
Any other key is shown verbatim in the same section, so nothing is silently lost.
Placeholders (`none`, `-`, `null`, `0`) are treated as "no subscription".

---

## 2. What is missing today

| # | Gap | Why it matters | Effort |
|---|---|---|---|
| A1 | API errors are Persian-only | An English build shows English chrome; any 4xx detail is Persian. The app already hides `message` for this reason. | S |
| A2 | `page_url` is frozen at conversation start | "Current page" shows where the chat began, not where the visitor is. | M |
| A3 | `eve_subscription_info` is untyped and client-parsed | Plan/expiry aliases drift; `days_left` is computed on a client clock that can be wrong. | S |
| A4 | Profile changes are not pushed | If the visitor signs in on the host site mid-chat, the sheet shows the old identity until the next REST fetch. | S |

---

## 3. Proposed server changes

### A1 — Localise API errors (`Accept-Language`)

Today `internal/i18n/fa.go` states "FA is the default server-side locale", and `apierr`
embeds Persian text in every error (`docs/API.md:5`). Proposed:

1. Add `internal/i18n/en.go` next to `fa.go` with the same message keys.
2. In the error writer, pick the catalog from `Accept-Language` (`en*` → English, default `fa`).
3. Keep `message` human-readable; keep `code` stable — clients branch on the code, never the text.

```bash
curl -s -H 'Accept-Language: en' "$BASE/api/v1/conversations/nope" -H "Authorization: Bearer $TOKEN"
# { "code": "not_found", "message": "Conversation not found", "details": null }
```

Backwards compatible: a client that sends no header keeps getting Persian.

### A2 — Keep `page_url` current

`services/conversation.go:66` writes `page_url` once, from the `page` sent at
`POST /api/widget/v1/session`. The widget knows about navigations (it re-posts the session for
its own state). Proposed:

1. `POST /api/widget/v1/session` accepts `page` on *resume* too and updates
   `conversations.page_url / page_title / referrer` for the active conversation when they changed.
2. Broadcast `conversation.updated` with `{"conversation_id", "page_url", "page_title"}`.

Client follow-up (not in this change): parse those two fields in `ConversationPatch` and show the
new page without a refetch.

```bash
# after the widget posts a new page
curl -s "$BASE/api/v1/conversations/$CONV" -H "Authorization: Bearer $TOKEN" | jq '.conversation.page_url'
```

### A3 — Normalise the subscription block

The dashboard and the app both re-derive plan/expiry from a loose map. Proposed: when the widget
posts `visitor.subscription`, the server writes a *typed* projection alongside the raw map:

```json
"eve_subscription": {
  "plan": "S17-Azin",
  "expiry": "2026-11-22T00:00:00Z",
  "days_left": 30,
  "active": true,
  "raw": { "plan": "S17-Azin", "expiry": "2026-11-22" }
}
```

`days_left` is computed **server-side** (the phone's clock is not authoritative) and refreshed on
read. Keep `eve_subscription_info` unchanged so older app builds and the dashboard keep working;
the app prefers `eve_subscription` when present.

Acceptance:

```bash
curl -s -X POST "$BASE/api/widget/v1/session?project_key=$KEY" \
  -H 'Content-Type: application/json' \
  -d '{"token":"<tok>","visitor":{"subscription":{"plan":"Pro","expiry":"2026-12-01"}}}' >/dev/null
curl -s "$BASE/api/v1/conversations/$CONV" -H "Authorization: Bearer $TOKEN" \
  | jq '.conversation.visitor.eve_subscription'
```

### A4 — Push profile changes

Emit `visitor.updated` to the conversation room when identity fields change mid-chat
(`eve_user_id`, `eve_reseller_id`, subscription, name/phone). Payload: the same `visitor`
object. Recipients: the conversation's room (agents) — same rules as `conversation.updated`.

The app can consume it in one line (`_visitors + (conversationId to snapshot)`); until then it
refreshes on open and on the sheet's refresh button.

---

## 4. Client behaviour when fields are missing

| Situation | What the sheet shows |
|---|---|
| `visitor` absent (older server) | "The server sent no profile details for this visitor." + Refresh |
| `visitor` present, field null | That row is omitted |
| `eve_subscription_info` empty | Plan: "No subscription" |
| Expiry in the past | "Expired" |
| Server returns 4xx | Localised kind message; the server's `message` is logged, not shown |

## 5. Security notes

- The profile is only ever read by an authenticated agent and only for conversations the
  authorization matrix allows (`conversationAccess`, `internal/handlers/conversations.go:23`).
- IP, phone and email are personal data: the app shows them in-session only and never writes them
  to its Room cache or logs.
- `user_agent` is parsed but not displayed.
