package io.legado.app.service

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.FileUtils
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import java.io.File
import java.util.Collections

object BgmManager {

    private var exoPlayer: ExoPlayer? = null
    private val audioExtensions = arrayOf("mp3", "wav", "ogg", "flac", "m4a", "aac")
    private var playlist: MutableList<MediaItem> = mutableListOf()

    fun init(context: Context) {
        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(context).build().apply {
                repeatMode = Player.REPEAT_MODE_ALL // 列表循环
                shuffleModeEnabled = false // 默认不随机，可按需改
                volume = AppConfig.bgmVolume / 100f
            }
        }
    }

    fun loadBgmFiles() {
        val uriStr = AppConfig.bgmUri
        if (uriStr.isBlank()) return
        
        playlist.clear()
        
        try {
            // 支持普通文件路径和 Uri
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
                // 打乱顺序，避免每次都从第一首开始
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
        }
    }

    fun pause() {
        if (exoPlayer?.isPlaying == true) {
            exoPlayer?.pause()
        }
    }

    fun next() {
        if (exoPlayer?.hasNextMediaItem() == true) {
            exoPlayer?.seekToNextMediaItem()
        } else {
            // 如果只有一首或者是最后一首，重新开始
            exoPlayer?.seekToDefaultPosition(0)
        }
    }

    fun prev() {
        if (exoPlayer?.hasPreviousMediaItem() == true) {
            exoPlayer?.seekToPreviousMediaItem()
        }
    }

    fun setVolume(progress: Int) {
        AppConfig.bgmVolume = progress
        exoPlayer?.volume = progress / 100f
    }

    fun release() {
        exoPlayer?.release()
        exoPlayer = null
        playlist.clear()
    }

    fun isPlaying(): Boolean {
        return exoPlayer?.isPlaying == true
    }
}

