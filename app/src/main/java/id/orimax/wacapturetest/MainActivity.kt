package id.orimax.wacapturetest

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale

/**
 * Test UI (v4).
 *
 * v3 bug fixed here: when a permission was DENIED the activity only showed a
 * 2-second Toast and left `lastError` null, so renderState() fell back to the
 * default "Siap. Tekan Mulai Rekam..." text. The screen looked frozen/dead
 * with no error, no notification and no log. Now EVERY failure path writes a
 * persistent, on-screen message plus a Log.e with tag WaCaptureTest.
 *
 * Also: the screen now shows the running versionName so we can tell v2/v3/v4
 * apart (all previous builds shipped versionName "1.0").
 */
class MainActivity : Activity() {

    private lateinit var levelBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var tvSignal: TextView
    private lateinit var tvFile: TextView
    private lateinit var tvVersion: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnPlay: Button

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null

    /** Set when we just launched a system dialog, so the UI can say so. */
    @Volatile private var waitingForPermission = false
    @Volatile private var waitingForProjection = false

    private val REQ_PERM = 1001
    private val REQ_PROJ = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        levelBar = findViewById(R.id.levelBar)
        tvStatus = findViewById(R.id.tvStatus)
        tvSignal = findViewById(R.id.tvSignal)
        tvFile = findViewById(R.id.tvFile)
        tvVersion = findViewById(R.id.tvVersion)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnPlay = findViewById(R.id.btnPlay)

        tvVersion.text = "v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})"

        btnStart.setOnClickListener { requestThenStart() }
        btnStop.setOnClickListener { stopCapture() }
        btnPlay.setOnClickListener { playResult() }

        // If the user denied microphone permanently, offer the settings page.
        tvStatus.setOnClickListener { openAppSettings() }

        startPolling()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from a dialog: the dialog is gone either way.
        waitingForPermission = false
    }

    private fun fail(msg: String) {
        android.util.Log.e(CaptureService.TAG, "UI FAILURE: $msg")
        CaptureService.lastError = msg
        CaptureService.isRunning = false
    }

    private fun requestThenStart() {
        CaptureService.lastError = null
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = need.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startProjection()
        } else {
            // v4: make the screen SAY a dialog is coming. Previously the screen
            // stayed on the default text and the user thought the app was dead.
            waitingForPermission = true
            btnStart.isEnabled = false
            tvStatus.text = "Menunggu izin mikrofon… setujui dialog yang muncul di layar."
            requestPermissions(missing.toTypedArray(), REQ_PERM)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code != REQ_PERM) return
        waitingForPermission = false
        btnStart.isEnabled = true

        if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) {
            startProjection()
            return
        }

        // Figure out WHICH permission was denied, for a precise message.
        val denied = perms.filterIndexed { i, _ ->
            i < results.size && results[i] != PackageManager.PERMISSION_GRANTED
        }
        val micDenied = denied.contains(Manifest.permission.RECORD_AUDIO)
        val permName = if (micDenied) "MIKROFON (RECORD_AUDIO)" else denied.joinToString()

        val permanently = micDenied && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)

        fail(
            "Izin $permName DITOLAK." +
                if (permanently) " Izin diblokir permanen — buka Setelan > Aplikasi > WA Capture Test > Izin, izinkan Mikrofon, lalu coba lagi."
                else " Ulangi dan tekan \"Izinkan\"."
        )
        toast(if (permanently) "Izin mikrofon diblokir permanen. Mengarahkan ke Setelan…" else "Izin $permName ditolak.")

        if (permanently) openAppSettings()
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
            )
        }
    }

    private fun startProjection() {
        waitingForProjection = true
        btnStart.isEnabled = false
        tvStatus.text = "Menunggu izin rekam layar… tekan \"MULAI SEKARANG\" di dialog."
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJ)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJ) return
        waitingForProjection = false
        btnStart.isEnabled = true

        if (resultCode != RESULT_OK || data == null) {
            fail(
                "Izin rekam layar DITOLAK / dialog ditutup (resultCode=$resultCode). " +
                    "Ulangi dan tekan tombol \"MULAI SEKARANG\" — jangan tap di luar dialog."
            )
            toast("Izin ditolak. Ulangi dan tekan \"MULAI SEKARANG\".")
            return
        }

        val i = Intent(this, CaptureService::class.java).apply {
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_RESULT_DATA, data)
        }
        CaptureService.lastError = null
        tvStatus.text = "Menyalakan service…"
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        toast("Izin diterima. Merekam…")
    }

    private fun stopCapture() {
        stopService(Intent(this, CaptureService::class.java))
        CaptureService.isRunning = false
        waitingForProjection = false
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

        btnStart.isEnabled = !running && !waitingForPermission && !waitingForProjection
        btnStop.isEnabled = running || CaptureService.lastFile != null

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
        } else if (waitingForPermission || waitingForProjection) {
            // Leave the "menunggu…" text we set before launching the dialog.
            levelBar.progress = 0
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

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        super.onDestroy()
    }
}
