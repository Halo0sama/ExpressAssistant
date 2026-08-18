package com.halo.expressassistant.ui

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * GitHub 版本检查：比对当前版本与公开仓库的最新 Release，
 * 有新版本时提供 apk 下载地址（无 apk 资产则回退到 release 页面）。
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val REPO = "Halo0sama/ExpressAssistant"
    private const val LATEST_API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val RELEASE_PAGE = "https://github.com/$REPO/releases/latest"

    private val client = OkHttpClient.Builder().build()

    data class Result(
        val latestTag: String?,
        val downloadUrl: String?,
        val error: String?,
        val hasUpdate: Boolean
    )

    suspend fun check(context: Context): Result = withContext(Dispatchers.IO) {
        val current = versionName(context)
        try {
            val request = Request.Builder()
                .url(LATEST_API)
                .header("User-Agent", "ExpressAssistant/$current (Android)")
                .header("Accept", "application/vnd.github+json")
                .build()
            val body = client.newCall(request).execute().use { it.body?.string().orEmpty() }
            if (body.isBlank()) return@withContext Result(null, null, "GitHub 返回空响应", false)
            val root = JSONObject(body)
            val tag = root.optString("tag_name").removePrefix("v").removePrefix("V").trim()
            if (tag.isBlank()) return@withContext Result(null, null, "最新版本解析失败", false)
            // 找 apk 资产
            var apkUrl = ""
            val assets = root.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) {
                        apkUrl = a.optString("browser_download_url")
                        break
                    }
                }
            }
            val hasUpdate = compareVersions(tag, current) > 0
            Log.i(TAG, "current=$current latest=$tag hasUpdate=$hasUpdate apk=${apkUrl.isNotBlank()}")
            Result(
                latestTag = tag,
                downloadUrl = if (apkUrl.isNotBlank()) apkUrl else RELEASE_PAGE,
                error = null,
                hasUpdate = hasUpdate
            )
        } catch (e: Throwable) {
            Log.w(TAG, "check fail: $e")
            Result(null, null, e.message ?: "网络错误", false)
        }
    }

    fun versionName(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
        } catch (e: Throwable) {
            "0"
        }
    }

    /** 数字段版本比较：a > b 返回正数 */
    fun compareVersions(a: String, b: String): Int {
        val pa = Regex("\\d+").findAll(a).map { it.value.toInt() }.toList()
        val pb = Regex("\\d+").findAll(b).map { it.value.toInt() }.toList()
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va - vb
        }
        return 0
    }
}
