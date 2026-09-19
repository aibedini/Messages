package com.autonomousone.messages.navigation

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed class Screen(val route: String) {

    object Home : Screen("home")

    object NewConversation :
        Screen("new_conversation?forward={forward}&draft={draft}&shared_phone={shared_phone}") {

        /** Route used from the nav graph declaration. */
        val baseRoute: String = "new_conversation"

        fun createForwardRoute(text: String): String =
            "new_conversation?forward=${encode(text)}"

        /**
         * External share/send entry point. [phone] pre-fills the recipient
         * search, [draftText] lands in the composer as a DRAFT — the user
         * still presses Send.
         */
        fun createDraftRoute(phone: String, draftText: String): String =
            "new_conversation?forward=&draft=${encode(draftText)}&shared_phone=${encode(phone)}"
    }

    object Gateway : Screen("gateway")

    companion object {
        /**
         * v2.6.12: a navigation argument that still contains a route
         * placeholder ("{forward}", "{draft}") is a leaked PATTERN, never
         * user data. Navigating with a pattern route (instead of a filled
         * route) makes Navigation hand the literal "{forward}" back as the
         * argument value and the composer "types" it. Sanitizing at the
         * argument layer covers every current and future caller — including
         * a process-death back-stack restore of a stale bad route.
         */
        fun cleanArg(raw: String?): String =
            raw?.takeIf { it.isNotEmpty() && '{' !in it && '}' !in it } ?: ""

        /**
         * v2.6.18: percent-encode a navigation argument. Deliberately NOT
         * URLEncoder.encode — that is a FORM encoder ("a b" -> "a+b") while
         * URI/Nav query decoding is percent-style ("a+b" means the literal
         * name "a+b"). Form-encoding a contact display name turned
         * "hamid dadash" into the header text "hamid+dadash".
         */
        fun encode(text: String): String =
            URLEncoder.encode(text, StandardCharsets.UTF_8.name())
                .replace("+", "%20")
    }

    object Settings : Screen("settings")

    object MessagingSettings : Screen("messaging_settings")

    object LinkedDevices : Screen("linked_devices")

    object AppearanceSettings : Screen("appearance_settings")

    object QuickReplies : Screen("quick_replies")

    object ScheduledMessages : Screen("scheduled_messages")

    /**
     * Conversation Info (v3.4.0 FEATURE 5).
     *
     * `phone`/`name` are carried from the conversation the user came from so the
     * header paints IMMEDIATELY (no blank title while the contact lookup runs) and
     * so a thread reached without an address (e.g. from the global Starred list)
     * still has a usable one. Arguments follow the [Conversation] conventions: a
     * Long path segment plus percent-encoded query args ([encode]), read back with
     * [cleanArg] so a leaked route PATTERN can never render as user data.
     *
     * Wired centrally in AppNavigation.kt by the coordinator.
     */
    object ConversationInfo :
        Screen("conversation_info/{threadId}?phone={phone}&name={name}") {

        fun createRoute(threadId: Long, phone: String = "", name: String = ""): String =
            "conversation_info/$threadId?phone=${encode(phone)}&name=${encode(name)}"
    }

    /**
     * Starred messages inside ONE conversation (v3.4.0 FEATURE 7).
     *
     * Deliberately separate from [StarredMessages] rather than one route with an
     * optional thread argument: a wired argument cannot be optional, so the global
     * list can never be reached through a thread route whose id was lost — which
     * would silently show every conversation's stars under a conversation's title.
     */
    object ConversationStarred : Screen("conversation_starred/{threadId}") {

        fun createRoute(threadId: Long): String = "conversation_starred/$threadId"
    }

    /** Global Starred browser (v3.4.0 FEATURE 7). */
    object StarredMessages : Screen("starred_messages")

    object Conversation :
        Screen(
            "conversation/{threadId}?phone={phone}&name={name}&forward={forward}" +
                "&draft={draft}&hitSource={hitSource}&hitProviderId={hitProviderId}"
        ) {

        fun createRoute(
            threadId: Long,
            phone: String = "",
            name: String = "",
            forward: String = "",
            draft: String = ""
        ): String =
            "conversation/$threadId" +
                "?phone=${encode(phone)}&name=${encode(name)}" +
                "&forward=${encode(forward)}&draft=${encode(draft)}"

        fun createNewRoute(
            phone: String,
            name: String,
            forward: String = "",
            draft: String = ""
        ): String =
            "conversation/0" +
                "?phone=${encode(phone)}&name=${encode(name)}" +
                "&forward=${encode(forward)}&draft=${encode(draft)}"

        /**
         * Opens a conversation AND lands on ONE exact message (v3.4.0).
         *
         * Used by the Starred browsers (FEATURE 7), whose rows carry the composite
         * identity (source, providerId) of the message the user tapped. The identity
         * is passed as `hit` arguments rather than a raw id because SMS `_id` 100 and
         * MMS `_id` 100 are DIFFERENT messages: a bare Long would open the wrong one
         * half the time.
         *
         * `hitSource` is the MessageEntity source vocabulary ("sms" / "mms") and is
         * empty for a normal open, which is why both args default to "".
         */
        fun createHitRoute(threadId: Long, source: String, providerId: Long): String =
            "conversation/$threadId" +
                "?phone=&name=&forward=&draft=" +
                "&hitSource=${encode(source)}&hitProviderId=$providerId"
    }

    /**
     * v3.4.0 FEATURE 6 — Media / Links / Files browser for ONE conversation.
     *
     * Same argument conventions as [Conversation]: a Long `threadId` path segment
     * and a percent-encoded `name` (see [encode]); args are read with
     * `Screen.cleanArg` so a leaked route PATTERN can never be shown as a name.
     * Wired centrally in AppNavigation.kt by the coordinator.
     */
    object ConversationMedia : Screen("conversation_media/{threadId}?name={name}") {

        fun createRoute(threadId: Long, name: String = ""): String =
            "conversation_media/$threadId?name=${encode(name)}"
    }

    /**
     * Recently Deleted / Trash (v3.4.0 FEATURE 8).
     *
     * No arguments: Trash is a global list of conversation tombstones, each row
     * carrying its own threadId. The screen restores a row, permanently deletes a
     * row, or empties Trash — all by id from the list, so a deep link cannot ask
     * for a purge of an arbitrary thread.
     *
     * Wired centrally in AppNavigation.kt by the coordinator:
     *
     *     composable(Screen.Trash.route) {
     *         RecentlyDeletedScreen(navController = navController)
     *     }
     */
    object Trash : Screen("trash")
}
