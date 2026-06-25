package io.github.thibaultbee.streampack.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.thibaultbee.streampack.app.databinding.ActivityMainBinding
import io.github.thibaultbee.streampack.app.utils.PermissionsManager
import io.github.thibaultbee.streampack.app.utils.showDialog
import io.github.thibaultbee.streampack.app.utils.toast
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.backCameras
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.cameraManager
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.cameras
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.defaultCameraId
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.frontCameras
import io.github.thibaultbee.streampack.core.streamers.lifecycle.StreamerViewModelLifeCycleObserver
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(this.application)
    }

    private val prefs by lazy { getSharedPreferences("carstream", MODE_PRIVATE) }

    /** カメラ切替の現在状態（自前トラッキング） */
    private var usingFront = false

    private val streamerRequiredPermissions =
        listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

    /**
     * A minimalist permission manager
     */
    @SuppressLint("MissingPermission")
    private val permissionsManager = PermissionsManager(
        this,
        streamerRequiredPermissions,
        onAllGranted = { onPermissionsGranted() },
        onShowPermissionRationale = { permissions, onRequiredPermissionLastTime ->
            // Explain why we need permissions
            showDialog(
                title = "Permissions denied",
                message = "Explain why you need to grant $permissions permissions to stream",
                positiveButtonText = R.string.accept,
                onPositiveButtonClick = { onRequiredPermissionLastTime() },
                negativeButtonText = R.string.denied
            )
        },
        onDenied = {
            showDialog(
                "Permissions denied",
                "You need to grant all permissions to stream",
                positiveButtonText = 0,
                negativeButtonText = 0
            )
        })

    /**
     * Listen to lifecycle events. So we don't have to stop the streamer manually in `onPause` and release in `onDestroy
     */
    private val streamerLifeCycleObserver by lazy { StreamerViewModelLifeCycleObserver(viewModel.streamer) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 車載: 配信中に画面を寝かせない
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bindProperties()
    }

    private fun bindProperties() {
        // 端末上で編集した配信URLを使う（前回値を復元）
        binding.serverUrl.setText(prefs.getString("url", DEFAULT_URL))
        binding.liveButton.setOnCheckedChangeListener { view, isChecked ->
            if (view.isPressed) {
                if (isChecked) {
                    val url = binding.serverUrl.text.toString().trim()
                    prefs.edit().putString("url", url).apply()
                    binding.statusText.text = "接続中… $url"
                    viewModel.startStream(url)
                } else {
                    viewModel.stopStream()
                    binding.statusText.text = "停止"
                }
            }
        }

        // 設定適用 / カメラ切替
        binding.applyButton.setOnClickListener { applySettings() }
        binding.switchCamButton.setOnClickListener { toggleCamera() }

        // Register the lifecycle observer
        lifecycle.addObserver(streamerLifeCycleObserver)

        // Configure the streamer（prefsの設定で）
        configureStreamer()

        // Bind events
        viewModel.closedThrowableLiveData.observe(this) {
            Log.e(TAG, "Disconnect: $it")
            binding.statusText.text = "✗ 切断: ${it.message}"
        }

        viewModel.pendingConnectionFailedLiveData.observe(this) {
            Log.e(TAG, "Connection error: $it")
            binding.statusText.text = "✗ 接続失敗: ${it.message}"
        }

        viewModel.throwableLiveData.observe(this) {
            Log.e(TAG, "Error: $it")
            binding.statusText.text = "✗ エラー: ${it.message}"
        }

        // 再接続の進捗を表示
        viewModel.reconnectCountLiveData.observe(this) { n ->
            if (n > 0 && viewModel.isStreamingLiveData.value != true) {
                binding.statusText.text = "⟳ 再接続 ${n}回目…"
            }
        }

        viewModel.isStreamingLiveData.observe(this) { isStreaming ->
            if (isStreaming) {
                binding.statusText.text =
                    "● 配信中  ${binding.resInput.text} ${binding.bitrateInput.text}kbps " +
                    "${binding.fpsInput.text}fps (ABR)"
                lockOrientation()
            } else {
                unlockOrientation()
            }
            if (isStreaming) {
                binding.liveButton.isChecked = true
            } else if (viewModel.isTryingConnectionLiveData.value == true) {
                binding.liveButton.isChecked = true
            } else {
                binding.liveButton.isChecked = false
            }
        }

        viewModel.isTryingConnectionLiveData.observe(this) { isWaitingForConnection ->
            if (isWaitingForConnection) {
                binding.liveButton.isChecked = true
            } else if (viewModel.isStreamingLiveData.value == true) {
                binding.liveButton.isChecked = true
            } else {
                binding.liveButton.isChecked = false
            }
        }
    }

    /** prefsの設定値をエンコーダに反映し、入力欄にも復元する */
    private fun configureStreamer() {
        viewModel.setAudioConfig()

        val kbps = prefs.getInt("bitrate", DEFAULT_BITRATE_KBPS)
        val res = prefs.getString("res", DEFAULT_RES) ?: DEFAULT_RES
        val fps = prefs.getInt("fps", DEFAULT_FPS)
        val (w, h) = parseRes(res)

        binding.bitrateInput.setText(kbps.toString())
        binding.resInput.setText("${w}x${h}")
        binding.fpsInput.setText(fps.toString())

        viewModel.setVideoConfig(kbps * 1000, w, h, fps)
    }

    /** 入力欄の設定をエンコーダへ適用＋永続化 */
    private fun applySettings() {
        val kbps = binding.bitrateInput.text.toString().trim().toIntOrNull() ?: DEFAULT_BITRATE_KBPS
        val resStr = binding.resInput.text.toString().trim().ifEmpty { DEFAULT_RES }
        val fps = binding.fpsInput.text.toString().trim().toIntOrNull() ?: DEFAULT_FPS
        val (w, h) = parseRes(resStr)

        prefs.edit()
            .putInt("bitrate", kbps)
            .putString("res", "${w}x${h}")
            .putInt("fps", fps)
            .apply()

        viewModel.setVideoConfig(kbps * 1000, w, h, fps)
        toast("設定適用: ${w}x${h} / ${kbps}kbps / ${fps}fps")
    }

    private fun parseRes(s: String): Pair<Int, Int> {
        val parts = s.lowercase().split("x")
        val w = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 1280
        val h = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 720
        return w to h
    }

    /** 前面/背面カメラを切り替え */
    @SuppressLint("MissingPermission")
    private fun toggleCamera() {
        val cm = cameraManager
        usingFront = !usingFront
        val target = (if (usingFront) cm.frontCameras else cm.backCameras).firstOrNull()
            ?: cm.cameras.firstOrNull()
        if (target != null) {
            viewModel.setCameraId(target)
            toast(if (usingFront) "前面カメラ" else "背面カメラ")
        }
    }

    private fun lockOrientation() {
        /**
         * Lock orientation while stream is running to avoid stream interruption if
         * user turns the device.
         */
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
    }

    private fun unlockOrientation() {
        requestedOrientation = ApplicationConstants.supportedOrientation
    }

    override fun onStart() {
        super.onStart()
        permissionsManager.requestPermissions()
    }

    @RequiresPermission(allOf = [Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO])
    private fun onPermissionsGranted() {
        setAVSource()
        setStreamerView()
    }

    @RequiresPermission(allOf = [Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO])
    private fun setAVSource() {
        // Set audio and video sources.
        viewModel.setAudioSource()
        // カメラが無い端末（VR機など）でもクラッシュさせない
        try {
            viewModel.setCameraId(this@MainActivity.defaultCameraId)
        } catch (t: Throwable) {
            Log.e(TAG, "No camera available: $t")
            binding.statusText.text = "⚠ カメラ無し: ${t.message}"
            toast("カメラが見つかりません: ${t.message}")
        }
    }

    private fun setStreamerView() {
        lifecycleScope.launch {
            binding.preview.setVideoSourceProvider(viewModel.streamer) // Bind the streamer to the preview
        }
    }

    companion object {
        private const val TAG = "MainActivity"

        // 注: StreamPackのSRT URLパーサは conntimeo 等を受け付けない（streamid/latency/mss等のみ）
        private const val DEFAULT_URL =
            "srt://[YOUR-HOME-IPV6]:5000?streamid=publish:car&latency=4000&mss=1360"

        private const val DEFAULT_BITRATE_KBPS = 2500
        private const val DEFAULT_RES = "1280x720"
        private const val DEFAULT_FPS = 30
    }
}
