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
            updatePlayPauseIcon() // 选完文件夹刷新图标
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
            if (isChecked) BgmManager.play() else BgmManager.pause()
            updatePlayPauseIcon() // 开关切换刷新图标
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
            updatePlayPauseIcon()
        }
        
        btnNext.setOnClickListener { 
            BgmManager.next()
            updatePlayPauseIcon()
        }

        btnPlayPause.setOnClickListener {
            if (BgmManager.isPlaying()) {
                BgmManager.pause()
            } else {
                BgmManager.play()
            }
            updatePlayPauseIcon() // 点击后立即刷新图标
        }
    }

    /**
     * 根据 BgmManager 的实时状态切换 播放/暂停 图标
     */
    private fun updatePlayPauseIcon() {
        if (BgmManager.isPlaying()) {
            // 如果在播放，显示暂停图标
            binding.btnPlayPause.setIconResource(R.drawable.ic_pause_filled)
        } else {
            // 如果没播放，显示播放图标
            binding.btnPlayPause.setIconResource(R.drawable.ic_play_filled)
        }
    }
}
