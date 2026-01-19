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
        const val PREF_KEY = PreferKey.webService
        var isRun = false
        var hostAddress = ""
        var port = 1122 // 记录当前实际运行的端口

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
    private val serverLock = Any()

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
            
            // 获取用户设置的起始端口
            var currentPort = getPrefInt(PreferKey.webPort, 1122)
            if (currentPort > 65530 || currentPort < 1024) {
                currentPort = 1122
            }

            if (addressList.isEmpty()) {
                // 网络未就绪，暂不停止服务，等待下次回调，防止UI闪烁
                return
            }

            // ——————【状态检查】——————
            // 如果服务活着，且IP没变，且端口也没变，直接跳过
            if (httpServer?.isAlive == true && webSocketServer?.isAlive == true) {
                val currentFirstIp = addressList.firstOrNull()?.hostAddress
                if (currentFirstIp != null && 
                    hostAddress.contains(currentFirstIp) && 
                    port == currentPort) { // 检查端口是否一致
                    return
                }
            }

            stopServers()

            // ——————【端口自动重试机制】——————
            // 尝试绑定端口，如果占用则+1，最多尝试10次
            var isSuccess = false
            for (i in 0 until 10) {
                val tryPort = currentPort + i
                try {
                    httpServer = HttpServer(tryPort)
                    httpServer?.start()
                    
                    // HTTP启动成功后，尝试启动WebSocket
                    webSocketServer = WebSocketServer(tryPort + 1)
                    webSocketServer?.start(30000)
                    
                    // 全部成功，更新当前实际端口
                    port = tryPort
                    isSuccess = true
                    break 
                } catch (e: IOException) {
                    // 绑定失败，清理并尝试下一个端口
                    stopServers()
                    e.printOnDebug()
                }
            }

            if (isSuccess) {
                notificationList.clear()
                addressList.forEach { address ->
                    notificationList.add(getString(R.string.http_ip, address.hostAddress, port))
                }
                hostAddress = notificationList.firstOrNull() ?: ""
                isRun = true
                postEvent(EventBus.WEB_SERVICE, hostAddress)
                FlowEventBus.post(EventBus.WEB_SERVICE, hostAddress)
                startForegroundNotification()
            } else {
                // 重试10次都失败，才彻底放弃
                isRun = false
                toastOnUi("Web Service Start Failed: Ports $currentPort-${currentPort+10} are busy.")
                stopSelf()
            }
        }
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
