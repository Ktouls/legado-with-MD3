package io.legado.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.script.ScriptException
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.HttpTTS
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.exoplayer.InputStreamDataSource
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Response
import org.mozilla.javascript.WrappedException
import splitties.init.appCtx
import java.io.File
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlin.coroutines.coroutineContext

/**
 * 在线朗读服务 (MD3 专用 - 逻辑归一化构建版)
 * 1. 统一 getFileNameHelper 作为唯一文件名生成标准
 * 2. 预缓存与播放逻辑强制共享相同的文本净化流程
 */
@SuppressLint("UnsafeOptInUsageError")
class HttpReadAloudService : BaseReadAloudService(),
    Player.Listener {
    private val exoPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(this).build()
    }

    private val ttsFolderPath: String by lazy {
        val baseDir = externalCacheDir ?: cacheDir
        baseDir.absolutePath + File.separator + "httpTTS" + File.separator
    }

    private val cache by lazy {
        val baseDir = externalCacheDir ?: cacheDir
        SimpleCache(
            File(baseDir, "httpTTS_cache"),
            LeastRecentlyUsedCacheEvictor(128 * 1024 * 1024),
            StandaloneDatabaseProvider(appCtx)
        )
    }
    private val cacheDataSinkFactory by lazy {
        CacheDataSink.Factory().setCache(cache)
    }
    private val loadErrorHandlingPolicy by lazy {
        CustomLoadErrorHandlingPolicy()
    }
    private var speechRate: Int = AppConfig.speechRatePlay + 5
    private var downloadTask: Coroutine<*>? = null
    private var playIndexJob: Job? = null
    private var downloadErrorNo: Int = 0
    private var playErrorNo = 0
    private val downloadTaskActiveLock = Mutex()

    override fun onCreate() {
        super.onCreate()
        exoPlayer.addListener(this)
        BgmManager.init(this)
        if (AppConfig.isBgmEnabled) BgmManager.loadBgmFiles()
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadTask?.cancel()
        exoPlayer.release()
        cache.release()
        BgmManager.release()
        Coroutine.async { removeCacheFile() }
    }

    override fun play() {
        pageChanged = false
        exoPlayer.stop()
        if (!requestFocus()) return
        if (contentList.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            ReadBook.readAloud()
        } else {
            super.play()
            if (AppConfig.isBgmEnabled && !BgmManager.isPlaying()) BgmManager.play()
            
            if (AppConfig.streamReadAloudAudio) {
                downloadAndPlayAudiosStream()
            } else {
                downloadAndPlayAudios()
            }
        }
    }

    override fun playStop() {
        exoPlayer.stop()
        playIndexJob?.cancel()
        BgmManager.pause()
    }

    private fun updateNextPos() {
        readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
        paragraphStartPos = 0
        if (nowSpeak < contentList.lastIndex) {
            nowSpeak++
        } else {
            nextChapter()
        }
    }

    // --- 标准下载逻辑 (文件模式) ---
    private fun downloadAndPlayAudios() {
        exoPlayer.clearMediaItems()
        downloadTask?.cancel()
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                val httpTts = ReadAloud.httpTTS ?: throw NoStackTraceException("tts is null")
                
                contentList.forEachIndexed { index, contentText ->
                    ensureActive()
                    if (index < nowSpeak) return@forEachIndexed
                    var text = contentText
                    if (paragraphStartPos > 0 && index == nowSpeak) {
                        text = text.substring(paragraphStartPos)
                    }
                    
                    val fileName = getFileNameHelper(textChapter?.chapter?.title, text)
                    val speakText = text.replace(AppPattern.notReadAloudRegex, "")
                    
                    if (speakText.isEmpty()) {
                        createSilentSound(fileName)
                    } else if (!hasSpeakFile(fileName)) {
                        runCatching {
                            val inputStream = getSpeakStream(httpTts, speakText)
                            if (inputStream != null) createSpeakFile(fileName, inputStream)
                            else createSilentSound(fileName)
                        }.onFailure { e ->
                            if (e !is CancellationException) pauseReadAloud()
                            return@execute
                        }
                    }
                    val file = getSpeakFileAsMd5(fileName)
                    val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
                    launch(Main) { exoPlayer.addMediaItem(mediaItem) }
                }
                preDownloadAudios(httpTts)
            }
        }.onError { e ->
            AppLog.put("朗读下载出错\n${e.localizedMessage}", e, true)
        }
    }

    private suspend fun preDownloadAudios(httpTts: HttpTTS) {
        val book = ReadBook.book ?: return
        val currentIdx = ReadBook.durChapterIndex
        val limit = AppConfig.audioPreDownloadNum 
        
        for (i in 1..limit) {
            try {
                currentCoroutineContext().ensureActive()
                val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, currentIdx + i) ?: break
                val contentString = getPurifiedChapterContent(book, chapter) ?: ""
                
                val segments = mutableListOf<String>()
                if (AppConfig.readAloudTitle) segments.add(chapter.title)
                if (contentString.isNotEmpty()) {
                    segments.addAll(contentString.split("\n").filter { it.isNotBlank() })
                }

                segments.forEach { segmentText ->
                    currentCoroutineContext().ensureActive()
                    val fileName = getFileNameHelper(chapter.title, segmentText)
                    val speakText = segmentText.replace(AppPattern.notReadAloudRegex, "")
                    
                    if (speakText.isEmpty()) {
                        createSilentSound(fileName)
                    } else if (!hasSpeakFile(fileName)) {
                        runCatching {
                            val inputStream = getSpeakStream(httpTts, speakText)
                            if (inputStream != null) createSpeakFile(fileName, inputStream)
                            else createSilentSound(fileName)
                        }
                    }
                }
            } catch (e: Exception) {
                AppLog.put("预载异常: ${e.localizedMessage}")
            }
        }
    }

    // --- 流式下载逻辑 ---
    private fun downloadAndPlayAudiosStream() {
        exoPlayer.clearMediaItems()
        downloadTask?.cancel()
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                val httpTts = ReadAloud.httpTTS ?: throw NoStackTraceException("tts is null")
                val downloaderChannel = Channel<Downloader>(Channel.UNLIMITED)
                launch {
                    for (downloader in downloaderChannel) {
                        kotlin.runCatching { downloader.download(null) }
                    }
                }
                contentList.forEachIndexed { index, contentText ->
                    ensureActive()
                    if (index < nowSpeak) return@forEachIndexed
                    var text = contentText
                    if (paragraphStartPos > 0 && index == nowSpeak) {
                        text = text.substring(paragraphStartPos)
                    }
                    
                    val fileName = getFileNameHelper(textChapter?.chapter?.title, text)
                    val speakText = text.replace(AppPattern.notReadAloudRegex, "")
                    
                    val dataSourceFactory = createDataSourceFactory(httpTts, speakText)
                    val downloader = createDownloader(dataSourceFactory, fileName)
                    downloaderChannel.send(downloader)
                    val mediaSource = createMediaSource(dataSourceFactory, fileName)
                    launch(Main) { exoPlayer.addMediaSource(mediaSource) }
                }
                preDownloadAudiosStream(httpTts, downloaderChannel)
            }
        }.onError { e ->
            AppLog.put("朗读流出错: ${e.localizedMessage}", e, true)
        }
    }

    private suspend fun preDownloadAudiosStream(httpTts: HttpTTS, downloaderChannel: Channel<Downloader>) {
        val book = ReadBook.book ?: return
        val currentIdx = ReadBook.durChapterIndex
        
        for (i in 1..AppConfig.audioPreDownloadNum) {
            try {
                currentCoroutineContext().ensureActive()
                val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, currentIdx + i) ?: break
                val contentString = getPurifiedChapterContent(book, chapter) ?: ""
                
                val segments = mutableListOf<String>()
                if (AppConfig.readAloudTitle) segments.add(chapter.title)
                if (contentString.isNotEmpty()) {
                    segments.addAll(contentString.split("\n").filter { it.isNotBlank() })
                }
                
                segments.forEach { segmentText ->
                    currentCoroutineContext().ensureActive()
                    val fileName = getFileNameHelper(chapter.title, segmentText)
                    val speakText = segmentText.replace(AppPattern.notReadAloudRegex, "")
                    val dataSourceFactory = createDataSourceFactory(httpTts, speakText)
                    val downloader = createDownloader(dataSourceFactory, fileName)
                    downloaderChannel.send(downloader)
                }
            } catch (e: Exception) {
                AppLog.put("流式预载异常: ${e.localizedMessage}")
            }
        }
    }

    // --- 归一化工具方法 ---

    private fun getFileNameHelper(title: String?, content: String): String {
        // 关键：强制执行相同的净化标准
        val t = (title ?: "").trim()
        val c = content.trim()
        val ttsUrl = ReadAloud.httpTTS?.url ?: "default"
        return MD5Utils.md5Encode16(t) + "_" +
                MD5Utils.md5Encode16("$ttsUrl-|$speechRate-|$c")
    }

    private fun getPurifiedChapterContent(book: Book, chapter: BookChapter): String? {
        var content = BookHelp.getContent(book, chapter) ?: return null
        if (AppConfig.replaceEnableDefault) {
            runCatching {
                appDb.replaceRuleDao.allEnabled.forEach { rule ->
                    rule.pattern?.let { p ->
                        if (p.isNotEmpty()) content = content.replace(p.toRegex(), rule.replacement)
                    }
                }
            }
        }
        return content
    }

    private suspend fun getSpeakStream(httpTts: HttpTTS, speakText: String): InputStream? {
        while (true) {
            try {
                val analyzeUrl = AnalyzeUrl(
                    httpTts.url, speakText = speakText, speakSpeed = speechRate,
                    source = httpTts, readTimeout = 300 * 1000L,
                    coroutineContext = currentCoroutineContext()
                )
                var response = analyzeUrl.getResponseAwait()
                currentCoroutineContext().ensureActive()
                
                httpTts.loginCheckJs?.let { if (it.isNotBlank()) response = analyzeUrl.evalJS(it, response) as Response }

                val contentType = response.headers["Content-Type"]?.substringBefore(";")
                if (contentType == "application/json" || contentType?.startsWith("text/") == true) {
                    throw NoStackTraceException(response.body.string())
                }
                
                currentCoroutineContext().ensureActive()
                return response.body.byteStream().also { downloadErrorNo = 0 }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                downloadErrorNo++
                if (downloadErrorNo > 5 || (e !is SocketTimeoutException && e !is ConnectException)) break 
            }
        }
        return null
    }

    private fun createDataSourceFactory(httpTts: HttpTTS, speakText: String): CacheDataSource.Factory {
        val upstreamFactory = DataSource.Factory {
            InputStreamDataSource {
                if (speakText.isEmpty()) null
                else runBlocking(lifecycleScope.coroutineContext[Job]!!) { getSpeakStream(httpTts, speakText) }
            } ?: resources.openRawResource(R.raw.silent_sound)
        }
        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheWriteDataSinkFactory(cacheDataSinkFactory)
    }

    private fun createDownloader(factory: CacheDataSource.Factory, fileName: String): Downloader {
        return DefaultDownloaderFactory(factory, okHttpClient.dispatcher.executorService)
            .createDownloader(DownloadRequest.Builder(fileName, fileName.toUri()).build())
    }

    private fun createMediaSource(factory: DataSource.Factory, fileName: String): MediaSource {
        val mediaItem = MediaItem.Builder().setUri(fileName).setMediaId(fileName).build()
        return DefaultMediaSourceFactory(this)
            .setDataSourceFactory(factory)
            .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            .createMediaSource(mediaItem)
    }

    private fun createSilentSound(fileName: String) {
        createSpeakFile(fileName).writeBytes(resources.openRawResource(R.raw.silent_sound).readBytes())
    }

    private fun hasSpeakFile(name: String) = FileUtils.exist("${ttsFolderPath}$name.mp3")
    private fun getSpeakFileAsMd5(name: String) = File("${ttsFolderPath}$name.mp3")
    private fun createSpeakFile(name: String) = FileUtils.createFileIfNotExist("${ttsFolderPath}$name.mp3")
    private fun createSpeakFile(name: String, inputStream: InputStream) {
        FileUtils.createFileIfNotExist("${ttsFolderPath}$name.mp3").outputStream().use { inputStream.copyTo(it) }
    }

    private fun removeCacheFile() {
        val keepTime = AppConfig.audioCacheCleanTime
        if (keepTime == 0L) {
            FileUtils.deleteFile(ttsFolderPath)
            return
        }
        val book = ReadBook.book ?: return
        val currentIdx = ReadBook.durChapterIndex
        val protectedPrefixes = mutableSetOf<String>()
        
        protectedPrefixes.add(MD5Utils.md5Encode16((textChapter?.chapter?.title ?: "").trim()))
        runBlocking {
            for (i in 1..AppConfig.audioPreDownloadNum) {
                appDb.bookChapterDao.getChapter(book.bookUrl, currentIdx + i)?.let {
                    protectedPrefixes.add(MD5Utils.md5Encode16(it.title.trim()))
                }
            }
        }

        FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach { fileItem ->
            val isProtected = protectedPrefixes.any { fileItem.name.startsWith(it) }
            val isExpired = System.currentTimeMillis() - fileItem.lastModified() > keepTime
            if ((!isProtected && isExpired) || fileItem.length() == 2160L) FileUtils.delete(fileItem.absolutePath)
        }
    }

    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        runCatching {
            playIndexJob?.cancel()
            exoPlayer.pause()
            BgmManager.pause()
        }
    }

    override fun resumeReadAloud() {
        super.resumeReadAloud()
        runCatching {
            if (pageChanged) play()
            else {
                exoPlayer.play()
                if (AppConfig.isBgmEnabled && !BgmManager.isPlaying()) BgmManager.play()
                upPlayPos()
            }
        }
    }

    private fun upPlayPos() {
        playIndexJob?.cancel()
        val textChapter = textChapter ?: return
        playIndexJob = lifecycleScope.launch {
            if (exoPlayer.duration <= 0) return@launch
            val speakText = contentList.getOrNull(nowSpeak) ?: return@launch
            val sleep = exoPlayer.duration / speakText.length.coerceAtLeast(1)
            val start = (speakText.length * exoPlayer.currentPosition / exoPlayer.duration).toInt()
            
            for (i in start..speakText.length) {
                if (pageIndex + 1 < textChapter.pageSize && 
                    readAloudNumber + i > textChapter.getReadLength(pageIndex + 1)) {
                    pageIndex++
                    ReadBook.moveToNextPage()
                }
                upTtsProgress(readAloudNumber + i)
                delay(sleep)
            }
        }
    }

    override fun upSpeechRate(reset: Boolean) {
        downloadTask?.cancel()
        exoPlayer.stop()
        speechRate = AppConfig.speechRatePlay + 5
        if (AppConfig.streamReadAloudAudio) downloadAndPlayAudiosStream() else downloadAndPlayAudios()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        super.onPlaybackStateChanged(playbackState)
        if (playbackState == Player.STATE_READY && !pause) {
            exoPlayer.play()
            upPlayPos()
        } else if (playbackState == Player.STATE_ENDED) {
            playErrorNo = 0
            updateNextPos()
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED && !timeline.isEmpty && exoPlayer.playbackState == Player.STATE_IDLE) {
            exoPlayer.prepare()
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            playErrorNo = 0
            updateNextPos()
            upPlayPos()
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)
        exoPlayer.currentMediaItem?.localConfiguration?.uri?.path?.let { File(it).delete() }
        if (++playErrorNo >= 5) pauseReadAloud()
        else if (exoPlayer.hasNextMediaItem()) {
            exoPlayer.seekToNextMediaItem()
            exoPlayer.prepare()
        } else {
            exoPlayer.clearMediaItems()
            updateNextPos()
        }
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<HttpReadAloudService>(actionStr)
    }

    class CustomLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy(0) {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo) = C.TIME_UNSET
    }
}
