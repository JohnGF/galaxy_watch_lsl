package com.nautilus.watchstreamer

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.nautilus.watchstreamer.core.WireProtocol

/**
 * Setup screen and touch surface. Streaming itself runs in [StreamingService],
 * so it keeps going when this Activity is paused by the screen turning off.
 */
class MainActivity : Activity() {

    companion object {
        private const val REQUEST_PERMISSIONS = 101
        private const val REQUEST_BACKGROUND_SENSORS = 102
        private const val PREF_ASKED_BATTERY = "asked_battery_optimization"
        private const val STATUS_REFRESH_MS = 1000L
    }

    private var service: StreamingService? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var rootContainer: FrameLayout
    private lateinit var controlsLayout: LinearLayout
    private lateinit var touchSurface: View
    private lateinit var tvTouchStatus: TextView
    private lateinit var tvStreamStatus: TextView
    private lateinit var tvStatus: TextView
    private lateinit var etIp: EditText
    private lateinit var btnLocation: Button
    private lateinit var btnToggle: Button

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as StreamingService.LocalBinder).service
            refreshStatus()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            refreshStatus()
        }
    }

    private val statusTicker = object : Runnable {
        override fun run() {
            refreshStatus()
            uiHandler.postDelayed(this, STATUS_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keeps the touch surface visible; streaming no longer depends on the screen being on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        rootContainer = FrameLayout(this)

        // Setup Controls Layout (Setup screen)
        controlsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }

        tvStatus = TextView(this).apply {
            text = "Status: STOPPED"
            textSize = 12f
            gravity = android.view.Gravity.CENTER
        }

        etIp = EditText(this).apply {
            setText(prefs().getString(StreamingService.PREF_TARGET_IP, StreamingService.DEFAULT_TARGET_IP))
            hint = "auto, or the PC's IP"
            textSize = 12f
            gravity = android.view.Gravity.CENTER
        }

        // Body site of this device; the bridge names its LSL streams after it (IMU_L_wrist, ...)
        btnLocation = Button(this).apply {
            text = prefs().getString(StreamingService.PREF_LOCATION, WireProtocol.LOCATIONS[0])
            textSize = 12f
            setOnClickListener {
                val locations = WireProtocol.LOCATIONS
                val next = locations[(locations.indexOf(text.toString()) + 1) % locations.size]
                text = next
                prefs().edit().putString(StreamingService.PREF_LOCATION, next).apply()
            }
        }

        btnToggle = Button(this).apply {
            text = "START STREAMING"
            setOnClickListener {
                if (service?.isRunning == true) stopStreaming() else startStreaming()
            }
        }

        controlsLayout.addView(tvStatus)
        controlsLayout.addView(etIp)
        controlsLayout.addView(btnLocation)
        controlsLayout.addView(btnToggle)

        // Touch surface overlay when streaming
        touchSurface = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            visibility = View.GONE

            val tvHint = TextView(context).apply {
                text = "TOUCH SCREEN ACTIVE\nPress BACK to stop"
                setTextColor(android.graphics.Color.GRAY)
                textSize = 12f
                gravity = android.view.Gravity.CENTER
            }

            tvTouchStatus = TextView(context).apply {
                text = "X: 0, Y: 0"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 14f
                gravity = android.view.Gravity.CENTER
            }

            tvStreamStatus = TextView(context).apply {
                setTextColor(android.graphics.Color.GRAY)
                textSize = 10f
                gravity = android.view.Gravity.CENTER
            }

            val innerLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                addView(tvHint)
                addView(tvTouchStatus)
                addView(tvStreamStatus)
            }

            addView(innerLayout, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))

            setOnTouchListener { _, event ->
                val s = service?.takeIf { it.isRunning } ?: return@setOnTouchListener false

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        s.onTouch(event.x, event.y, true)
                        tvTouchStatus.text = "X: ${event.x.toInt()}, Y: ${event.y.toInt()}"
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        s.onTouch(event.x, event.y, false)
                        tvTouchStatus.text = "Released (X: ${event.x.toInt()}, Y: ${event.y.toInt()})"
                    }
                }
                true
            }
        }

        rootContainer.addView(controlsLayout)
        rootContainer.addView(touchSurface)
        setContentView(rootContainer)

        checkPermissions()
        askBatteryOptimizationOnce()
    }

    override fun onStart() {
        super.onStart()
        // Flag 0: attach to the service if it is running, never create it just by binding
        bindService(Intent(this, StreamingService::class.java), connection, 0)
        uiHandler.post(statusTicker)
    }

    override fun onStop() {
        uiHandler.removeCallbacks(statusTicker)
        unbindService(connection)
        service = null
        super.onStop()
    }

    override fun onBackPressed() {
        if (service?.isRunning == true) {
            stopStreaming()
        } else {
            super.onBackPressed()
        }
    }

    private fun prefs() = getSharedPreferences(StreamingService.PREFS, Context.MODE_PRIVATE)

    private fun checkPermissions() {
        val needed = mutableListOf(Manifest.permission.BODY_SENSORS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            requestBackgroundSensors()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) requestBackgroundSensors()
    }

    /** Android only grants background body-sensor access as a second step, after BODY_SENSORS. */
    private fun requestBackgroundSensors() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS_BACKGROUND) == PackageManager.PERMISSION_GRANTED) return
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BODY_SENSORS_BACKGROUND), REQUEST_BACKGROUND_SENSORS)
    }

    /** Doze can still stop a foreground service's network on some builds; ask once to be exempt. */
    private fun askBatteryOptimizationOnce() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return
        if (prefs().getBoolean(PREF_ASKED_BATTERY, false)) return
        prefs().edit().putBoolean(PREF_ASKED_BATTERY, true).apply()
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: ActivityNotFoundException) {
            // Some Wear OS builds have no such settings screen; the foreground service still holds the locks
        }
    }

    private fun startStreaming() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
            // A health foreground service cannot start without it
            Toast.makeText(this, "Body sensors permission needed", Toast.LENGTH_LONG).show()
            checkPermissions()
            return
        }

        prefs().edit().putString(StreamingService.PREF_TARGET_IP, etIp.text.toString().trim()).apply()
        ContextCompat.startForegroundService(this, Intent(this, StreamingService::class.java))
        controlsLayout.visibility = View.GONE
        touchSurface.visibility = View.VISIBLE
    }

    private fun stopStreaming() {
        val sent = service?.packetCount ?: 0L
        stopService(Intent(this, StreamingService::class.java))
        // Drop the stopped instance; the binding reconnects to the next one on restart
        service = null
        touchSurface.visibility = View.GONE
        controlsLayout.visibility = View.VISIBLE
        btnToggle.text = "START STREAMING"
        tvStatus.text = "Status: STOPPED (Sent: $sent)"
    }

    private fun refreshStatus() {
        val s = service
        if (s == null || !s.isRunning) {
            // Also catches a stop from the notification while this screen is open
            if (touchSurface.visibility == View.VISIBLE && s?.isRunning == false) {
                touchSurface.visibility = View.GONE
                controlsLayout.visibility = View.VISIBLE
            }
            return
        }
        controlsLayout.visibility = View.GONE
        touchSurface.visibility = View.VISIBLE
        val wifi = if (s.isWifiBound) "Wi-Fi OK" else "Waiting for Wi-Fi"
        tvStreamStatus.text = "$wifi · ${s.targetDescription()}\nsent ${s.packetCount} · dropped ${s.droppedCount}"
    }
}
