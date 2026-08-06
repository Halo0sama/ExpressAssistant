package com.halo.expressassistant.service

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.halo.expressassistant.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

object ShellClient {
    private var binder: IExpressShell? = null
    private var connection: ServiceConnection? = null
    var lastError: String = "unknown"
        private set

    private val args by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(App.instance, ExpressShellService::class.java)
        ).apply {
            tag("express_shell")
            version(20)
            processNameSuffix("express_shell")
        }
    }

    fun isReady(): Boolean = binder != null

    fun current(): IExpressShell? = binder

    fun bind(onResult: (Boolean) -> Unit) {
        if (binder != null) {
            onResult(true)
            return
        }
        if (!Shizuku.pingBinder()) {
            lastError = "Shizuku 未运行"
            onResult(false)
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            lastError = "等待授权"
            Shizuku.requestPermission(1000)
            onResult(false)
            return
        }
        lastError = "bind failed"
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binder = IExpressShell.Stub.asInterface(service)
                onResult(true)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                binder = null
            }
        }
        connection = conn
        try {
            Shizuku.bindUserService(args, conn)
        } catch (t: Throwable) {
            lastError = t.toString()
            onResult(false)
        }
    }

    suspend fun probe(): String = withContext(Dispatchers.IO) {
        binder?.probeAuth() ?: "ERR: not bound"
    }

    suspend fun fetchList(body: String): String = withContext(Dispatchers.IO) {
        binder?.getExpressList(body) ?: "ERR: not bound"
    }

    suspend fun fetchDetail(body: String): String = withContext(Dispatchers.IO) {
        binder?.getExpressDetail(body) ?: "ERR: not bound"
    }
}
