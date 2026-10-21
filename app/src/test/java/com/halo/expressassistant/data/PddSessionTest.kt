package com.halo.expressassistant.data

import com.halo.expressassistant.ui.PddCapture
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PddSessionTest {
    @Test fun userIdCanBeFirstCookie() {
        assertEquals("1234567890", PddSession.userId("pdd_user_id=1234567890; PDDAccessToken=test=="))
        assertEquals("test==", PddSession.cookies("PDDAccessToken=test==")["PDDAccessToken"])
    }

    @Test fun identityUsesFullUidAndSupportsLegacyUid() {
        assertNotEquals(PddSession.userId("pdduid=111123456"), PddSession.userId("pdduid=222123456"))
        assertEquals("1234", PddSession.userId("pdd_user_id=; pdduid=1234"))
    }

    @Test fun incompleteCookiesDoNotProveLogin() {
        assertFalse(PddSession.hasCredentials("PDDAccessToken=test; PASS_ID=test"))
        assertFalse(PddSession.hasCredentials("pdd_user_id=1234"))
        assertTrue(PddSession.hasCredentials("pdd_user_id=1234; PDDAccessToken=test"))
    }

    @Test fun emptyAuthenticatedOrdersAreValid() {
        assertTrue(PddCapture.authenticatedOrders("{\"orders\":[]}"))
        assertTrue(PddCapture.authenticatedOrders("{\"data\":{\"orders\":[]}}"))
    }

    @Test fun errorsAndUnrelatedResponsesDoNotProveLogin() {
        for (body in listOf("<html>login</html>", "{}", "null", "{\"success\":false,\"orders\":[]}",
            "{\"error_code\":40001,\"orders\":[]}", "{\"errorCode\":40001,\"orders\":[]}")) {
            assertFalse(body, PddCapture.authenticatedOrders(body))
        }
    }

    @Test fun captureDecodingPreservesEscapedOrderData() {
        val body = JSONObject().put("orders", JSONArray().put(JSONObject().put("name", "引号\" 换行\n 路径\\中文"))).toString()
        val captured = JSONArray().put(JSONObject().put("u", "/proxy/api/order").put("b", body)).toString()
        assertEquals(listOf("/proxy/api/order" to body), PddCapture.decodeCaptures(JSONObject.quote(captured)))
        assertTrue(PddCapture.decodeCaptures("null").isEmpty())
    }
}
