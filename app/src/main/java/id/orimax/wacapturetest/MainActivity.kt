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
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale

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
                val i = Intent(this, CaptureService::class.java).apply {
                    putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(CaptureService.EXTRA_RESULT_DATA, data)
                }
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                btnStart.isEnabled = false
                btnStop.isEnabled = true
                btnPlay.isEnabled = false
                tvStatus.text = "Merekam… sekarang lakukan panggilan WA."
                startMeter()
            } else {
                toast("Ditolak. Izin rekam tidak diberikan.")
            }
        }
    }

    private fun stopCapture() {
        stopService(Intent(this, CaptureService::class.java))
        btnStart.isEnabled = true
        btnStop.isEnabled = false
        btnPlay.isEnabled = true
        tvStatus.text = "Berhenti. Tekan \"Putar Hasil\" untuk mendengarkan."
        stopMeter()
        val f = CaptureService.lastFile
        if (f != null && f.exists()) {
            tvFile.text = "File: ${f.absolutePath}\nUkuran: ${f.length() / 1024} KB"
            if (f.length() < 5_000) {
                tvStatus.text =
                    "PERINGATAN: file sangat kecil (${f.length()} byte). Kemungkinan tidak ada audio tertangkap."
            }
        }
    }

    private fun playResult() {
        val f: File? = CaptureService.lastFile
        if (f == null || !f.exists()) { toast("Belum ada file hasil."); return }
        player?.release()
        player = MediaPlayer().apply {
            setDataSource(f.absolutePath)
            setOnCompletionListener { toast("Selesai memutar.") }
            prepare()
            start()
        }
        toast("Memutar: ${f.name}")
    }

    private fun startMeter() {
        handler.post(object : Runnable {
            override fun run() {
                if (!CaptureService.isRunning) return
                levelBar.progress = CaptureService.currentAmplitude
                val total = CaptureService.totalBufferCount
                val loud = CaptureService.loudBufferCount
                tvSignal.text = String.format(
                    Locale.US,
                    "Sinyal: %d buffer, %d berbunyi (%.0f%%)",
                    total, loud, if (total > 0) loud * 100.0 / total else 0.0
                )
                handler.postDelayed(this, 200)
            }
        })
    }

    private fun stopMeter() { handler.removeCallbacksAndMessages(null) }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        stopMeter()
        player?.release()
        super.onDestroy()
    }
}
