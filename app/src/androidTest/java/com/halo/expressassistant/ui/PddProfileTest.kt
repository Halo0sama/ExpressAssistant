package com.halo.expressassistant.ui

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PddProfileTest {
    @Test fun scopedCookieCleanupRemovesParentDomainCookiesAndPreservesOtherPlatforms() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val name = PddWeb.PROFILE_PREFIX + "test-scope-" + java.util.UUID.randomUUID()
        var web: WebView? = null
        lateinit var cm: CookieManager
        try {
            instrumentation.runOnMainSync {
                assumeTrue(PddWeb.isolated)
                web = PddWeb.create(instrumentation.targetContext, name)
                cm = PddWeb.cookieManager(web!!)
            }
            val set = CountDownLatch(3)
            instrumentation.runOnMainSync {
                cm.setCookie(PddWeb.ORDERS_URL, "PDDAccessToken=test; Domain=.yangkeduo.com; Path=/") { set.countDown() }
                cm.setCookie(PddWeb.ORDERS_URL, "pdd_user_id=1234; Path=/") { set.countDown() }
                cm.setCookie("https://trade.m.jd.com", "pt_key=test; Path=/") { set.countDown() }
            }
            assertTrue(set.await(10, TimeUnit.SECONDS))
            assertTrue(cm.getCookie(PddWeb.ORDERS_URL).contains("PDDAccessToken=test"))
            val cleared = CountDownLatch(1)
            instrumentation.runOnMainSync { PddCapture.clearCookies(cm) { cleared.countDown() } }
            assertTrue(cleared.await(10, TimeUnit.SECONDS))
            assertTrue(cm.getCookie(PddWeb.ORDERS_URL).isNullOrBlank())
            assertEquals("pt_key=test", cm.getCookie("https://trade.m.jd.com"))
            val restored = CountDownLatch(1)
            instrumentation.runOnMainSync {
                PddCapture.setCookiesFor("pdd_user_id=5678; PDDAccessToken=fresh", cm) { restored.countDown() }
            }
            assertTrue(restored.await(10, TimeUnit.SECONDS))
            val cookies = com.halo.expressassistant.data.PddSession.cookies(cm.getCookie(PddWeb.ORDERS_URL))
            assertEquals("5678", cookies["pdd_user_id"])
            assertEquals("fresh", cookies["PDDAccessToken"])
            assertEquals("pt_key=test", cm.getCookie("https://trade.m.jd.com"))
        } finally {
            instrumentation.runOnMainSync {
                web?.destroy()
                PddWeb.clearProfile(name)
            }
        }
    }

    @Test fun rebindingUsesFullUidAndPreservesCustomAccountName() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefsName = "pdd-test-" + java.util.UUID.randomUUID()
        val context = object : android.content.ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefsName, mode)
        }
        val channel = com.halo.expressassistant.data.Store.CH_PDD
        try {
            com.halo.expressassistant.data.Store.addPddAccount(context, "pdd_user_id=111123456; PDDAccessToken=one", "profile-a")
            val first = com.halo.expressassistant.data.Store.accounts(context, channel).single()
            com.halo.expressassistant.data.Store.updateAccount(context, channel, first.copy(label = "自定义名称"))
            com.halo.expressassistant.data.Store.addPddAccount(context, "pdd_user_id=111123456; PDDAccessToken=two", "profile-b")
            val rebound = com.halo.expressassistant.data.Store.accounts(context, channel).single()
            assertEquals(first.id, rebound.id)
            assertEquals("自定义名称", rebound.label)
            assertTrue(rebound.payload.contains("profile-b"))
            com.halo.expressassistant.data.Store.addPddAccount(context, "pdd_user_id=222123456; PDDAccessToken=three", "profile-c")
            assertEquals(2, com.halo.expressassistant.data.Store.accounts(context, channel).size)
        } finally {
            base.deleteSharedPreferences(prefsName)
        }
    }

    @Test fun clearingOneProfilePreservesOtherProfileAndDefaultCookies() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val suffix = java.util.UUID.randomUUID().toString()
        val names = listOf(PddWeb.PROFILE_PREFIX + "test-a-" + suffix, PddWeb.PROFILE_PREFIX + "test-b-" + suffix)
        val views = ArrayList<WebView>()
        val managers = ArrayList<CookieManager>()
        val url = "https://example.test/"
        try {
            instrumentation.runOnMainSync {
                assumeTrue(PddWeb.isolated)
                names.forEach { name ->
                    PddWeb.create(instrumentation.targetContext, name).also {
                        views.add(it)
                        managers.add(PddWeb.cookieManager(it))
                    }
                }
            }
            val set = CountDownLatch(3)
            instrumentation.runOnMainSync {
                CookieManager.getInstance().setCookie(url, "ea_default=$suffix; Path=/") { set.countDown() }
                managers[0].setCookie(url, "ea_profile=a; Path=/") { set.countDown() }
                managers[1].setCookie(url, "ea_profile=b; Path=/") { set.countDown() }
            }
            assertTrue(set.await(10, TimeUnit.SECONDS))
            assertEquals("ea_profile=a", managers[0].getCookie(url))
            assertEquals("ea_profile=b", managers[1].getCookie(url))
            val cleared = CountDownLatch(1)
            instrumentation.runOnMainSync { managers[0].removeAllCookies { cleared.countDown() } }
            assertTrue(cleared.await(10, TimeUnit.SECONDS))
            assertTrue(managers[0].getCookie(url).isNullOrBlank())
            assertEquals("ea_profile=b", managers[1].getCookie(url))
            assertTrue(CookieManager.getInstance().getCookie(url).contains("ea_default=$suffix"))
        } finally {
            instrumentation.runOnMainSync {
                views.forEach { it.destroy() }
                names.forEach { PddWeb.clearProfile(it) }
                CookieManager.getInstance().setCookie(url, "ea_default=; Path=/; Max-Age=0")
            }
        }
    }
}
