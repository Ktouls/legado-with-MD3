package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
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

    // 文件夹选择器：使用 Legado 原生的文件处理协议
    private val selectDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            AppConfig.bgmUri = uri.toString()
            binding.tvPath.text = uri.path
            BgmManager.loadBgmFiles()
            BgmManager.play()
        }
    }

    override fun onStart() {
        super.onStart()
        // 设置弹窗宽度为屏幕的 90%
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        initData()
    }

    private fun initData() = binding.run {
        switchBgm.isChecked = AppConfig.isBgmEnabled
        tvPath.text = AppConfig.bgmUri.ifEmpty { "未选择文件夹" }
        seekVolume.progress = AppConfig.bgmVolume
        tvVolumeValue.text = "${AppConfig.bgmVolume}%"
    }

    private fun initView() = binding.run {
        // 总开关
        switchBgm.setOnCheckedChangeListener { _, isChecked ->
            AppConfig.isBgmEnabled = isChecked
            if (isChecked) BgmManager.play() else BgmManager.pause()
        }

        // 选择文件夹按钮
        btnSelectFolder.setOnClickListener {
            selectDir.launch {
                mode = HandleFileContract.DIR // 设置为目录选择模式
            }
        }

        // 音量调节滑动条
        seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    BgmManager.setVolume(progress)
                    tvVolumeValue.text = "$progress%"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 基础播放控制
        btnPrev.setOnClickListener { BgmManager.prev() }
        btnNext.setOnClickListener { BgmManager.next() }
        btnPlayPause.setOnClickListener {
            if (BgmManager.isPlaying()) {
                BgmManager.pause()
                btnPlayPause.text = "播放"
            } else {
                BgmManager.play()
                btnPlayPause.text = "暂停"
            }
        }
    }
}
