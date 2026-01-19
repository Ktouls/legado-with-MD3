package io.legado.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.constant.PreferKey
import io.legado.app.receiver.NetworkChangedListener
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.eventBus.FlowEventBus
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.postEvent
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.sendToClip
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.startForegroundServiceCompat
import io.legado.app.utils.startService
import io.legado.app.utils.stopService
import io.legado.app.utils.toastOnUi
import io.legado.app.web.HttpServer
import io.legado.app.web.WebSocketServer
import splitties.init.appCtx
import splitties.systemservices.powerManager
import splitties.systemservices.wifiManager
import java.io.IOException

class WebService : BaseService() {

    companion object {
        // 关键：必须与 UI 界面开关使用的 Key 严格一致
        const val PREF_KEY = PreferKey.webService 
        var isRun = false
        var hostAddress = ""

        fun start(context: Context) {
            appCtx.putPrefBoolean(PREF_KEY, true)
            context.startService<WebService>()
        }

        fun startSilent(context: Context) {
            context.startService<WebService>()
        }

        fun stop(context: Context) {
            appCtx.putPrefBoolean(PREF_KEY, false)
            context.stopService<WebService>()
        }

        fun startForeground(context: Context) {
            appCtx.putPrefBoolean(PREF_KEY, true)
            val intent = Intent(context, WebService::class.java)
            context.startForegroundServiceCompat(intent)
        }

        fun serve() {
            appCtx.putPrefBoolean(PREF_KEY, true)
            appCtx.startService<WebService> {
                action = "serve"
            }
        }
    }

    private val useWakeLock = appCtx.getPrefBoolean(PreferKey.webServiceWakeLock, false)
    private val wakeLock: PowerManager.WakeLock by lazy {
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "legado:WebService")
            .apply { setReferenceCounted(false) }
    }
    private val wifiLock by lazy {
        @Suppress("DEPRECATION")
        wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "legado:WebService")
            ?.apply { setReferenceCounted(false) }
    }

    private var httpServer: HttpServer? = null
    private var webSocketServer: WebSocketServer? = null
    private var notificationList = mutableListOf<String>()
    private val serverLock = Any() // 并发启动锁
    
    private val networkChangedListener by lazy {
        NetworkChangedListener(this)
    }

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        if (useWakeLock) {
            wakeLock.acquire()
            wifiLock?.acquire()
        }
        isRun = true
        upTile(true)
        networkChangedListener.register()
        networkChangedListener.onNetworkChanged = {
            upWebServer()
        }
    }

    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.stop -> {
                appCtx.putPrefBoolean(PREF_KEY, false)
                stopSelf()
            }
            "copyHostAddress" -> sendToClip(hostAddress)
            "serve" -> {
                appCtx.putPrefBoolean(PREF_KEY, true)
                upWebServer()
            }
            else -> {
                // 仅在未运行或服务器失效时才触发 bind
                if (!isRun || httpServer?.isAlive != true) {
                    upWebServer()
                } else {
                    startForegroundNotification()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (useWakeLock) {
            if (wakeLock.isHeld) wakeLock.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
        networkChangedListener.unRegister()
        isRun = false
        stopServers()
        postEvent(EventBus.WEB_SERVICE, "")
        FlowEventBus.post(EventBus.WEB_SERVICE, "")
        upTile(false)
    }

    private fun stopServers() {
        synchronized(serverLock) {
            try {
                if (httpServer?.isAlive == true) httpServer?.stop()
                httpServer = null
                if (webSocketServer?.isAlive == true) webSocketServer?.stop()
                webSocketServer = null
            } catch (e: Exception) {
                e.printOnDebug()
            }
        }
    }

    private fun upWebServer() {
        synchronized(serverLock) {
            val addressList = NetworkUtils.getLocalIPAddress()
            val port = getPort()

            if (addressList.isEmpty()) {
                toastOnUi("Web Service: No IP address found")
                return
            }

            // 幂等性检查，防止 EADDRINUSE
            if (httpServer?.isAlive == true && webSocketServer?.isAlive == true) {
                val currentFirstIp = addressList.firstOrNull()?.hostAddress
                if (currentFirstIp != null && hostAddress.contains(currentFirstIp) && hostAddress.contains(port.toString())) {
                    return
                }
            }

            try {
                if (httpServer?.isAlive == true) httpServer?.stop()
                if (webSocketServer?.isAlive == true) webSocketServer?.stop()
                
                httpServer = HttpServer(port)
                webSocketServer = WebSocketServer(port + 1)

                httpServer?.start()
                webSocketServer?.start(30000)

                notificationList.clear()
                addressList.forEach { address ->
                    notificationList.add(getString(R.string.http_ip, address.hostAddress, port))
                }

                hostAddress = notificationList.firstOrNull() ?: ""
                isRun = true
                postEvent(EventBus.WEB_SERVICE, hostAddress)
                FlowEventBus.post(EventBus.WEB_SERVICE, hostAddress)
                startForegroundNotification()
            } catch (e: IOException) {
                httpServer = null
                webSocketServer = null
                toastOnUi("Start Web Service failed: ${e.localizedMessage}")
                e.printOnDebug()
            }
        }
    }

    private fun getPort(): Int {
        var port = getPrefInt(PreferKey.webPort, 1122)
        if (port > 65530 || port < 1024) {
            port = 1122
        }
        return port
    }

    override fun startForegroundNotification() {
        if (notificationList.isEmpty()) return
        val builder = NotificationCompat.Builder(this, AppConst.channelIdWeb)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSmallIcon(R.drawable.ic_web_service_noti)
            .setOngoing(true)
            .setContentTitle(getString(R.string.web_service))
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationList.joinToString("\n")))
            .setContentIntent(servicePendingIntent<WebService>("copyHostAddress"))
            .addAction(
                R.drawable.ic_stop_black_24dp,
                getString(R.string.cancel),
                servicePendingIntent<WebService>(IntentAction.stop)
            )
        
        startForeground(NotificationId.WebService, builder.build())
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun upTile(active: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            kotlin.runCatching {
                startService<WebTileService> {
                    action = if (active) IntentAction.start else IntentAction.stop
                }
            }
        }
    }
}
