package com.halo.expressassistant

import android.app.Application
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
        val request = PeriodicWorkRequestBuilder<TrackingWorker>(30, TimeUnit.MINUTES)
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
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
