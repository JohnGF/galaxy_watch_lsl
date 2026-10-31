package com.nautilus.watchstreamer

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.nautilus.watchstreamer.core.StreamHelper
import com.nautilus.watchstreamer.core.WireProtocol
import com.nautilus.watchstreamer.core.WireProtocol.Sample
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the whole streaming pipeline so it survives the screen going off.
 *
 * An Activity is paused as soon as Wear OS dims the screen (wrist down, palm cover),
 * and its sensor listeners get throttled. A health-type foreground service keeps the
 * process at foreground priority; the wake lock keeps the CPU running, and a bound
 * Wi-Fi network keeps Wear OS from dropping Wi-Fi for the Bluetooth proxy.
 */
class StreamingService : Service(), SensorEventListener {

    companion object {
        const val ACTION_STOP = "com.nautilus.watchstreamer.STOP"
        const val PREFS = "streamer"
        const val PREF_TARGET_IP = "target_ip"
        const val PREF_LOCATION = "location"
        const val DEFAULT_TARGET_IP = "192.168.137.1"  // Default Hotspot IP
        const val TARGET_PORT = 5005

        private const val TAG = "StreamingService"
        private const val CHANNEL_ID = "streaming"
        private const val NOTIFICATION_ID = 1
        private const val IMU_RATE_HZ = 50
        private const val IMU_PERIOD_US = 1_000_000 / IMU_RATE_HZ
        // 5 samples per packet: 10 packets/s instead of 50, which matters with four devices on one hotspot
        private const val IMU_BATCH = 5
        private const val HEARTBEAT_MS = 1000L
    }

    inner class LocalBinder : Binder() {
        val service: StreamingService get() = this@StreamingService
    }

    private val binder = LocalBinder()

    private lateinit var sensorManager: SensorManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var sensorThread: HandlerThread
    private lateinit var sensorHandler: Handler
    private val executor = Executors.newSingleThreadExecutor()

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Socket and network are only touched on the executor thread
    @Volatile private var socket: DatagramSocket? = null
    private var boundNetwork: Network? = null
    @Volatile private var targetAddress: InetAddress? = null
    private var targetIp = DEFAULT_TARGET_IP
    private lateinit var deviceId: String
    private var location = WireProtocol.LOCATIONS[0]

    // Shared by the sensor thread and the echo thread
    private val seq = AtomicLong()
    private var echoThread: Thread? = null

    // Only touched on the sensor thread
    private val latestGyro = FloatArray(3)
    private var imuBatch = ArrayList<Sample>(IMU_BATCH)

    @Volatile var isRunning = false
        private set
    @Volatile var packetCount = 0L
        private set
    @Volatile var droppedCount = 0L
        private set
    val isWifiBound: Boolean get() = socket != null

    private val heartbeat = object : Runnable {
        override fun run() {
            val battery = (getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val values = floatArrayOf(packetCount.toFloat(), droppedCount.toFloat(), battery.toFloat())
            send(WireProtocol.HEARTBEAT, listOf(Sample(SystemClock.elapsedRealtimeNanos(), values)))
            sensorHandler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        sensorThread = HandlerThread("SensorThread").apply { start() }
        sensorHandler = Handler(sensorThread.looper)
        // Stable per watch (and per signing key), so the bridge keeps the same LSL source_id across restarts
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        deviceId = "wear-" + androidId.takeLast(6)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (isRunning) return START_STICKY

        // Read from prefs rather than the intent so a sticky restart (null intent) resumes to the same PC
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        targetIp = prefs.getString(PREF_TARGET_IP, DEFAULT_TARGET_IP)!!
        location = prefs.getString(PREF_LOCATION, WireProtocol.LOCATIONS[0])!!
        try {
            startInForeground()
        } catch (e: Exception) {
            // Missing BODY_SENSORS or a background-start restriction; nothing can stream without it
            Log.e(TAG, "Cannot start foreground service", e)
            stopSelf()
            return START_NOT_STICKY
        }

        isRunning = true
        acquireLocks()
        requestWifi()
        executor.execute {
            targetAddress = try {
                InetAddress.getByName(targetIp)
            } catch (e: IOException) {
                Log.e(TAG, "Bad target address $targetIp", e)
                null
            }
        }
        registerSensors()
        sensorHandler.post(heartbeat)
        echoThread = Thread(::answerPings, "EchoThread").apply { start() }
        // Sticky: if the system kills the process under memory pressure, it is restarted and resumes streaming
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        sensorManager.unregisterListener(this)
        sensorHandler.removeCallbacksAndMessages(null)
        sensorThread.quitSafely()
        networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        networkCallback = null
        executor.execute {
            socket?.close()
            socket = null
        }
        executor.shutdown()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()
        super.onDestroy()
    }

    /** Called from the touch surface; serialized onto the sensor thread with the IMU updates. */
    fun onTouch(x: Float, y: Float, touched: Boolean) {
        sensorHandler.post {
            val values = floatArrayOf(x, y, if (touched) 1f else 0f)
            send(WireProtocol.TOUCH, listOf(Sample(SystemClock.elapsedRealtimeNanos(), values)))
        }
    }

    private fun startInForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Streaming", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, StreamingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("LSL streaming")
            .setContentText("$location → $targetIp:$TARGET_PORT")
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)

        // Ongoing Activity puts an icon on the watch face and in the launcher's recents,
        // so the stream stays visible and one tap reopens the app
        OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, builder)
            .setStaticIcon(android.R.drawable.ic_media_play)
            .setTouchIntent(openIntent)
            .setStatus(Status.Builder().addTemplate("Streaming LSL").build())
            .build()
            .apply(applicationContext)

