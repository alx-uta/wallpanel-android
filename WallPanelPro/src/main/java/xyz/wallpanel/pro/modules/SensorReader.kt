/*
 * Copyright (c) 2022 WallPanel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xyz.wallpanel.pro.modules

import android.content.Context
import android.content.Context.SENSOR_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import xyz.wallpanel.pro.R
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.util.*
import javax.inject.Inject

data class SensorInfo(val sensorType: String?, val unit: String?, val deviceClass: String?, val displayName: String?)

// Simple wrapper for system sensors
private data class SystemSensor(val type: Int)

class SensorReader @Inject
constructor(private val context: Context){

    private val mSensorManager: SensorManager?
    private val mSensorList = ArrayList<Any>()
    private val sensorHandler = Handler(Looper.getMainLooper())
    private var updateFrequencyMilliSeconds: Int = 0
    private var callback: SensorCallback? = null
    private var sensorsPublished: Boolean = false
    private var lightSensorEvent: SensorEvent? = null
    // Some devices' SELinux policy denies untrusted apps read access to /proc/stat.
    // Once that's confirmed, stop retrying every cycle instead of failing forever.
    private var cpuUsageUnavailable: Boolean = false

    private val sensorUpdateRunnable = object : Runnable {
        override fun run() {
            if (updateFrequencyMilliSeconds > 0) {
                getBatteryReading()
                // Run CPU and memory reading on background thread to avoid StrictMode violations
                Thread {
                    if (!cpuUsageUnavailable) {
                        getCpuUsage()
                    }
                    getMemoryUsage()
                }.start()
                sensorHandler.postDelayed(this, updateFrequencyMilliSeconds.toLong())
                sensorsPublished = false
            }
        }
    }

    init {
        mSensorManager = context.getSystemService(SENSOR_SERVICE) as SensorManager
        for (s in mSensorManager.getSensorList(Sensor.TYPE_ALL)) {
            if (getSensorName(s.type) != null)
                mSensorList.add(s)
        }
        // Add system sensors (non-hardware)
        mSensorList.add(SystemSensor(TYPE_BATTERY))
        mSensorList.add(SystemSensor(TYPE_CPU))
        mSensorList.add(SystemSensor(TYPE_MEMORY))
    }

    fun getSensors(): List<SensorInfo> {
        return mSensorList.map { s -> 
            val type = when (s) {
                is Sensor -> s.type
                is SystemSensor -> s.type
                else -> -1
            }
            SensorInfo(getSensorName(type), getSensorUnit(type), getSensorDeviceClass(type), getSensorDisplayName(type))
        }
    }

    fun startReadings(freqSeconds: Int, callback: SensorCallback) {
        this.callback = callback
        if (freqSeconds >= 0) {
            updateFrequencyMilliSeconds = 1000 * freqSeconds
            sensorHandler.removeCallbacksAndMessages(null)
            sensorHandler.postDelayed(sensorUpdateRunnable, updateFrequencyMilliSeconds.toLong())
            startSensorReadings()
        }
    }

    fun refreshSensors() {
        sensorHandler.removeCallbacksAndMessages(null)
        sensorHandler.post(sensorUpdateRunnable)
        stopSensorReading()
        startSensorReadings()
    }

    fun stopReadings() {
        sensorHandler.removeCallbacksAndMessages(null)
        updateFrequencyMilliSeconds = 0
        stopSensorReading()
    }

    private fun publishSensorData(sensorName: String?, sensorData: JSONObject) {
        if(sensorName != null) {
            callback?.publishSensorData(sensorName, sensorData)
        }
    }

    private fun getSensorName(sensorType: Int): String? {
        when (sensorType) {
            Sensor.TYPE_AMBIENT_TEMPERATURE -> return TEMPERATURE
            Sensor.TYPE_LIGHT -> return LIGHT
            Sensor.TYPE_MAGNETIC_FIELD -> return MAGNETIC_FIELD
            Sensor.TYPE_PRESSURE -> return PRESSURE
            Sensor.TYPE_RELATIVE_HUMIDITY -> return HUMIDITY
            TYPE_BATTERY -> return BATTERY
            TYPE_CPU -> return CPU_USAGE
            TYPE_MEMORY -> return MEMORY_USAGE
        }
        return null
    }

    private fun getSensorDisplayName(sensorType: Int): String? {
        when (sensorType) {
            Sensor.TYPE_AMBIENT_TEMPERATURE -> return context.getString(R.string.mqtt_sensor_temperature)
            Sensor.TYPE_LIGHT -> return context.getString(R.string.mqtt_sensor_light)
            Sensor.TYPE_MAGNETIC_FIELD -> return context.getString(R.string.mqtt_sensor_magnetic_field)
            Sensor.TYPE_PRESSURE -> return context.getString(R.string.mqtt_sensor_pressure)
            Sensor.TYPE_RELATIVE_HUMIDITY -> return context.getString(R.string.mqtt_sensor_humidity)
            TYPE_BATTERY -> return context.getString(R.string.mqtt_sensor_battery_level)
            TYPE_CPU -> return context.getString(R.string.mqtt_sensor_cpu_usage)
            TYPE_MEMORY -> return context.getString(R.string.mqtt_sensor_memory_usage)
        }
        return null
    }

    private fun getSensorUnit(sensorType: Int): String? {
        when (sensorType) {
            Sensor.TYPE_AMBIENT_TEMPERATURE -> return UNIT_C
            Sensor.TYPE_LIGHT -> return UNIT_LX
            Sensor.TYPE_MAGNETIC_FIELD -> return UNIT_UT
            Sensor.TYPE_PRESSURE -> return UNIT_HPA
            Sensor.TYPE_RELATIVE_HUMIDITY -> return UNIT_PERCENTAGE
            TYPE_BATTERY -> return UNIT_PERCENTAGE
            TYPE_CPU -> return UNIT_PERCENTAGE
            TYPE_MEMORY -> return UNIT_MB
        }
        return null
    }

    /**
     * Map to Home Assistant device class for sensors
     */
    private fun getSensorDeviceClass(sensorType: Int): String? {
        when(sensorType) {
            Sensor.TYPE_AMBIENT_TEMPERATURE -> return "temperature"
            Sensor.TYPE_LIGHT -> return "illuminance"
            Sensor.TYPE_PRESSURE -> return "pressure"
            Sensor.TYPE_RELATIVE_HUMIDITY -> return "humidity"
        }
        return null
    }

    /**
     * Start all sensor readings.
     */
    private fun startSensorReadings() {
        if(mSensorManager != null) {
            for (item in mSensorList) {
                if (item is Sensor) {
                    mSensorManager.registerListener(sensorListener, item, 1000)
                }
            }
        }
    }

    /**
     * Stop all sensor readings.
     */
    private fun stopSensorReading() {
        for (item in mSensorList) {
            if (item is Sensor) {
                mSensorManager?.unregisterListener(sensorListener, item)
            }
        }
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if(event != null && !sensorsPublished) {
                var data = JSONObject()
                if(event.sensor.type == Sensor.TYPE_LIGHT) {
                    lightSensorEvent = event
                }
                if(lightSensorEvent != null) {
                    data.put(VALUE, lightSensorEvent!!.values[0])
                    data.put(UNIT, getSensorUnit(lightSensorEvent!!.sensor.type))
                    data.put(ID, lightSensorEvent!!.sensor.name)
                    publishSensorData(getSensorName(lightSensorEvent!!.sensor.type), data)
                }
                data = JSONObject()
                data.put(VALUE, event.values[0])
                data.put(UNIT, getSensorUnit(event.sensor.type))
                data.put(ID, event.sensor.name)
                publishSensorData(getSensorName(event.sensor.type), data)
                sensorsPublished = true
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        }
    }

    // TODO let's move this to its own setting
    private fun getBatteryReading() {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, intentFilter)
        val batteryStatusIntExtra = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = batteryStatusIntExtra == BatteryManager.BATTERY_STATUS_CHARGING || batteryStatusIntExtra == BatteryManager.BATTERY_STATUS_FULL
        val chargePlug = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val usbCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_USB
        val acCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_AC
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val data = JSONObject()
        try {
            data.put(VALUE, level)
            data.put(UNIT, UNIT_PERCENTAGE)
            data.put(CHARGING, isCharging)
            data.put(AC_PLUGGED, acCharge)
            data.put(USB_PLUGGED, usbCharge)
        } catch (ex: JSONException) {
            ex.printStackTrace()
        }

        publishSensorData(BATTERY, data)
    }
    
    private fun getCpuUsage() {
        try {
            // Read system-wide CPU usage from /proc/stat
            val reader = java.io.BufferedReader(java.io.FileReader("/proc/stat"))
            val line = reader.readLine()
            reader.close()
            
            val tokens = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.size >= 9 && tokens[0] == "cpu") {
                // Format: cpu user nice system idle iowait irq softirq steal guest guest_nice
                val user1 = tokens[1].toLongOrNull() ?: 0L
                val nice1 = tokens[2].toLongOrNull() ?: 0L
                val system1 = tokens[3].toLongOrNull() ?: 0L
                val idle1 = tokens[4].toLongOrNull() ?: 0L
                val iowait1 = tokens[5].toLongOrNull() ?: 0L
                val irq1 = tokens[6].toLongOrNull() ?: 0L
                val softirq1 = tokens[7].toLongOrNull() ?: 0L
                
                Thread.sleep(1000)
                
                val reader2 = java.io.BufferedReader(java.io.FileReader("/proc/stat"))
                val line2 = reader2.readLine()
                reader2.close()
                
                val tokens2 = line2.split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (tokens2.size >= 9 && tokens2[0] == "cpu") {
                    val user2 = tokens2[1].toLongOrNull() ?: 0L
                    val nice2 = tokens2[2].toLongOrNull() ?: 0L
                    val system2 = tokens2[3].toLongOrNull() ?: 0L
                    val idle2 = tokens2[4].toLongOrNull() ?: 0L
                    val iowait2 = tokens2[5].toLongOrNull() ?: 0L
                    val irq2 = tokens2[6].toLongOrNull() ?: 0L
                    val softirq2 = tokens2[7].toLongOrNull() ?: 0L
                    
                    val idle = (idle2 + iowait2) - (idle1 + iowait1)
                    val nonIdle = (user2 + nice2 + system2 + irq2 + softirq2) - (user1 + nice1 + system1 + irq1 + softirq1)
                    val total = idle + nonIdle
                    
                    val cpuUsage = if (total > 0) {
                        ((nonIdle.toDouble() / total.toDouble()) * 100.0).toInt()
                    } else 0
                    
                    val data = JSONObject()
                    data.put(VALUE, cpuUsage)
                    data.put(UNIT, UNIT_PERCENTAGE)
                    data.put(ID, "system_cpu")
                    publishSensorData(CPU_USAGE, data)
                }
            }
        } catch (e: java.io.FileNotFoundException) {
            cpuUsageUnavailable = true
            Timber.w("CPU usage unavailable on this device (cannot read /proc/stat): ${e.message}")
        } catch (e: Exception) {
            Timber.e(e, "Error reading CPU usage")
        }
    }
    
    private fun getMemoryUsage() {
        try {
            // Read memory info from /proc/meminfo
            val reader = java.io.BufferedReader(java.io.FileReader("/proc/meminfo"))
            var totalMem = 0L
            var availMem = 0L
            
            reader.useLines { lines ->
                lines.forEach { line ->
                    when {
                        line.startsWith("MemTotal:") -> {
                            totalMem = line.split(Regex("\\s+"))[1].toLongOrNull() ?: 0L
                        }
                        line.startsWith("MemAvailable:") -> {
                            availMem = line.split(Regex("\\s+"))[1].toLongOrNull() ?: 0L
                        }
                    }
                    if (totalMem > 0 && availMem > 0) return@forEach
                }
            }
            
            val totalSystemMemoryMB = totalMem / 1024
            val availableSystemMemoryMB = availMem / 1024
            val usedSystemMemoryMB = totalSystemMemoryMB - availableSystemMemoryMB
            
            val usedPercentage = if (totalSystemMemoryMB > 0) {
                ((usedSystemMemoryMB.toDouble() / totalSystemMemoryMB.toDouble()) * 100.0).toInt()
            } else 0
            
            val data = JSONObject()
            data.put(VALUE, usedSystemMemoryMB)
            data.put(UNIT, UNIT_MB)
            data.put(ID, "system_memory")
            data.put("total", totalSystemMemoryMB)
            data.put("available", availableSystemMemoryMB)
            data.put("percentage", usedPercentage)
            publishSensorData(MEMORY_USAGE, data)
        } catch (e: Exception) {
            Timber.e(e, "Error reading memory usage")
        }
    }


    companion object {
        const val TYPE_BATTERY: Int = -100
        const val TYPE_CPU: Int = -101
        const val TYPE_MEMORY: Int = -102
        
        const val BATTERY: String = "battery"
        const val CHARGING: String = "charging"
        const val AC_PLUGGED: String = "acPlugged"
        const val USB_PLUGGED: String = "usbPlugged"
        const val HUMIDITY: String = "humidity"
        const val LIGHT: String = "light"
        const val PRESSURE: String = "pressure"
        const val TEMPERATURE: String = "temperature"
        const val MAGNETIC_FIELD: String = "magneticField"
        const val CPU_USAGE: String = "cpuUsage"
        const val MEMORY_USAGE: String = "memoryUsage"
        const val UNIT_C: String = "°C"
        const val UNIT_PERCENTAGE: String = "%"
        const val UNIT_HPA: String = "hPa"
        const val UNIT_UT: String = "uT"
        const val UNIT_LX: String = "lx"
        const val UNIT_MB: String = "MB"
        const val VALUE = "value"
        const val UNIT = "unit"
        const val ID = "id"
    }
}