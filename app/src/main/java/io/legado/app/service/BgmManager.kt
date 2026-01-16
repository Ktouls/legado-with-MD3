package io.legado.app.service

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.*
import splitties.init.appCtx
import java.io.File
import java.util.Collections

object BgmManager {

    private var exoPlayer: ExoPlayer? = null
    private val audioExtensions = arrayOf("mp3", "wav", "ogg", "flac", "m4a", "aac")
    private var playlist: MutableList<MediaItem> = mutableListOf()
    
    // 用于控制音量动画的协程任务
    private var fadeJob: Job? = null
    private val mainScope = CoroutineScope(Dispatchers.Main)

    fun init(context: Context) {
        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(context).build().apply {
                repeatMode = Player.REPEAT_MODE_ALL
                shuffleModeEnabled = false
                // 初始音量设为 0，等待淡入
                volume = 0f
            }
        }
    }

    /**
     * 音量平滑过渡动画
     * @param targetVolume 目标音量 (0.0 - 1.0)
     * @param duration 持续时间 (毫秒)
     * @param onComplete 动画完成后的回调
     */
    private fun animateVolume(targetVolume: Float, duration: Long = 500L, onComplete: (() -> Unit)? = null) {
        fadeJob?.cancel() // 取消之前的动画任务
        val startVolume = exoPlayer?.volume ?: 0f
        
        fadeJob = mainScope.launch {
            val steps = 20 // 动画步数
            val interval = duration / steps // 每步间隔
            val delta = (targetVolume - startVolume) / steps // 每步增加/减少的量
            
            for (i in 1..steps) {
                delay(interval)
                exoPlayer?.volume = startVolume + delta * i
            }
            exoPlayer?.volume = targetVolume
            onComplete?.invoke()
        }
    }

    fun loadBgmFiles() {
        val uriStr = AppConfig.bgmUri
        if (uriStr.isBlank()) return
        playlist.clear()
        try {
            if (uriStr.startsWith("content://")) {
                val docFile = DocumentFile.fromTreeUri(appCtx, Uri.parse(uriStr))
                docFile?.listFiles()?.forEach { file ->
                    if (file.isFile && isAudioFile(file.name)) {
                        playlist.add(MediaItem.fromUri(file.uri))
                    }
                }
            } else {
                val dir = File(uriStr)
                if (dir.exists() && dir.isDirectory) {
                    dir.listFiles()?.forEach { file ->
                        if (file.isFile && isAudioFile(file.name)) {
                            playlist.add(MediaItem.fromUri(Uri.fromFile(file)))
                        }
                    }
                }
            }

            if (playlist.isNotEmpty()) {
                Collections.shuffle(playlist)
                exoPlayer?.setMediaItems(playlist)
                exoPlayer?.prepare()
            } else {
                appCtx.toastOnUi("所选文件夹内没有找到音频文件")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun isAudioFile(name: String?): Boolean {
        return name != null && audioExtensions.any { name.endsWith(".$it", true) }
    }

    fun play() {
        if (!AppConfig.isBgmEnabled) return
        if (playlist.isEmpty()) {
            loadBgmFiles()
        }
        if (playlist.isNotEmpty() && exoPlayer?.isPlaying == false) {
            exoPlayer?.play()
            // 执行淡入：从当前音量到设置的音量
            animateVolume(AppConfig.bgmVolume / 100f)
        }
    }

    fun pause() {
        if (exoPlayer?.isPlaying == true) {
            // 执行淡出：淡出到 0 后再执行暂停
            animateVolume(0f) {
                exoPlayer?.pause()
            }
        }
    }

    fun next() {
        // 切歌时先淡出 -> 切换 -> 再淡入
        animateVolume(0f， 300L) {
            if (exoPlayer?.hasNextMediaItem() == true) {
                exoPlayer?.seekToNextMediaItem()
            } else {
                exoPlayer?.seekToDefaultPosition(0)
            }
            animateVolume(AppConfig.bgmVolume / 100f, 500L)
        }
    }

    fun prev() {
        animateVolume(0f, 300L) {
            if (exoPlayer?.hasPreviousMediaItem() == true) {
                exoPlayer?.seekToPreviousMediaItem()
            }
            animateVolume(AppConfig.bgmVolume / 100f, 500L)
        }
    }

    fun setVolume(progress: Int) {
        AppConfig.bgmVolume = progress
        // 拖动进度条时直接设置，不使用动画，保证响应速度
        fadeJob?.cancel() 
        exoPlayer?.volume = progress / 100f
    }

    fun release() {
        fadeJob?.cancel()
        exoPlayer?.release()
        exoPlayer = null
        playlist.clear()
    }

    fun isPlaying(): Boolean {
        return exoPlayer?.isPlaying == true
    }
}
