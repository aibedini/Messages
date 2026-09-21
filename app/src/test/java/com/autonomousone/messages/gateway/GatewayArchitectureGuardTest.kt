package com.autonomousone.messages.gateway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Architectural guards for the ONE-GMweb-server rule (v3.4.6).
 *
 * These are SOURCE scans, not behaviour tests, and that is deliberate. The failure they
 * prevent is a regression by ADDITION: someone adds a helper that reads a second URL, or a
 * convenience fallback that hardcodes the deployment, and every behavioural test still passes
 * because no test exercises the new path. A grep-level guard is the only kind that catches it.
 *
 * The specific thing being protected: the app used to hold `gmwebUrl` AND `backendUrl`, with
 * `backendUrl` defaulting to a domain compiled into the APK — so a phone could pull its
 * send-requests from one server and upload its events to another, with nothing in the UI
 * saying so.
 */
class GatewayArchitectureGuardTest {

    private val mainRoots = listOf("src/main", "app/src/main")

    private fun mainSources(): List<File> =
        mainRoots.map { File(it) }.firstOrNull { it.isDirectory }
            ?.walkTopDown()
            ?.filter { it.isFile && it.extension == "kt" }
            ?.toList()
            ?: error("cannot locate main sources; looked in ${mainRoots.joinToString()}")

    /**
     * Resolves a main-source file by its path BELOW `src/main`, trying the module-relative and
     * repo-relative roots. Returns null instead of throwing so a caller can try the next
     * candidate — an earlier version threw inside the lookup and made the fallback unreachable.
     */
    private fun mainSource(relative: String): File? =
        mainRoots.map { File(it, relative) }.firstOrNull { it.isFile }

    private fun requireMainSource(relative: String): File =
        mainSource(relative) ?: error(
            "cannot locate $relative; looked in " +
                mainRoots.joinToString { File(it, relative).path }
        )

    private fun mainSourceText(name: String): String =
        mainSources().firstOrNull { it.name == name }?.readText()
            ?: error("cannot locate $name among the main sources")

