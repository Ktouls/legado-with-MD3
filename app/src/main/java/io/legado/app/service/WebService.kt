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
        const val PREF_AUTO_START = "web_service_auto" // 统一定义记忆 Key
        var isRun = false
        var hostAddress = ""

        /**
         * 用户主动开启服务：记录状态并启动
         */
        fun start(context: Context) {
            appCtx.putPrefBoolean(PREF_AUTO_START, true)
            context.startService<WebService>()
        }

        /**
         * 静默/自动启动：不修改配置，仅尝试拉起服务
         */
        fun startSilent(context: Context) {
            context.startService<WebService>()
        }

        /**
         * 用户主动停止服务：记录状态并停止
         */
        fun stop(context: Context) {
            appCtx.putPrefBoolean(PREF_AUTO_START, false)
            context.stopService<WebService>()
        }

        /**
         * 以兼容方式启动前台服务
         */
        fun startForeground(context: Context) {
            // ——————【补全记忆逻辑】——————
            // 确保通过快捷磁贴启动时，状态也能被持久化记录
            appCtx.putPrefBoolean(PREF_AUTO_START, true)
            val intent = Intent(context, WebService::class.java)
            context.startForegroundServiceCompat(intent)
        }

        /**
         * 处理外部指令（如 Tile），视为用户意图
         */
        fun serve() {
            appCtx.putPrefBoolean(PREF_AUTO_START, true)
            appCtx.startService<WebService> {
                action = "serve"
            }
        }
    }

    private val useWakeLock = appCtx.getPrefBoolean(PreferKey.webServiceWakeLock, false)
    private val wakeLock: PowerManager.WakeLock by lazy {
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "legado:WebService")
            .apply {
                setReferenceCounted(false)
            }
    }
    private val wifiLock by lazy {
        @Suppress("DEPRECATION")
        wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "legado:WebService")
            ?.apply {
                setReferenceCounted(false)
            }
    }

    private var httpServer: HttpServer? = null
    private var webSocketServer: WebSocketServer? = null
    private var notificationList = mutableListOf<String>()
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
                appCtx.putPrefBoolean(PREF_AUTO_START, false)
                stopSelf()
            }
            "copyHostAddress" -> sendToClip(hostAddress)
            "serve" -> {
                appCtx.putPrefBoolean(PREF_AUTO_START, true)
                upWebServer()
            }
            else -> upWebServer()
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
        try {
            if (httpServer?.isAlive == true) httpServer?.stop()
            httpServer = null
            if (webSocketServer?.isAlive == true) webSocketServer?.stop()
            webSocketServer = null
        } catch (e: Exception) {
            e.printOnDebug()
        }
    }

    private fun upWebServer() {
        val addressList = NetworkUtils.getLocalIPAddress()
        val port = getPort()

        if (addressList.isEmpty()) {
            toastOnUi("Web Service: No IP address found")
            stopSelf()
            return
        }

        // 幂等性检查：若环境未变且服务存活，不重复重启
        if (httpServer?.isAlive == true && webSocketServer?.isAlive == true) {
            val currentFirstIp = addressList.firstOrNull()?.hostAddress
            if (currentFirstIp != null && hostAddress.contains(currentFirstIp) && hostAddress.contains(port.toString())) {
                return
            }
        }

        stopServers()

        httpServer = HttpServer(port)
        webSocketServer = WebSocketServer(port + 1)

        try {
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
            toastOnUi("Start Web Service failed: ${e.localizedMessage}")
            e.printOnDebug()
            stopSelf()
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
