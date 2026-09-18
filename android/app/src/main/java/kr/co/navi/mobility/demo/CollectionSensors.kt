package kr.co.navi.mobility.demo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.*
import android.location.*
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/** GNSS/ambient readings are raw observations, never substituted for measured AR alignment. */
class CollectionSensors(context: Context) : LocationListener, SensorEventListener {
    private val context = context.applicationContext
    private val locations = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    @Volatile private var location: Location? = null
    @Volatile private var light: Pair<Long, Float>? = null
    @Volatile private var rotation: Pair<Long, FloatArray>? = null
    private var locationEnabled = false

    fun start() {
        val allowed = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (allowed) for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            runCatching {
                if (locations.isProviderEnabled(provider)) {
                    locations.requestLocationUpdates(provider, 1000L, 0f, this, Looper.getMainLooper())
                    locationEnabled = true
                }
            }
        }
        listOf(Sensor.TYPE_LIGHT, Sensor.TYPE_ROTATION_VECTOR).forEach { type ->
            sensors.getDefaultSensor(type)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        }
    }

    fun snapshot(): JSONObject {
        val now = SystemClock.elapsedRealtimeNanos()
        val result = JSONObject().put("captured_elapsed_realtime_ns", now)
            .put("location_updates_available", locationEnabled)
            .put("location_role", "raw_device_location_not_AR_calibration")
        location?.let { l ->
            @Suppress("DEPRECATION") val mock = if (Build.VERSION.SDK_INT >= 31) l.isMock else l.isFromMockProvider
            result.put("location", JSONObject().put("lat", l.latitude).put("lon", l.longitude)
                .put("accuracy_m", if (l.hasAccuracy()) l.accuracy else JSONObject.NULL)
                .put("observed_at_epoch_ms", l.time).put("elapsed_realtime_ns", l.elapsedRealtimeNanos)
                .put("age_ms", (now-l.elapsedRealtimeNanos)/1_000_000).put("provider", l.provider)
                .put("mock", mock))
        } ?: result.put("location", JSONObject.NULL)
        light?.let { (stamp, lux) -> result.put("light", JSONObject().put("lux", lux).put("timestamp_ns", stamp).put("age_ms", (now-stamp)/1_000_000)) }
            ?: result.put("light", JSONObject.NULL)
        rotation?.let { (stamp, values) -> result.put("rotation_vector", JSONObject().put("values", JSONArray(values.toList())).put("timestamp_ns", stamp)) }
            ?: result.put("rotation_vector", JSONObject.NULL)
        return result
    }
    override fun onLocationChanged(value: Location) { location = Location(value) }
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> light = event.timestamp to event.values[0]
            Sensor.TYPE_ROTATION_VECTOR -> rotation = event.timestamp to event.values.copyOf()
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    @Deprecated("Legacy Android callback") override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
    fun stop() { runCatching { locations.removeUpdates(this) }; sensors.unregisterListener(this) }
}
