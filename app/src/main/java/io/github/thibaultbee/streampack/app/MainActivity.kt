package io.github.thibaultbee.streampack.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
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
import io.github.thibaultbee.streampack.app.managers.AudioSourceManager
import io.github.thibaultbee.streampack.app.views.AudioVisualizerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import android.widget.LinearLayout
import android.widget.Button
import android.view.LayoutInflater
import android.view.View
import android.media.MediaRecorder
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(this.application)
    }

    private val prefs by lazy { getSharedPreferences("carstream", MODE_PRIVATE) }

    private var usingFront = false

    private val streamerRequiredPermissions =
        listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

    @SuppressLint("MissingPermission")
    private val permissionsManager = PermissionsManager(
        this,
        streamerRequiredPermissions,
        onAllGranted = { onPermissionsGranted() },
        onShowPermissionRationale = { permissions, onRequiredPermissionLastTime ->
            showDialog(
                title = getString(R.string.denied),
                message = "Explain why you need to grant $permissions permissions to stream",
                positiveButtonText = R.string.accept,
                onPositiveButtonClick = { onRequiredPermissionLastTime() },
                negativeButtonText = R.string.denied
            )
        },
        onDenied = {
            showDialog(
                getString(R.string.denied),
                "You need to grant all permissions to stream",
                positiveButtonText = 0,
                negativeButtonText = 0
            )
        })

    private val streamerLifeCycleObserver by lazy { StreamerViewModelLifeCycleObserver(viewModel.streamer) }
    private lateinit var audioSourceManager: AudioSourceManager
    private var isHudVisible = true
    private var visualizerJob: Job? = null
    private var mediaRecorder: MediaRecorder? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        audioSourceManager = AudioSourceManager(this)

        bindProperties()
    }

    override fun onResume() {
        super.onResume()
        // Re-configure in case settings were changed
        configureStreamer()
        updateStatusDetails()
    }

    private fun bindProperties() {
        binding.liveButton.setOnCheckedChangeListener { view, isChecked ->
            if (view.isPressed) {
                if (isChecked) {
                    val url = prefs.getString("url", DEFAULT_URL) ?: DEFAULT_URL
                    binding.tvLiveStatus.text = getString(R.string.status_connecting)
                    binding.tvLiveStatus.setBackgroundResource(R.drawable.rounded_bg_primary)
                    viewModel.startStream(url)
                } else {
                    viewModel.stopStream()
                    binding.tvLiveStatus.text = getString(R.string.status_stopped)
                    binding.tvLiveStatus.setBackgroundColor(Color.GRAY)
                }
            }
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        var isMuted = false
        binding.btnMute.setOnClickListener {
            isMuted = !isMuted
            toast("Mute toggled: $isMuted")
            if (isMuted) {
                binding.btnMute.setImageResource(R.drawable.ic_mic_off)
                binding.btnMute.alpha = 0.5f
            } else {
                binding.btnMute.setImageResource(android.R.drawable.ic_btn_speak_now)
                binding.btnMute.alpha = 1.0f
            }
        }

        binding.btnSwitchCam.setOnClickListener { toggleCamera() }
        binding.btnAudioSources.setOnClickListener { showAudioSourcesDialog() }

        binding.btnToggleHud.setOnClickListener {
            isHudVisible = !isHudVisible
            binding.uiContainer.visibility = if (isHudVisible) View.VISIBLE else View.INVISIBLE
            binding.btnToggleHud.setImageResource(if (isHudVisible) R.drawable.ic_eye else R.drawable.ic_eye_off)
        }

        lifecycle.addObserver(streamerLifeCycleObserver)
        configureStreamer()

        viewModel.closedThrowableLiveData.observe(this) {
            Log.e(TAG, "Disconnect: $it")
            binding.tvLiveStatus.text = getString(R.string.status_error, "Disconnected")
            binding.tvLiveStatus.setBackgroundColor(Color.RED)
        }

        viewModel.pendingConnectionFailedLiveData.observe(this) {
            Log.e(TAG, "Connection error: $it")
            binding.tvLiveStatus.text = getString(R.string.status_error, "Connection Failed")
            binding.tvLiveStatus.setBackgroundColor(Color.RED)
        }

        viewModel.throwableLiveData.observe(this) {
            Log.e(TAG, "Error: $it")
            binding.tvLiveStatus.text = getString(R.string.status_error, it.message)
            binding.tvLiveStatus.setBackgroundColor(Color.RED)
        }

        viewModel.reconnectCountLiveData.observe(this) { n ->
            if (n > 0 && viewModel.isStreamingLiveData.value != true) {
                binding.tvLiveStatus.text = getString(R.string.status_reconnecting)
                binding.tvLiveStatus.setBackgroundResource(R.drawable.rounded_bg_primary)
            }
        }

        viewModel.isStreamingLiveData.observe(this) { isStreaming ->
            if (isStreaming) {
                binding.tvLiveStatus.text = getString(R.string.status_live)
                binding.tvLiveStatus.setBackgroundColor(Color.RED)
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

    private fun showAudioSourcesDialog() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_audio_sources, null)
        val llAudio = view.findViewById<LinearLayout>(R.id.llAudio)
        
        val audios = audioSourceManager.getAvailableAudioSources()
        audios.forEach { audio ->
            val btn = Button(this)
            btn.text = "🎙️ ${audio.name}"
            btn.setOnClickListener {
                viewModel.setAudioSource()
                binding.tvActiveAudio.text = "MIC: ${audio.name.uppercase()}"
                dialog.dismiss()
            }
            llAudio.addView(btn)
        }
        
        dialog.setContentView(view)
        dialog.show()
    }

    private fun updateStatusDetails() {
        val host = prefs.getString("srt_host", "[YOUR-HOME-IPV6]") ?: ""
        val port = prefs.getInt("srt_port", 5000)
        val streamId = prefs.getString("srt_streamid", "publish:car") ?: ""
        val latency = prefs.getInt("srt_latency", 4000)
        val bitrate = prefs.getInt("bitrate", DEFAULT_BITRATE_KBPS)
        val fps = prefs.getInt("fps", DEFAULT_FPS)
        val res = prefs.getString("res", DEFAULT_RES) ?: DEFAULT_RES
        
        binding.tvStatusDetails.text = "Server: $host:$port\nID: $streamId | Latency: ${latency}ms | $res @ ${fps}fps | ${bitrate}kbps"
    }

    private fun configureStreamer() {
        viewModel.setAudioConfig()

        val kbps = prefs.getInt("bitrate", DEFAULT_BITRATE_KBPS)
        val res = prefs.getString("res", DEFAULT_RES) ?: DEFAULT_RES
        val fps = prefs.getInt("fps", DEFAULT_FPS)
        val (w, h) = parseRes(res)

        viewModel.setVideoConfig(kbps * 1000, w, h, fps)
        
        // Also check if URL exists, if not initialize it from defaults
        if (!prefs.contains("url")) {
            val host = prefs.getString("srt_host", "[YOUR-HOME-IPV6]")
            val port = prefs.getInt("srt_port", 5000)
            val streamId = prefs.getString("srt_streamid", "publish:car")
            val latency = prefs.getInt("srt_latency", 4000)
            val url = "srt://$host:$port?streamid=$streamId&latency=$latency&mss=1360"
            prefs.edit().putString("url", url).apply()
        }
    }

    private fun parseRes(s: String): Pair<Int, Int> {
        val parts = s.lowercase().split("x")
        val w = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 1280
        val h = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 720
        return w to h
    }

    @SuppressLint("MissingPermission")
    private fun toggleCamera() {
        val cm = cameraManager
        usingFront = !usingFront
        val target = (if (usingFront) cm.frontCameras else cm.backCameras).firstOrNull()
            ?: cm.cameras.firstOrNull()
        if (target != null) {
            viewModel.setCameraId(target)
            toast(if (usingFront) getString(R.string.front_camera) else getString(R.string.rear_camera))
        }
    }

    private fun startAudioVisualizer() {
        try {
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(cacheDir.absolutePath + "/dummy_audio.3gp")
                prepare()
                start()
            }

            visualizerJob?.cancel()
            visualizerJob = lifecycleScope.launch {
                while (true) {
                    val maxAmp = mediaRecorder?.maxAmplitude ?: 0
                    // Max amplitude is 32767. Normalize to 0.0 - 1.0
                    val ratio = (maxAmp / 32767f).coerceIn(0f, 1f)
                    binding.audioVisualizer.setAmplitude(ratio)
                    delay(50) // Poll every 50ms for smooth 20fps visualization
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // If mic is exclusively locked by older Android version, visualizer will fail silently.
            binding.audioVisualizer.setAmplitude(0f)
        }
    }

    private fun stopAudioVisualizer() {
        visualizerJob?.cancel()
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudioVisualizer()
    }

    private fun lockOrientation() {
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
        startAudioVisualizer()
    }

    @RequiresPermission(allOf = [Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO])
    private fun setAVSource() {
        viewModel.setAudioSource()
        try {
            viewModel.setCameraId(this@MainActivity.defaultCameraId)
        } catch (t: Throwable) {
            Log.e(TAG, "No camera available: $t")
            binding.tvLiveStatus.text = getString(R.string.no_camera, t.message)
            toast(getString(R.string.no_camera, t.message))
        }
    }

    private fun setStreamerView() {
        lifecycleScope.launch {
            binding.preview.setVideoSourceProvider(viewModel.streamer) 
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val DEFAULT_URL =
            "srt://[YOUR-HOME-IPV6]:5000?streamid=publish:car&latency=4000&mss=1360"
        private const val DEFAULT_BITRATE_KBPS = 20000
        private const val DEFAULT_RES = "3840x2160"
        private const val DEFAULT_FPS = 30
    }
}
