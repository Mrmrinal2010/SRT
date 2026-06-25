package io.github.thibaultbee.streampack.app

import android.Manifest
import android.media.AudioFormat
import android.media.MediaFormat
import android.util.Range
import android.util.Size
import androidx.annotation.RequiresPermission
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import io.github.thibaultbee.streampack.app.data.rotation.RotationRepository
import io.github.thibaultbee.streampack.core.configuration.BitrateRegulatorConfig
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MicrophoneSourceFactory
import io.github.thibaultbee.streampack.core.interfaces.releaseBlocking
import io.github.thibaultbee.streampack.core.interfaces.setCameraId
import io.github.thibaultbee.streampack.core.interfaces.startStream
import io.github.thibaultbee.streampack.core.streamers.single.AudioConfig
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer
import io.github.thibaultbee.streampack.core.streamers.single.VideoConfig
import io.github.thibaultbee.streampack.core.utils.extensions.isClosedException
import io.github.thibaultbee.streampack.ext.srt.regulator.controllers.DefaultSrtBitrateRegulatorController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainViewModel(
    private val rotationRepository: RotationRepository,
    val streamer: SingleStreamer
) : ViewModel() {
    private val defaultDispatcher = Dispatchers.Default

    /** ユーザーが配信を望んでいるか（再接続ループの継続条件） */
    @Volatile
    private var shouldStream = false
    private var currentUrl = ""
    private var connectionJob: Job? = null

    /** ABRの上限に使う、現在設定のビデオビットレート(bps) */
    private var videoBitrateBps = 2_500_000

    /**
     * A LiveData to observe the stream state.
     */
    val isStreamingLiveData: LiveData<Boolean>
        get() = streamer.isStreamingFlow.asLiveData()

    /**
     * A LiveData to observe the pending connection state.
     */
    private val _isTryingConnectionLiveData = MutableLiveData<Boolean>()
    val isTryingConnectionLiveData: LiveData<Boolean> = _isTryingConnectionLiveData

    /** 再接続の試行回数（0=通常） */
    private val _reconnectCountLiveData = MutableLiveData(0)
    val reconnectCountLiveData: LiveData<Int> = _reconnectCountLiveData

    /**
     * A LiveData to observe async disconnection errors.
     */
    val closedThrowableLiveData: LiveData<Throwable> =
        streamer.throwableFlow.filterNotNull().filter { it.isClosedException }.asLiveData()

    /**
     * A LiveData to observe streamer errors.
     */
    val throwableLiveData: LiveData<Throwable> =
        streamer.throwableFlow.filterNotNull().filter { !it.isClosedException }.asLiveData()

    /** The connection failed to established */
    private val _pendingConnectionFailedFlow = MutableStateFlow<Throwable?>(null)
    val pendingConnectionFailedLiveData: LiveData<Throwable> =
        _pendingConnectionFailedFlow.filterNotNull().asLiveData()

    init {
        /**
         * Listens to device rotation.
         */
        viewModelScope.launch(defaultDispatcher) {
            rotationRepository.rotationFlow.collect {
                streamer.setTargetRotation(it)
            }
        }
    }

    /**
     * Starts the stream with auto-reconnect.
     *
     * 接続→ABR付与→切断待ち→クリーンアップ→（望む限り）指数バックオフで再接続、を1ループで回す。
     */
    fun startStream(url: String) {
        currentUrl = url
        shouldStream = true
        _reconnectCountLiveData.postValue(0)
        connectionJob?.cancel()
        connectionJob = viewModelScope.launch {
            runWithAutoReconnect()
        }
    }

    private suspend fun runWithAutoReconnect() {
        // 前回の接続が残っていれば掃除してから開始
        cleanupConnection()
        var attempt = 0
        while (shouldStream) {
            _isTryingConnectionLiveData.postValue(true)
            val connected = try {
                streamer.startStream(currentUrl) // open + startStream（失敗時は内部でauto-close）
                true
            } catch (t: Throwable) {
                if (shouldStream) _pendingConnectionFailedFlow.emit(t)
                false
            }

            if (connected) {
                attempt = 0
                _reconnectCountLiveData.postValue(0)
                attachBitrateRegulator()
                _isTryingConnectionLiveData.postValue(false)

                // 切断（or 停止）まで待つ。startStream成功後はisOpenFlow=true。
                try {
                    streamer.isOpenFlow.first { !it }
                } catch (_: Throwable) {
                    // cancel等
                }

                cleanupConnection()
                if (!shouldStream) break
                // 予期せぬ切断 → 再接続
                attempt++
                _reconnectCountLiveData.postValue(attempt)
                delay(reconnectBackoffMs(attempt))
            } else {
                _isTryingConnectionLiveData.postValue(false)
                if (!shouldStream) break
                attempt++
                _reconnectCountLiveData.postValue(attempt)
                delay(reconnectBackoffMs(attempt))
            }
        }
        cleanupConnection()
        _isTryingConnectionLiveData.postValue(false)
    }

    /** 1,2,4,8秒…上限8秒 */
    private fun reconnectBackoffMs(attempt: Int): Long {
        val seconds = 1L shl (attempt - 1).coerceIn(0, 3)
        return (seconds * 1000L).coerceAtMost(8000L)
    }

    /** SRT接続にABR(適応ビットレート)を付与。回線が細ると自動でビットレートを落とす。 */
    private fun attachBitrateRegulator() {
        try {
            val maxBps = videoBitrateBps
            val minBps = MIN_BITRATE_BPS.coerceAtMost(maxBps)
            streamer.addBitrateRegulatorController(
                DefaultSrtBitrateRegulatorController.Factory(
                    bitrateRegulatorConfig = BitrateRegulatorConfig(
                        videoBitrateRange = Range(minBps, maxBps)
                    )
                )
            )
        } catch (_: Throwable) {
            // SRT以外/非対応時は無視
        }
    }

    /** 接続の後始末: ABR解除 → stopStream → close（isOpenFlowを確実にリセット） */
    private suspend fun cleanupConnection() {
        try {
            streamer.removeBitrateRegulatorController()
        } catch (_: Throwable) {
        }
        try {
            streamer.stopStream()
        } catch (_: Throwable) {
        }
        try {
            streamer.close()
        } catch (_: Throwable) {
        }
    }

    /**
     * Stops the stream and the auto-reconnect loop.
     */
    fun stopStream() {
        shouldStream = false
        connectionJob?.cancel()
        viewModelScope.launch {
            cleanupConnection()
            _isTryingConnectionLiveData.postValue(false)
            _reconnectCountLiveData.postValue(0)
        }
    }

    /**
     * Sets the audio configuration.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun setAudioConfig() {
        val audioConfig = AudioConfig(
            mimeType = MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate = 44100,
            channelConfig = AudioFormat.CHANNEL_IN_STEREO
        )

        viewModelScope.launch {
            streamer.setAudioConfig(audioConfig)
        }
    }

    /**
     * Sets the video configuration.
     *
     * @param bitrateBps 開始ビットレート(bps)。ABRの上限にもなる。
     * @param width 横解像度
     * @param height 縦解像度
     * @param fps フレームレート
     */
    fun setVideoConfig(bitrateBps: Int, width: Int, height: Int, fps: Int) {
        videoBitrateBps = bitrateBps
        val videoConfig = VideoConfig(
            mimeType = MediaFormat.MIMETYPE_VIDEO_AVC,
            startBitrate = bitrateBps,
            resolution = Size(width, height),
            fps = fps
        )

        viewModelScope.launch {
            streamer.setVideoConfig(videoConfig)
        }
    }

    /**
     * Sets the microphone as the audio source.
     */
    fun setAudioSource() {
        viewModelScope.launch {
            streamer.setAudioSource(MicrophoneSourceFactory())
        }
    }

    /**
     * Sets the camera with the given id as the video source.
     *
     * @param cameraId The camera id.
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    fun setCameraId(cameraId: String) {
        viewModelScope.launch {
            streamer.setCameraId(cameraId)
        }
    }

    override fun onCleared() {
        shouldStream = false
        connectionJob?.cancel()
        streamer.releaseBlocking()
    }

    companion object {
        private const val TAG = "MainViewModel"
        private const val MIN_BITRATE_BPS = 500_000
    }
}
