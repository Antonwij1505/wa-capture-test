package id.orimax.wacapturetest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that:
 *   1. Attaches to a MediaProjection session (user-approved).
 *   2. Creates an AudioRecord fed by AudioPlaybackCaptureConfiguration
 *      (the OFFICIAL, consent-based way to capture app audio output).
 *   3. Encodes PCM 48kHz stereo -> AAC -> .m4a file.
 *
 * This is the exact mechanism the real app will use for WhatsApp calls.
 * It does NOT need root, Shizuku, or any OEM "helper" (which ColorOS blocks).
 */
class CaptureService : Service() {

    companion object {
        const val TAG = "WaCaptureTest"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val CHANNEL_ID = "capture_channel"

        /** Live amplitude (0..32767) for the UI level meter. */
        @Volatile var currentAmplitude: Int = 0
        /** Rolling count of non-silent buffers, to prove signal is arriving. */
        @Volatile var loudBufferCount: Int = 0
        @Volatile var totalBufferCount: Int = 0
        @Volatile var isRunning: Boolean = false
        @Volatile var lastFile: File? = null
    }

    private var projection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var recordThread: Thread? = null

    private val sampleRate = 48_000
    private val channelCount = 2
    private var trackIndex = -1
    private var muxerStarted = false
    private var encodedPtsUs = 0L
    private var audioPtsUs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (code == -1 || data == null) {
            Log.e(TAG, "Missing projection result")
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        startCapture(code, data)
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Capture", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("WA Capture Test")
            .setContentText("Merekam audio panggilan…")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notif)
        }
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(resultCode, data)
        val proj = projection ?: run {
            Log.e(TAG, "getMediaProjection returned null")
            stopSelf(); return
        }

        // --- The crucial part: capture OTHER apps' audio output (WhatsApp). ---
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION) // WA voice call
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)               // media playback
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf * 4, sampleRate * channelCount * 2)

        audioRecord = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufSize)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()

        val ar = audioRecord!!
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized (state=${ar.state})")
            stopSelf(); return
        }

        lastFile = buildOutputFile()
        setupEncoder(lastFile!!)

        recordThread = Thread { recordLoop(ar) }.also { it.start() }
        isRunning = true
        Log.i(TAG, "Capture started -> ${lastFile!!.absolutePath}")
    }

    private fun buildOutputFile(): File {
        val dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir
        if (!dir.exists()) dir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "test_wa_$ts.m4a")
    }

    private fun setupEncoder(file: File) {
        val mime = MediaFormat.MIMETYPE_AUDIO_AAC
        encoder = MediaCodec.createEncoderByType(mime)
        val fmt = MediaFormat.createAudioFormat(mime, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        encoder!!.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder!!.start()
        muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    private fun recordLoop(ar: AudioRecord) {
        val pcm = ShortArray(sampleRate * channelCount / 10) // ~100 ms
        val bytes = ByteArray(pcm.size * 2)
        ar.startRecording()
        try {
            while (isRunning && !Thread.currentThread().isInterrupted) {
                val n = ar.read(pcm, 0, pcm.size)
                if (n <= 0) continue

                // Level meter + signal presence check.
                var peak = 0
                for (i in 0 until n) {
                    val v = kotlin.math.abs(pcm[i].toInt())
                    if (v > peak) peak = v
                }
                currentAmplitude = peak
                totalBufferCount++
                if (peak > 200) loudBufferCount++

                feedEncoder(pcm, n)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "recordLoop error", t)
        } finally {
            runCatching { ar.stop() }
            runCatching { ar.release() }
            audioRecord = null
            signalEndOfStream()
        }
    }

    private fun feedEncoder(pcm: ShortArray, n: Int) {
        val enc = encoder ?: return
        val inIndex = enc.dequeueInputBuffer(10_000)
        if (inIndex >= 0) {
            val inBuf = enc.getInputBuffer(inIndex)!!
            inBuf.clear()
            val sizeBytes = n * 2
            inBuf.put(toBytes(pcm, n))
            enc.queueInputBuffer(inIndex, 0, sizeBytes, audioPtsUs, 0)
            audioPtsUs += (n.toLong() * 1_000_000L) / (sampleRate * channelCount) * channelCount
        }
        drainEncoder(false)
    }

    private fun toBytes(pcm: ShortArray, n: Int): ByteArray {
        val out = ByteArray(n * 2)
        var j = 0
        for (i in 0 until n) {
            val s = pcm[i].toInt()
            out[j++] = (s and 0xFF).toByte()
            out[j++] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val enc = encoder ?: return
        val mx = muxer ?: return
        if (endOfStream) {
            val idx = enc.dequeueInputBuffer(10_000)
            if (idx >= 0) enc.queueInputBuffer(idx, 0, 0, audioPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        while (true) {
            val info = MediaCodec.BufferInfo()
            val outIndex = enc.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = mx.addTrack(enc.outputFormat)
                    mx.start()
                    muxerStarted = true
                }
                outIndex >= 0 -> {
                    val buf = enc.getOutputBuffer(outIndex)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        info.size = 0
                    }
                    if (info.size > 0 && muxerStarted) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        mx.writeSampleData(trackIndex, buf, info)
                    }
                    enc.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun signalEndOfStream() {
        runCatching { drainEncoder(true) }
        runCatching { encoder?.stop(); encoder?.release() }
        runCatching { if (muxerStarted) muxer?.stop(); muxer?.release() }
        encoder = null; muxer = null
    }

    override fun onDestroy() {
        isRunning = false
        recordThread?.interrupt()
        runCatching { projection?.stop() }
        super.onDestroy()
    }
}
