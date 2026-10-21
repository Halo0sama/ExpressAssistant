package com.halo.expressassistant.ui

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.halo.expressassistant.data.PddSession
import com.halo.expressassistant.data.Store
import org.json.JSONObject

/** Local browser isolation does not override the server's session policy. */
object PddWeb {
    const val PROFILE_PREFIX = "express-pdd-"
    const val ORDERS_URL = "https://mobile.yangkeduo.com/orders.html"
    val isolated: Boolean get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
    private var cleanedProfiles = false

    fun profileFor(context: Context, cookies: String): String {
        val uid = PddSession.userId(cookies)
        val account = Store.accounts(context, Store.CH_PDD).firstOrNull {
            uid.isNotBlank() && PddSession.userId(Store.cookieOf(it.payload)) == uid
        }
        return account?.let { JSONObject(it.payload).optString("webProfile") }
            ?.takeIf { it.startsWith(PROFILE_PREFIX) }
            ?: PROFILE_PREFIX + "legacy-" + java.security.MessageDigest.getInstance("SHA-256")
                .digest(uid.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    }

    fun create(context: Context, profile: String): WebView {
        if (isolated && !cleanedProfiles) {
            cleanedProfiles = true
            val retained = Store.accounts(context, Store.CH_PDD).map {
                profileFor(context, Store.cookieOf(it.payload))
            }.toSet() + profile
            // Loaded profiles cannot be deleted until the next process. Collect only our
            // unbound profiles before loading one; other platforms are never considered.
            ProfileStore.getInstance().allProfileNames.filter {
                it.startsWith(PROFILE_PREFIX) && it !in retained
            }.forEach { runCatching { ProfileStore.getInstance().deleteProfile(it) } }
        }
        return WebView(context).also { web ->
            // Must precede settings access and every other WebView operation.
            if (isolated) WebViewCompat.setProfile(web, profile)
            web.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                // Chromium desktop browser, shared by login, orders and logistics.
                // No canvas, audio or GPU fingerprint mutations.
                userAgentString = WebSettings.getDefaultUserAgent(context)
                    .replace(Regex("\\(Linux; Android[^)]*\\)"), "(X11; Linux x86_64)")
                    .replace("Version/4.0 ", "").replace(" Mobile", "")
            }
            cookieManager(web).apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(web, true)
            }
        }
    }

    fun cookieManager(web: WebView): CookieManager =
        if (isolated) WebViewCompat.getProfile(web).cookieManager else CookieManager.getInstance()

    fun persistSession(context: Context, web: WebView, expectedCookies: String) {
        val cm = cookieManager(web)
        val fresh = cm.getCookie(ORDERS_URL).orEmpty()
        val uid = PddSession.userId(expectedCookies)
        if (!PddSession.hasCredentials(fresh) || PddSession.userId(fresh) != uid) return
        val account = Store.accounts(context, Store.CH_PDD).firstOrNull {
            PddSession.userId(Store.cookieOf(it.payload)) == uid
        } ?: return
        val payload = JSONObject(account.payload).put("cookie", fresh).toString()
        if (payload != account.payload) Store.updateAccount(context, Store.CH_PDD, account.copy(payload = payload))
        cm.flush()
    }

    fun clearProfile(profile: String) {
        if (isolated && profile.startsWith(PROFILE_PREFIX)) {
            try {
                ProfileStore.getInstance().deleteProfile(profile)
            } catch (_: IllegalStateException) {
                // Chromium keeps loaded profiles alive for the process lifetime.
                // Clear just this profile now; remove its directory on the next start.
                ProfileStore.getInstance().getProfile(profile)?.apply {
                    cookieManager.removeAllCookies { cookieManager.flush() }
                    webStorage.deleteAllData()
                }
            }
        }
    }

    fun blockNavigation(url: String): Boolean =
        Uri.parse(url).scheme?.lowercase() !in setOf("https", "http", "about")
}
