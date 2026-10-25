package com.nautilus.watchstreamer.core

/**
 * W2 packet format, shared by every wearable that streams to the nautilus_bci bridge
 * (spec: nautilus_bci docs/guides/wearable_protocol.md):
 *
 *     W2|<device_id>|<location>|<seq>|<kind>[@<rate_hz>]|<t_ns>,<v1>,...;<t_ns>,<v1>,...
 *
 * The bridge gives each device_id its own LSL streams and clock mapping, so several
 * watches (or watches plus ESP32 boards) can stream at once.
 */
object WireProtocol {
    const val IMU = "IMU"
    const val HR = "HR"
    const val TOUCH = "TOUCH"
    const val HEARTBEAT = "HB"

    val LOCATIONS = listOf("L_wrist", "R_wrist", "L_ankle", "R_ankle")

    class Sample(val timestampNs: Long, val values: FloatArray)

    fun formatPacket(
        deviceId: String,
        location: String,
        seq: Long,
        kind: String,
        samples: List<Sample>,
        rateHz: Int = 0
    ): String {
        val sb = StringBuilder("W2|").append(deviceId).append('|').append(location).append('|')
            .append(seq and 0xFFFFFFFFL).append('|').append(kind)
        if (rateHz > 0) sb.append('@').append(rateHz)
        sb.append('|')
        samples.forEachIndexed { i, sample ->
            if (i > 0) sb.append(';')
            sb.append(sample.timestampNs)
            sample.values.forEach { sb.append(',').append(it) }
        }
        return sb.toString()
    }
}