    /**
     * The domains that must never be baked in again — neither the original IP-based host nor the
     * legacy cloud hostname, and not the hostname that DNS-resolves to the same IP.
     */
    private val forbiddenHosts = listOf(
        "gmweb.46.31.76.103.nip.io",
        "gaitway.autonomousone.in",
        "46.31.76.103"
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // No baked production server anywhere in the shipped sources
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `noProductionSourceHardcodesAGmwebHost`() {
        val offenders = mutableListOf<String>()
        mainSources().forEach { file ->
            val text = file.readText()
            forbiddenHosts.forEach { host ->
                if (text.contains(host)) offenders += "${file.path} contains $host"
            }
        }

        assertTrue(
            "a GMweb host must be configuration, never a compiled-in constant:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun `theGradleBuildHasNoDefaultServerDomain`() {
        val build = listOf(File("build.gradle.kts"), File("app/build.gradle.kts"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("cannot locate build.gradle.kts")
        val declaration = build
            .lineSequence()
            .firstOrNull { it.contains("GATEWAY_BACKEND_URL") && it.contains("findProperty") }
            ?: error("the GATEWAY_BACKEND_URL declaration disappeared — update this guard")

        assertFalse(
            "the build property must have no fallback domain, but the declaration is: $declaration",
            declaration.contains("?:")
        )
        assertTrue(
            "an unset property must resolve to an empty string: $declaration",
            declaration.contains("orEmpty()")
        )
    }

    /** Strips line comments and KDoc continuation lines, leaving executable text. */
    private fun codeOnly(text: String): String = text.lineSequence()
        .map { line ->
            val withoutLineComment = line.substringBefore("//")
            // A KDoc/block-comment continuation line starts with `*`; drop those wholesale.
            if (withoutLineComment.trimStart().startsWith("*")) "" else withoutLineComment
        }
        .joinToString("\n")

    @Test
    fun `BuildConfigIsNeverReadAsAServerAddressAtRuntime`() {
        // It may still be NAMED in a comment explaining why it is gone; it must never be USED.
        val offenders = mutableListOf<String>()
        mainSources().forEach { file ->
            codeOnly(file.readText()).lineSequence().forEachIndexed { index, line ->
                if (line.contains("BuildConfig.GATEWAY_BACKEND_URL")) {
                    offenders += "${file.path}:${index + 1}"
                }
            }
        }

        assertTrue(
            "the compiled-in server address must not be read at runtime:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // One authority, and only one
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `theTwoLegacyUrlNamesAreAliasesOfTheSingleOrigin`() {
        val prefs = requireMainSource(
            "java/com/autonomousone/messages/gateway/GatewayPreferences.kt"
        ).readText()

        // Both legacy names must resolve to the single stored origin…
        assertTrue(
            "gmwebUrl must be an alias of gmwebServerOrigin",
            prefs.contains("var gmwebUrl: String") && prefs.contains("get() = gmwebServerOrigin")
        )
        assertTrue(
            "backendUrl must be an alias of gmwebServerOrigin",
            prefs.contains("var backendUrl: String")
        )
        // …and the getter must not fall back to the compiled-in default any more.
        assertFalse(
            "the legacy getter must not fall back to a build constant",
            codeOnly(prefs).contains("BuildConfig.GATEWAY_BACKEND_URL")
        )
    }

    @Test
    fun `theMigrationNeverAdoptsACompiledInDefault`() {
        val text = requireMainSource(
            "java/com/autonomousone/messages/gateway/GatewayPreferences.kt"
        ).readText()

        assertTrue(
            "the migration must ask whether the legacy key was ever WRITTEN",
            text.contains("prefs.contains(KEY_BACKEND_URL)")
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // The UI actually renders the health work, and keeps the two concepts apart
    // ═══════════════════════════════════════════════════════════════════════════

    private fun gatewayScreen(): String = mainSourceText("GatewayScreen.kt")

    @Test
    fun `theGatewayScreenRendersTheHealthAndLogCards`() {
        val screen = gatewayScreen()

        assertTrue(
            "GatewayHealthCard must be rendered, not merely compiled in an unused file",
            screen.contains("GatewayHealthCard(")
        )
        assertTrue(
            "GatewayLogFeedCard must be rendered",
            screen.contains("GatewayLogFeedCard(")
        )
        assertTrue(
            "the legacy raw log card must be gone",
            !screen.contains("Live Server Logs")
        )
    }

    @Test
    fun `theLocalApiCardNeverUsesTheGmwebOrigin`() {
        val screen = gatewayScreen()

        // The card that lists the phone's OWN endpoints must build them from the LAN base.
        assertTrue(
            "the local API base must be the phone's LAN address",
            screen.contains("val localBase = \"http://\${viewModel.localIpAddress}:\${viewModel.port}\"")
        )
        // And the old conflation must not come back: a "Cloud Mode" badge that swapped the base
        // URL of the LOCAL endpoints to the GMweb origin.
        assertFalse("the misleading Cloud Mode badge must be gone", screen.contains("Cloud Mode"))
        assertFalse("the LAN Mode badge must be gone", screen.contains("LAN Mode"))
        assertFalse(
            "the second hardcoded cloud host must be gone",
            screen.contains("https://gaitway.autonomousone.in")
        )
        assertFalse(
            "the local endpoints must not be built from a cloud URL variable",
            screen.contains("val effectiveBaseUrl") || screen.contains("val cloudUrl")
        )
    }

    @Test
    fun `theReconnectLogNoLongerClaimsEndToEndSuccess`() {
        val vm = mainSourceText("GatewayViewModel.kt")

        assertFalse(
            "enrollment success must not be reported as a full reconnection",
            vm.contains("\"✅ Reconnected to cloud backend\"")
        )
        assertTrue(
            "it must name the control plane for what it proves",
            vm.contains("Device identity authenticated")
        )
    }

    @Test
    fun `theEventAckSpamIsNotThePrimaryFeed`() {
        // Comments may quote the OLD line to explain why it is gone; executable code must not
        // contain it.
        val uploader = codeOnly(mainSourceText("EventUploader.kt"))

        assertFalse(
            "the raw 'N/N event(s) ACKed by GMweb' line must no longer be the user-facing message",
            uploader.contains("ACKed by GMweb")
        )
        assertTrue(
            "the user-facing line must describe the sync",
            uploader.contains("Sync uploaded")
        )
    }
}
