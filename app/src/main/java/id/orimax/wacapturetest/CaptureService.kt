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
        const val ACTION_STOP = "id.orimax.wacapturetest.STOP"

        /** Live amplitude (0..32767) for the UI level meter. */
        @Volatile var currentAmplitude: Int = 0
        /** Rolling count of non-silent buffers, to prove signal is arriving. */
        @Volatile var loudBufferCount: Int = 0
        @Volatile var totalBufferCount: Int = 0
        @Volatile var isRunning: Boolean = false
        @Volatile var lastFile: File? = null
        @Volatile var lastError: String? = null
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
        // Notification "Stop" button path.
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // CRITICAL: startForeground() must happen within ~5s of
        // startForegroundService(), BEFORE any user dialog / slow work.
        // v1 called it after MediaProjection was resolved -> Android killed
        // the app: "Context.startForegroundService() did not then call
        // Service.startForeground()".
        startForegroundCompat()

        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (code == -1 || data == null) {
            lastError = "Izin MediaProjection tidak diterima (data kosong). " +
                    "Coba lagi dan pastikan menekan tombol 'Mulai sekarang/Start now' di dialog."
            Log.e(TAG, "Missing projection result (code=$code data=$data)")
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startCapture(code, data)
        } catch (t: Throwable) {
            Log.e(TAG, "startCapture failed", t)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // IMPORTANCE_LOW made ColorOS classify this as "unimportant notification"
            // and hide it from the shade. Use DEFAULT so it stays visible.
            val ch = NotificationChannel(CHANNEL_ID, "Capture", NotificationManager.IMPORTANCE_DEFAULT)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
        val stopIntent = Intent(this, CaptureService::class.java).setAction(ACTION_STOP)
        val stopPi = android.app.PendingIntent.getService(
            this, 0, stopIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("WA Capture Test")
            .setContentText("Merekam audio panggilan… (tap Stop untuk berhenti)")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi
                ).build()
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notif)
        }
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        lastError = null
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(resultCode, data)
        val proj = projection ?: run {
            lastError = "getMediaProjection returned null"
            Log.e(TAG, lastError!!)
            stopSelf(); return
        }

        // Required on Android 14+; harmless and recommended on 11.
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection stopped by system/user")
                isRunning = false
            }
        }, android.os.Handler(android.os.Looper.getMainLooper()))

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
            lastError = "AudioRecord gagal init (state=${ar.state}); device mungkin blokir capture"
            Log.e(TAG, lastError!!)
            stopSelf(); return
        }

        lastFile = buildOutputFile()
        setupEncoder(lastFile!!)

        isRunning = true
        recordThread = Thread { recordLoop(ar) }.also { it.start() }
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
        runCatching { stopForeground(true) }
        super.onDestroy()
    }
}
