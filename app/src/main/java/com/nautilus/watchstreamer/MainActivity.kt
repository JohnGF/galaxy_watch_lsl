package com.nautilus.watchstreamer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

class MainActivity : Activity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelSensor: Sensor? = null
    private var gyroSensor: Sensor? = null
    private var hrSensor: Sensor? = null

    private var wakeLock: PowerManager.WakeLock? = null

    private var targetIp = "192.168.137.1"  // Default Hotspot IP
    private var targetPort = 5005
    private var isStreaming = false
    private var socket: DatagramSocket? = null
    private val executor = Executors.newSingleThreadExecutor()

    private var lastAccel = floatArrayOf(0f, 0f, 0f)
    private var lastGyro = floatArrayOf(0f, 0f, 0f)
    private var lastHr = 0f

    // Touch values
    private var touchX = -1f
    private var touchY = -1f
    private var isTouched = 0 // 1 if active touch, 0 otherwise

    private var packetCount = 0L

    private lateinit var rootContainer: FrameLayout
    private lateinit var controlsLayout: LinearLayout
    private lateinit var touchSurface: View
    private lateinit var tvTouchStatus: TextView
    private lateinit var tvStatus: TextView
    private lateinit var etIp: EditText
    private lateinit var btnToggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Keep screen on continuously so watch display does not turn off or sleep app
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
            setText(targetIp)
            hint = "PC Hotspot IP"
            textSize = 12f
            gravity = android.view.Gravity.CENTER
        }

        btnToggle = Button(this).apply {
            text = "START STREAMING"
            setOnClickListener {
                if (isStreaming) stopStreaming() else startStreaming()
            }
        }

        controlsLayout.addView(tvStatus)
        controlsLayout.addView(etIp)
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

            val innerLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                addView(tvHint)
                addView(tvTouchStatus)
            }

            addView(innerLayout, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))

            setOnTouchListener { _, event ->
                if (!isStreaming) return@setOnTouchListener false

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        touchX = event.x
                        touchY = event.y
                        isTouched = 1
                        tvTouchStatus.text = "X: ${touchX.toInt()}, Y: ${touchY.toInt()}"
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        touchX = event.x
                        touchY = event.y
                        isTouched = 0
                        tvTouchStatus.text = "Released (X: ${touchX.toInt()}, Y: ${touchY.toInt()})"
                    }
                }
                sendUdpPacket()
                true
            }
        }

        rootContainer.addView(controlsLayout)
        rootContainer.addView(touchSurface)
        setContentView(rootContainer)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        hrSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)

        checkPermissions()
    }

    override fun onBackPressed() {
        if (isStreaming) {
            stopStreaming()
        } else {
            super.onBackPressed()
        }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BODY_SENSORS), 101)
        }
    }

    private fun isNetworkConnected(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun startStreaming() {
        if (!isNetworkConnected()) {
            Toast.makeText(this, "No network connection. Cannot stream.", Toast.LENGTH_LONG).show()
            tvStatus.text = "Error: No Network"
            return
        }

        targetIp = etIp.text.toString().trim()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LSLStreamer::WakeLock").apply {
            acquire(10800000L) // 3 hours max
        }

        executor.execute {
            try {
                socket = DatagramSocket()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        hrSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }

        isStreaming = true
        controlsLayout.visibility = View.GONE
        touchSurface.visibility = View.VISIBLE
    }

    private fun stopStreaming() {
        isStreaming = false
        sensorManager.unregisterListener(this)
        wakeLock?.release()
        executor.execute {
            socket?.close()
            socket = null
        }
        touchSurface.visibility = View.GONE
        controlsLayout.visibility = View.VISIBLE
        btnToggle.text = "START STREAMING"
        tvStatus.text = "Status: STOPPED (Sent: $packetCount)"
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isStreaming || event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                lastAccel[0] = event.values[0]
                lastAccel[1] = event.values[1]
                lastAccel[2] = event.values[2]
                sendUdpPacket()
            }
            Sensor.TYPE_GYROSCOPE -> {
                lastGyro[0] = event.values[0]
                lastGyro[1] = event.values[1]
                lastGyro[2] = event.values[2]
            }
            Sensor.TYPE_HEART_RATE -> {
                lastHr = event.values[0]
            }
        }
    }

    private fun sendUdpPacket() {
        val payload = "${lastAccel[0]},${lastAccel[1]},${lastAccel[2]},${lastGyro[0]},${lastGyro[1]},${lastGyro[2]},$lastHr,$touchX,$touchY,$isTouched"
        val bytes = payload.toByteArray()

        executor.execute {
            try {
                val address = InetAddress.getByName(targetIp)
                val packet = DatagramPacket(bytes, bytes.size, address, targetPort)
                socket?.send(packet)
                packetCount++
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
