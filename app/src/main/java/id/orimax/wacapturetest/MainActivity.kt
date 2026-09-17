package id.orimax.wacapturetest

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale

/**
 * Test UI. IMPORTANT: the UI never "remembers" the recording state itself —
 * it polls CaptureService's static flags every 250 ms. That way, closing and
 * reopening the app (or the screen going black for MediaProjection) always
 * shows the TRUE state: still recording, or stopped.
 */
class MainActivity : Activity() {

    private lateinit var levelBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var tvSignal: TextView
    private lateinit var tvFile: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnPlay: Button

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null

    private val REQ_PERM = 1001
    private val REQ_PROJ = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        levelBar = findViewById(R.id.levelBar)
        tvStatus = findViewById(R.id.tvStatus)
        tvSignal = findViewById(R.id.tvSignal)
        tvFile = findViewById(R.id.tvFile)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnPlay = findViewById(R.id.btnPlay)

        btnStart.setOnClickListener { requestThenStart() }
        btnStop.setOnClickListener { stopCapture() }
        btnPlay.setOnClickListener { playResult() }

        // Start polling immediately: reflects service state even after reopen.
        startPolling()
    }

    private fun requestThenStart() {
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = need.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startProjection()
        else requestPermissions(missing.toTypedArray(), REQ_PERM)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_PERM) {
            if (results.all { it == PackageManager.PERMISSION_GRANTED }) startProjection()
            else toast("Izin RECORD_AUDIO wajib diberikan.")
        }
    }

    private fun startProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJ)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROJ) {
            if (resultCode == RESULT_OK && data != null) {
                CaptureService.lastError = null
                val i = Intent(this, CaptureService::class.java).apply {
                    putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(CaptureService.EXTRA_RESULT_DATA, data)
                }
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                toast("Mulai merekam. Notifikasi harus muncul di bar atas.")
            } else {
                toast("Ditolak. Izin rekam tidak diberikan.")
            }
        }
    }

    private fun stopCapture() {
        stopService(Intent(this, CaptureService::class.java))
        CaptureService.isRunning = false
    }

    private fun playResult() {
        val f: File? = CaptureService.lastFile
        if (f == null || !f.exists()) { toast("Belum ada file hasil."); return }
        runCatching { player?.release() }
        player = MediaPlayer().apply {
            setDataSource(f.absolutePath)
            setOnCompletionListener { toast("Selesai memutar.") }
            prepare()
            start()
        }
        toast("Memutar: ${f.name}")
    }

    /** Single source of truth: poll the service's public state. */
    private fun startPolling() {
        handler.post(object : Runnable {
            override fun run() {
                renderState()
                handler.postDelayed(this, 250)
            }
        })
    }

    private fun renderState() {
        val running = CaptureService.isRunning

        btnStart.isEnabled = !running
        btnStop.isEnabled = running

        if (running) {
            levelBar.progress = CaptureService.currentAmplitude
            val total = CaptureService.totalBufferCount
            val loud = CaptureService.loudBufferCount
            tvStatus.text = "SEDANG MEREKAM (app boleh diminimize)."
            tvSignal.text = String.format(
                Locale.US,
                "Sinyal: %d buffer, %d berbunyi (%.0f%%)",
                total, loud, if (total > 0) loud * 100.0 / total else 0.0
            )
        } else {
            levelBar.progress = 0
            val err = CaptureService.lastError
            tvStatus.text = if (err != null) "GAGAL: $err"
            else "Siap. Tekan \"Mulai Rekam\" lalu terima dialog izin Android."
        }

        val f = CaptureService.lastFile
        if (f != null && f.exists()) {
            btnPlay.isEnabled = !running
            val kb = f.length() / 1024
            val warn = if (!running && f.length() < 5_000)
                "  <-- terlalu kecil, audio kemungkinan TIDAK tertangkap" else ""
            tvFile.text = "File: ${f.name}\nUkuran: $kb KB$warn"
        } else {
            btnPlay.isEnabled = false
            if (!running) tvFile.text = "Belum ada file."
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        super.onDestroy()
    }
}
