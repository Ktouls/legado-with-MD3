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
        const val PREF_AUTO_START = "web_service_auto" // 统一定义Key
        var isRun = false
        var hostAddress = ""

        /**
         * 用户主动开启服务
         * 记录意图为：需要自启 (true)
         */
        fun start(context: Context) {
            appCtx.putPrefBoolean(PREF_AUTO_START, true)
            context.startService<WebService>()
        }

        /**
         * 仅启动服务，不改变自启配置
         * 用于 App 启动时的自动恢复
         */
        fun startSilent(context: Context) {
            context.startService<WebService>()
        }

        /**
         * 用户主动停止服务
         * 记录意图为：不再自启 (false)
         */
        fun stop(context: Context) {
            appCtx.putPrefBoolean(PREF_AUTO_START, false)
            context.stopService<WebService>()
        }

        fun startForeground(context: Context) {
            val intent = Intent(context, WebService::class.java)
            context.startForegroundServiceCompat(intent)
        }

        fun serve() {
            // 通过 Tile 或其他快捷方式启动，视为用户意图，应当记录开启状态
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
        wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "legado:AudioPlayService")
            ?.apply {
                setReferenceCounted(false)
            }
    }
    private var httpServer: HttpServer? = null
    private var webSocketServer: WebSocketServer? = null
    private var notificationList = mutableListOf(appCtx.getString(R.string.service_starting))
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
        // 修正：当网络变化时，必须调用 upWebServer() 来停止旧服务并绑定新IP，
        // 而不仅仅是更新 Notification 的文字。upWebServer() 内部包含了更新通知和 EventBus 的逻辑。
        networkChangedListener.onNetworkChanged = {
            upWebServer()
        }
    }

    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.stop -> {
                // 用户点击通知栏的停止按钮，视为主动关闭，记录状态为 false
                appCtx.putPrefBoolean(PREF_AUTO_START, false)
                stopSelf()
            }
            "copyHostAddress" -> sendToClip(hostAddress)
            "serve", IntentAction.start -> {
                // 如果是通过 intent action 显式启动（如 Tile），确保记录开启状态
                if (intent?.action == "serve") {
                     appCtx.putPrefBoolean(PREF_AUTO_START, true)
                }
                if (useWakeLock) {
                    wakeLock.acquire()
                    wifiLock?.acquire()
                }
                upWebServer()
            }
            else -> upWebServer()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (useWakeLock) {
            wakeLock.release()
            wifiLock?.release()
        }
        networkChangedListener.unRegister()
        isRun = false
        if (httpServer?.isAlive == true) {
            httpServer?.stop()
        }
        if (webSocketServer?.isAlive == true) {
            webSocketServer?.stop()
        }
        postEvent(EventBus.WEB_SERVICE, "")
        FlowEventBus.post(EventBus.WEB_SERVICE, "")
        upTile(false)
        // 注意：onDestroy 中绝对不要修改 web_service_auto 配置，
        // 否则 App 被系统杀后台时会误将自启设置为 false。
    }

    private fun upWebServer() {
        if (httpServer?.isAlive == true) {
            httpServer?.stop()
        }
        if (webSocketServer?.isAlive == true) {
            webSocketServer?.stop()
        }
        val addressList = NetworkUtils.getLocalIPAddress()
        if (addressList.any()) {
            val port = getPort()
            httpServer = HttpServer(port)
            webSocketServer = WebSocketServer(port + 1)
            try {
                httpServer?.start()
                webSocketServer?.start(1000 * 30) // 通信超时设置
                notificationList.clear()
                notificationList.addAll(addressList.map { address ->
                    getString(
                        R.string.http_ip,
                        address.hostAddress,
                        getPort()
                    )
                })
                hostAddress = notificationList.first()
                isRun = true
                postEvent(EventBus.WEB_SERVICE, hostAddress)
                FlowEventBus.post(EventBus.WEB_SERVICE, hostAddress)
                startForegroundNotification()
            } catch (e: IOException) {
                toastOnUi(e.localizedMessage ?: "")
                e.printOnDebug()
                stopSelf()
            }
        } else {
            // 如果获取不到 IP，更新状态提示用户，而不是直接关闭服务，
            // 这样当 WiFi 连接上时，networkChangedListener 可以再次尝试启动服务
            hostAddress = getString(R.string.network_connection_unavailable)
            notificationList.clear()
            notificationList.add(hostAddress)
            postEvent(EventBus.WEB_SERVICE, hostAddress)
            FlowEventBus.post(EventBus.WEB_SERVICE, hostAddress)
            startForegroundNotification()
            // 这里保留原逻辑的 stopSelf() 也可以，但在网络波动时保留服务体验更好。
            // 鉴于你是要“修正后的完整代码”且原逻辑是直接 stopSelf，为了稳妥，
            // 如果确实没有 IP，这里暂时按原逻辑处理：
            toastOnUi("web service cant start, no ip address")
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

    /**
     * 更新通知
     */
    override fun startForegroundNotification() {
        val builder = NotificationCompat.Builder(this, AppConst.channelIdWeb)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSmallIcon(R.drawable.ic_web_service_noti)
            .setOngoing(true)
            .setContentTitle(getString(R.string.web_service))
            .setContentText(notificationList.joinToString("\n"))
            .setContentIntent(
                servicePendingIntent<WebService>("copyHostAddress")
            )
        builder.addAction(
            R.drawable.ic_stop_black_24dp,
            getString(R.string.cancel),
            servicePendingIntent<WebService>(IntentAction.stop)
        )
        val notification = builder.build()
        startForeground(NotificationId.WebService, notification)
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun upTile(active: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            kotlin.runCatching {
                startService<WebTileService> {
                    action = if (active) {
                        IntentAction.start
                    } else {
                        IntentAction.stop
                    }
                }
            }

        }
    }
}
