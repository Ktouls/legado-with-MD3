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
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /**
     * 【核心修复点】
     * 按照 BaseDialogFragment 的要求，必须使用 onFragmentCreated 
     * 而不是标准的 onViewCreated
     */
    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initView()
        initData()
    }

    private fun initData() = binding.run {
        switchBgm.isChecked = AppConfig.isBgmEnabled
        tvPath.text = AppConfig.bgmUri.ifEmpty { "未选择文件夹" }
        seekVolume.value = AppConfig.bgmVolume.toFloat()
        tvVolumeValue.text = "${AppConfig.bgmVolume}%"
    }

    private fun initView() = binding.run {
        switchBgm.setOnCheckedChangeListener { _, isChecked ->
            AppConfig.isBgmEnabled = isChecked
            if (isChecked) BgmManager.play() else BgmManager.pause()
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
