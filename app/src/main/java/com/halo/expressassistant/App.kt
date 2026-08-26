package com.halo.expressassistant

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.halo.expressassistant.data.Store
import java.util.concurrent.TimeUnit

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        // 15 分钟为 WorkManager 周期下限；Worker 内部按设置（默认关闭 / 用户自定分钟）决定是否执行
        val request = PeriodicWorkRequestBuilder<TrackingWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("express_tracking", ExistingPeriodicWorkPolicy.UPDATE, request)
        if (Store.localApiEnabled(this)) {
            ApiServer.start(this)
        }
        // 记录当前前台 Activity：小组件刷新等后台入口需要 Activity 上下文跑四源同步
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                topActivity = activity
            }

            override fun onActivityPaused(activity: Activity) {
                if (topActivity === activity) topActivity = null
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

            /** 衬线主题下补字体：系统 serif 覆盖不了中文的 ROM 才会真的动视图，常见机型是 no-op */
            override fun onActivityStarted(activity: Activity) {
                com.halo.expressassistant.ui.SerifFont.applyIfNeeded(activity)
            }

            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    companion object {
        lateinit var instance: App
            private set

        /** 当前在前台的 Activity；无前台 Activity 时为 null */
        @Volatile
        var topActivity: Activity? = null
            private set
    }
}
