package com.autonomousone.messages.evechat.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * A profile plus the moment it arrived, so the sheet can be honest about freshness
 * ("Updated 14:03") instead of implying the plan/IP are live.
 */
data class EveChatVisitorSnapshot(
    val visitor: EveChatVisitor,
    val fetchedAt: Long = System.currentTimeMillis()
)

/**
 * The visitor profile the EveChat server attaches to a conversation.
 *
 * The dashboard's "visitor" card and this sheet are backed by the same server object
 * (`visitor` in `GET /api/v1/conversations/{id}`): identity, device, network, the EVE
 * subscription block and any host-page metadata. The server owns those fields; the app only
 * renders what it sent and never invents a value.
 *
 * Parsed fresh on every detail fetch and held in memory — it is *server state*, not something
 * the app may mutate, so it is deliberately not persisted in the EveChat Room database.
 */
data class EveChatVisitor(
    val id: String,
    val name: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val eveMobile: String? = null,
    val eveUserId: String? = null,
    val eveResellerId: String? = null,
    val deviceType: String? = null,
    val os: String? = null,
    val browser: String? = null,
    val userAgent: String? = null,
    val language: String? = null,
    val ip: String? = null,
    val country: String? = null,
    val isBanned: Boolean = false,
    val firstSeenAt: Long? = null,
    val lastSeenAt: Long? = null,
    val subscription: EveChatSubscription? = null,
    /** Keys the host page put in `visitor.metadata`, in the order the server listed them. */
    val metadata: List<Pair<String, String>> = emptyList(),
    // Conversation-scoped context, copied from the conversation JSON at parse time.
    val pageUrl: String? = null,
    val pageTitle: String? = null,
    val referrer: String? = null,
    val channel: String? = null
) {
    /** Best available human label; empty when the server sent no identity at all. */
    val displayName: String
        get() = name ?: phone ?: eveMobile ?: email ?: ""

    /** The mobile number the visitor left, whichever field it arrived in. */
    val mobile: String?
        get() = phone ?: eveMobile

    val hasAnything: Boolean
        get() = listOf(
            name, email, mobile, eveUserId, eveResellerId, deviceType, os, browser, language,
            ip, country, pageUrl
        ).any { !it.isNullOrBlank() } || subscription != null || metadata.isNotEmpty()

    companion object {
        /**
         * @param visitor the `visitor` object
         * @param conversation the conversation it belongs to; used only for page context
         */
        fun fromJson(visitor: JSONObject, conversation: JSONObject? = null): EveChatVisitor {
            val subJson = visitor.optJSONObject("eve_subscription_info")
            return EveChatVisitor(
                id = visitor.optStringOrNull("id") ?: "",
                name = visitor.optStringOrNull("name"),
                email = visitor.optStringOrNull("email"),
                phone = visitor.optStringOrNull("phone"),
                eveMobile = visitor.optStringOrNull("eve_mobile"),
                eveUserId = visitor.optStringOrNull("eve_user_id"),
                eveResellerId = visitor.optStringOrNull("eve_reseller_id"),
                deviceType = visitor.optStringOrNull("device_type"),
                os = visitor.optStringOrNull("os"),
                browser = visitor.optStringOrNull("browser"),
                userAgent = visitor.optStringOrNull("user_agent"),
                language = visitor.optStringOrNull("language"),
                ip = visitor.optStringOrNull("ip"),
                country = visitor.optStringOrNull("country"),
                isBanned = visitor.optBoolean("is_banned", false),
                firstSeenAt = EveChatTime.parse(visitor.optStringOrNull("first_seen_at")),
                lastSeenAt = EveChatTime.parse(visitor.optStringOrNull("last_seen_at")),
                subscription = EveChatSubscription.fromJson(subJson),
                metadata = stringMap(visitor.optJSONObject("metadata")),
                pageUrl = conversation?.optStringOrNull("page_url"),
                pageTitle = conversation?.optStringOrNull("page_title"),
                referrer = conversation?.optStringOrNull("referrer"),
                channel = conversation?.optStringOrNull("channel")
            )
        }

        /**
         * @return key/value pairs in a STABLE order (by key). org.json iterates a hash map on the
         * JVM and a linked map on Android, so without sorting the same payload could render its
         * rows in a different order on every fetch.
         */
        internal fun stringMap(obj: JSONObject?): List<Pair<String, String>> {
            if (obj == null) return emptyList()
            val out = ArrayList<Pair<String, String>>(obj.length())
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (obj.isNull(key)) continue
                val text = render(obj.opt(key)) ?: continue
                out += key to text
            }
            return out.sortedBy { it.first.lowercase(Locale.ROOT) }
        }

        /** JSON values are untyped in this protocol; show scalars as text and containers as JSON. */
        internal fun render(value: Any?): String? = when (value) {
            null, JSONObject.NULL -> null
            is String -> value.trim().takeIf { it.isNotEmpty() }
            is JSONObject, is JSONArray -> value.toString()
            else -> value.toString()
        }
    }
}

