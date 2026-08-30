package com.autonomousone.messages.navigation

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * v2.6.18 field bug: opening a conversation whose contact display name
 * contains a space ("hamid dadash") showed the header as "hamid+dadash".
 * Root cause: createRoute FORM-encoded args (URLEncoder: space → '+'),
 * while Navigation decodes query args percent-style (Uri.getQueryParameter:
 * '+' is a LITERAL plus, only %20 is a space).
 */
class NavigationRouteEncodingTest {

    private fun navDecode(raw: String): String =
        URLDecoder.decode(raw, StandardCharsets.UTF_8.name())

    @Test
    fun `space in name encodes as %20 not plus`() {
        val route = Screen.Conversation.createRoute(5L, phone = "+989120000000", name = "hamid dadash")
        val nameArg = route.substringAfter("name=").substringBefore("&")
        assertEquals("hamid%20dadash", nameArg)
    }

    @Test
    fun `nav roundtrip restores spaced name exactly`() {
        val name = "hamid dadash"
        val route = Screen.Conversation.createRoute(5L, name = name)
        val decoded = navDecode(route.substringAfter("name=").substringBefore("&"))
        assertEquals(name, decoded)
    }

    @Test
    fun `phone plus prefix survives roundtrip`() {
        val phone = "+989121234567"
        val route = Screen.Conversation.createRoute(1L, phone = phone, name = "x")
        val decoded = navDecode(route.substringAfter("phone=").substringBefore("&"))
        assertEquals(phone, decoded)
    }

    @Test
    fun `persian name roundtrips`() {
        val name = "حمید داداش"
        val route = Screen.Conversation.createNewRoute("+989120000000", name)
        val decoded = navDecode(route.substringAfter("name=").substringBefore("&"))
        assertEquals(name, decoded)
    }

    @Test
    fun `ampersand in name cannot split query args`() {
        // encode() must escape '&' (%26) so the arg stays one value.
        val route = Screen.Conversation.createRoute(9L, name = "a&b=c")
        assertFalse("raw & leaked into route", route.substringAfter("name=").contains("&b=c"))
        val value = route.substringAfter("name=").substringBefore("&forward")
        assertEquals("a%26b%3Dc", value)
    }

    @Test
    fun `forward text percent signs are not double decoded`() {
        // Message text may legitimately contain "%20"; encoding must escape
        // the '%' itself so a single decode returns the literal text.
        val text = "off %20 today"
        val route = Screen.NewConversation.createForwardRoute(text)
        val decoded = navDecode(route.substringAfter("forward="))
        assertEquals(text, decoded)
    }
}
