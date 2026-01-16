package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogBgmConfigBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.service.BgmManager
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding

class BgmConfigDialog : BaseDialogFragment(R.layout.dialog_bgm_config) {

    private val binding by viewBinding(DialogBgmConfigBinding::bind)

    // 文件夹选择器
    private val selectDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            AppConfig.bgmUri = uri.toString()
            binding.tvPath.text = uri.path
            BgmManager.loadBgmFiles()
            BgmManager.play()
            // 选完直接强制显示暂停图标（因为已经开始播了）
            binding.btnPlayPause.setIconResource(R.drawable.ic_pause_filled)
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initView()
        initData()
    }

    private fun initData() = binding.run {
        switchBgm.isChecked = AppConfig.isBgmEnabled
        tvPath.text = AppConfig.bgmUri.ifEmpty { "未选择文件夹" }
        seekVolume.value = AppConfig.bgmVolume.toFloat()
        tvVolumeValue.text = "${AppConfig.bgmVolume}%"
        updatePlayPauseIcon() // 初始化时刷新图标
    }

    private fun initView() = binding.run {
        switchBgm.setOnCheckedChangeListener { _, isChecked ->
            AppConfig.isBgmEnabled = isChecked
            if (isChecked) {
                BgmManager.play()
                btnPlayPause.setIconResource(R.drawable.ic_pause_filled)
            } else {
                BgmManager.pause()
                btnPlayPause.setIconResource(R.drawable.ic_play_filled)
            }
        }

        btnSelectFolder.setOnClickListener {
            selectDir.launch {
                mode = HandleFileContract.DIR
            }
        }

        seekVolume.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val progress = value.toInt()
                BgmManager.setVolume(progress)
                tvVolumeValue.text = "$progress%"
            }
        }

        btnPrev.setOnClickListener { 
            BgmManager.prev()
            // 切换歌曲时，强制设为暂停图标（表示正在播）
            btnPlayPause.setIconResource(R.drawable.ic_pause_filled)
        }
        
        btnNext.setOnClickListener { 
            BgmManager.next()
            btnPlayPause.setIconResource(R.drawable.ic_pause_filled)
        }

        btnPlayPause.setOnClickListener {
            if (BgmManager.isPlaying()) {
                // 如果当前在播，点击后是想暂停
                BgmManager.pause()
                // 【核心修复】不等待状态检测，直接手动切成“播放”图标
                it.post { btnPlayPause.setIconResource(R.drawable.ic_play_filled) }
            } else {
                // 如果当前没播，点击后是想播放
                BgmManager.play()
                // 【核心修复】直接手动切成“暂停”图标
                it.post { btnPlayPause.setIconResource(R.drawable.ic_pause_filled) }
            }
        }
    }

    /**
     * 仅用于初始化或外部状态同步
     */
    private fun updatePlayPauseIcon() {
        if (BgmManager.isPlaying()) {
            binding.btnPlayPause.setIconResource(R.drawable.ic_pause_filled)
        } else {
            binding.btnPlayPause.setIconResource(R.drawable.ic_play_filled)
        }
    }
}