/**
 * The EVE subscription the host page reported.
 *
 * The widget contract is loose: keys arrive as `plan`/`subscription_plan` and
 * `expiry`/`subscription_expiry` depending on the integration, so both spellings are read and
 * anything unrecognised is surfaced verbatim instead of being dropped.
 */
data class EveChatSubscription(
    val plan: String? = null,
    val expiry: String? = null,
    val expiryAt: Long? = null,
    val serverDaysLeft: Int? = null,
    val extra: List<Pair<String, String>> = emptyList()
) {
    /** Days until expiry: the server's own count when given, otherwise derived from the date. */
    fun daysLeft(now: Long = System.currentTimeMillis()): Int? {
        serverDaysLeft?.let { return it }
        val at = expiryAt ?: return null
        return Math.ceil((at - now) / 86_400_000.0).toInt()
    }

    val isExpired: Boolean
        get() = daysLeft()?.let { it < 0 } ?: false

    val isEmpty: Boolean
        get() = plan.isNullOrBlank() && expiry.isNullOrBlank() && extra.isEmpty()

    companion object {
        private val PLAN_KEYS = setOf("plan", "subscriptionplan", "planname", "package", "packagename")
        private val EXPIRY_KEYS = setOf(
            "expiry", "subscriptionexpiry", "expire", "expires", "expiresat", "expireat",
            "enddate", "expirydate"
        )
        private val DAYS_KEYS = setOf("daysleft", "remainingdays", "daysremaining", "dayleft", "remaining")

        /**
         * Some integrations report "no subscription" as a literal placeholder rather than
         * omitting the key. Those read as a plan to a human ("—", "none"), so they are absent.
         */
        private val PLACEHOLDER_PLANS = setOf("-", "—", "none", "null", "n/a", "na", "0", "false")

        /** Epoch values below 1e11 are seconds, not milliseconds. */
        private fun parseEpoch(text: String): Long? {
            val numeric = text.toLongOrNull()
            if (numeric != null) return if (numeric < 100_000_000_000L) numeric * 1000 else numeric
            return EveChatTime.parse(text)
        }

        private fun normalise(key: String) =
            key.lowercase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "")

        fun fromJson(obj: JSONObject?): EveChatSubscription? {
            if (obj == null || obj.length() == 0) return null
            var plan: String? = null
            var expiry: String? = null
            var expiryAt: Long? = null
            var days: Int? = null
            val extra = ArrayList<Pair<String, String>>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (obj.isNull(key)) continue
                val raw = obj.opt(key)
                val text = EveChatVisitor.render(raw) ?: continue
                when (normalise(key)) {
                    in PLAN_KEYS -> plan = text
                    in EXPIRY_KEYS -> {
                        expiry = text
                        expiryAt = parseEpoch(text)
                    }
                    in DAYS_KEYS -> days = text.trim().toDoubleOrNull()?.let { Math.round(it).toInt() }
                    else -> extra += key to text
                }
            }
            plan = plan?.trim()?.takeIf { it.isNotEmpty() && it.lowercase(Locale.ROOT) !in PLACEHOLDER_PLANS }
            if (plan == null && expiry == null && days == null && extra.isEmpty()) return null
            return EveChatSubscription(plan, expiry, expiryAt, days, extra.sortedBy { it.first.lowercase(Locale.ROOT) })
        }
    }
}