        return builder.build()
    }

    // The service's lifetime bounds the locks; a timeout would silently stop long recordings
    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LSLStreamer::WakeLock").apply {
            setReferenceCounted(false)
            acquire()
        }
        // Stops Wi-Fi power save from holding packets back and sending them in bursts.
        // Android only honours low-latency mode while the screen is on; with it off the
        // device timestamps still keep sample times correct, only arrival gets burstier.
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "LSLStreamer::WifiLock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    /**
     * Wear OS turns Wi-Fi off when the screen goes off and routes traffic over the
     * phone's Bluetooth proxy. Requesting a Wi-Fi network keeps the radio up, and
     * binding the socket to it keeps packets off the proxy. On loss the request stays
     * open, so the socket is rebuilt as soon as Wi-Fi comes back.
     */
    private fun requestWifi() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)  // lab hotspots often have no uplink
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnExecutor {
                    socket?.close()
                    socket = try {
                        DatagramSocket().also {
                            network.bindSocket(it)
                            it.soTimeout = 1000  // lets the echo thread notice a stop or a socket swap
                        }
                    } catch (e: IOException) {
                        Log.w(TAG, "Cannot bind socket to Wi-Fi", e)
                        null
                    }
                    boundNetwork = network
                }
            }

            override fun onLost(network: Network) {
                runOnExecutor {
                    if (network == boundNetwork) {
                        socket?.close()
                        socket = null
                        boundNetwork = null
                    }
                }
            }
        }
        networkCallback = callback
        connectivityManager.requestNetwork(request, callback)
    }

    private fun registerSensors() {
        // maxReportLatency 0: deliver every sample now instead of letting the sensor hub batch them
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, IMU_PERIOD_US, 0, sensorHandler)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, IMU_PERIOD_US, 0, sensorHandler)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, 0, sensorHandler)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isRunning || event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                // One IMU sample per accelerometer event, paired with the latest gyroscope reading
                val accel = StreamHelper.convertIMUData(event.values)
                val values = floatArrayOf(accel[0], accel[1], accel[2], latestGyro[0], latestGyro[1], latestGyro[2])
                imuBatch.add(Sample(event.timestamp, values))
                if (imuBatch.size >= IMU_BATCH) {
                    send(WireProtocol.IMU, imuBatch, IMU_RATE_HZ)
                    imuBatch = ArrayList(IMU_BATCH)
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                StreamHelper.convertIMUData(event.values).copyInto(latestGyro, endIndex = 3)
            }
            Sensor.TYPE_HEART_RATE -> {
                val hr = StreamHelper.convertPPGData(event.values[0])
                send(WireProtocol.HR, listOf(Sample(event.timestamp, floatArrayOf(hr))))
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun send(kind: String, samples: List<Sample>, rateHz: Int = 0) {
        val payload = WireProtocol.formatPacket(deviceId, location, seq.getAndIncrement(), kind, samples, rateHz)
        runOnExecutor {
            val s = socket
            val address = targetAddress
            if (s == null || address == null) {
                droppedCount++
                return@runOnExecutor
            }
            try {
                s.send(StreamHelper.createDatagramPacket(payload, address, TARGET_PORT))
                packetCount++
            } catch (e: IOException) {
                droppedCount++
            }
        }
    }

    /**
     * Answers the bridge's clock-sync pings (W2P|<id>) with ECHO(t2 = arrival, turnaround = t3 - t2).
     * Replies straight from this thread, not through the executor, so no queueing sits between the
     * timestamps and the wire; the bridge fits clock offset and drift from these exchanges.
     */
    private fun answerPings() {
        val buffer = ByteArray(64)
        while (isRunning) {
            val s = socket
            if (s == null) {
                SystemClock.sleep(200)  // waiting for Wi-Fi
                continue
            }
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                s.receive(packet)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: IOException) {
                continue  // socket closed by a network change or by onDestroy
            }
            val t2 = SystemClock.elapsedRealtimeNanos()
            val pingId = WireProtocol.parsePing(String(packet.data, 0, packet.length)) ?: continue
            val t3 = SystemClock.elapsedRealtimeNanos()
            val values = floatArrayOf(pingId.toFloat(), (t3 - t2) / 1000f)
            val payload = WireProtocol.formatPacket(deviceId, location, seq.getAndIncrement(), WireProtocol.ECHO, listOf(Sample(t2, values)))
            try {
                s.send(StreamHelper.createDatagramPacket(payload, packet.address, packet.port))
            } catch (e: IOException) {
                droppedCount++
            }
        }
    }

    private fun runOnExecutor(task: () -> Unit) {
        try {
            executor.execute(task)
        } catch (e: RejectedExecutionException) {
            // Late sensor or network callback after onDestroy; nothing left to send to
        }
    }
}
