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
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.StrictMode
import android.os.SystemClock
import xyz.wallpanel.pro.R
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Describes one published sensor well enough to build its Home Assistant discovery
 * config without the discovery code needing to know what kind of sensor it is.
 *
 * [numeric] decides whether the discovered entity casts its value to a float; string
 * sensors such as the IP address would end up as `unknown` in Home Assistant otherwise.
 * [diagnostic] puts the entity in Home Assistant's diagnostic section rather than the
 * main controls, which is where device facts like the app version belong.
 */
data class SensorInfo(
    val sensorType: String?,
    val unit: String?,
    val deviceClass: String?,
    val displayName: String?,
    val stateClass: String? = null,
    val numeric: Boolean = true,
    val diagnostic: Boolean = false,
)

// Simple wrapper for system sensors
private data class SystemSensor(val type: Int)

class SensorReader @Inject
constructor(private val context: Context){

    private val mSensorManager: SensorManager?
    private val mSensorList = ArrayList<Any>()
    private val sensorHandler = Handler(Looper.getMainLooper())
    private var updateFrequencyMilliSeconds: Int = 0
    private var callback: SensorCallback? = null
    // The hardware sensor types already reported this cycle. Sensor events are delivered
    // on the main looper, which is also where the cycle clears this.
    private val publishedSensorTypes = mutableSetOf<Int>()
    private var lightSensorEvent: SensorEvent? = null
    // Some devices' SELinux policy denies untrusted apps read access to /proc/stat.
    // Once that's confirmed, stop retrying every cycle instead of failing forever. Written
    // from the reading thread and read from the main thread.
    @Volatile
    private var cpuUsageUnavailable: Boolean = false
    // The values that never change while the process runs are published when readings start
    // and again on every refresh, which is what the service asks for on a broker reconnect.
    @Volatile
    private var deviceInfoPublished: Boolean = false
    @Volatile
    private var backgroundReadings: Thread? = null

    private val sensorUpdateRunnable = object : Runnable {
        override fun run() {
            if (updateFrequencyMilliSeconds > 0) {
                readImmediateSensors()
                startBackgroundReadings()
                sensorHandler.postDelayed(this, updateFrequencyMilliSeconds.toLong())
                publishedSensorTypes.clear()
            }
        }
    }

    /**
     * The readings that are cheap enough to take on the calling thread. The group is guarded
     * as a whole: this runs on the main thread, and a throw here would take the application
     * down rather than skip a cycle.
     */
    private fun readImmediateSensors() {
        try {
            getBatteryReading()
            getUptimeReading()
            if (!deviceInfoPublished) {
                getDeviceInfoReadings()
                deviceInfoPublished = true
            }
        } catch (e: Exception) {
            Timber.e(e, "Error taking the sensor readings")
        }
    }

    /**
     * The readings that touch disk, /proc or the network stack, kept off the calling thread
     * to avoid StrictMode violations. One cycle runs at a time: the CPU reading takes a
     * second on its own, and a short publish frequency would otherwise stack threads up.
     */
    private fun startBackgroundReadings() {
        if (backgroundReadings?.isAlive == true) {
            Timber.d("The previous sensor readings are still running, skipping this cycle")
            return
        }
        backgroundReadings = Thread {
            try {
                if (!cpuUsageUnavailable) {
                    getCpuUsage()
                }
                getMemoryUsage()
                getStorageReading()
                getNetworkReadings()
            } catch (e: Exception) {
                Timber.e(e, "Error taking the background sensor readings")
            }
        }.apply { start() }
    }

    init {
        mSensorManager = context.getSystemService(SENSOR_SERVICE) as SensorManager
        for (s in mSensorManager.getSensorList(Sensor.TYPE_ALL)) {
            if (getSensorName(s.type) != null)
                mSensorList.add(s)
        }
        // Add system sensors (non-hardware). Every reading this device could report is
        // registered here whether or not a value is available at this moment, so that a
        // kiosk which boots faster than the Wi-Fi associates keeps its signal reading.
        mSensorList.add(SystemSensor(TYPE_BATTERY))
        mSensorList.add(SystemSensor(TYPE_MEMORY))
        mSensorList.add(SystemSensor(TYPE_CPU))
        mSensorList.add(SystemSensor(TYPE_STORAGE))
        mSensorList.add(SystemSensor(TYPE_UPTIME))
        mSensorList.add(SystemSensor(TYPE_IP_ADDRESS))
        mSensorList.add(SystemSensor(TYPE_APP_VERSION))
        mSensorList.add(SystemSensor(TYPE_ANDROID_VERSION))
        mSensorList.add(SystemSensor(TYPE_WIFI_SIGNAL))
        cpuUsageUnavailable = !canReadCpuStat()
    }

    /**
     * A single probe of the file the CPU reading parses. Reading procfs is cheap enough to
     * do once while the reader is built, and a device whose policy denies the file will
     * deny it for as long as the application runs.
     */
    private fun canReadCpuStat(): Boolean {
        val policy = StrictMode.allowThreadDiskReads()
        return try {
            java.io.File(PROC_STAT).bufferedReader().use { it.readLine() } != null
        } catch (e: Exception) {
            Timber.w("CPU usage unavailable on this device (cannot read $PROC_STAT): ${e.message}")
            false
        } finally {
            StrictMode.setThreadPolicy(policy)
        }
    }

    /**
     * Every reading this device is able to report. An entry is left out only where the
     * device can never produce a value -- no Wi-Fi radio, or a kernel that will not hand
     * over /proc/stat -- so a reading that is merely unavailable right now keeps its Home
     * Assistant entity instead of having it taken down and rebuilt.
     */
    fun getSensors(): List<SensorInfo> {
        return mSensorList.mapNotNull { s ->
            val type = when (s) {
                is Sensor -> s.type
                is SystemSensor -> s.type
                else -> -1
            }
            if (!canReport(type)) {
                return@mapNotNull null
            }
            SensorInfo(
                sensorType = getSensorName(type),
                unit = getSensorUnit(type),
                deviceClass = getSensorDeviceClass(type),
                displayName = getSensorDisplayName(type),
                stateClass = getSensorStateClass(type),
                numeric = isNumericSensor(type),
                diagnostic = isDiagnosticSensor(type),
            )
        }
    }

    /**
     * Whether the device could produce this reading at all. This is a question about the
     * hardware and the platform, not about the state of the network right now.
     */
    private fun canReport(sensorType: Int): Boolean {
        return when (sensorType) {
            TYPE_CPU -> !cpuUsageUnavailable
            TYPE_WIFI_SIGNAL -> hasWifiHardware()
            else -> true
        }
    }

    private fun hasWifiHardware(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)
    }

    fun startReadings(freqSeconds: Int, callback: SensorCallback) {
        this.callback = callback
        if (freqSeconds >= 0) {
            updateFrequencyMilliSeconds = 1000 * freqSeconds
            deviceInfoPublished = false
            sensorHandler.removeCallbacksAndMessages(null)
            sensorHandler.postDelayed(sensorUpdateRunnable, updateFrequencyMilliSeconds.toLong())
            startSensorReadings()
        }
    }

    fun refreshSensors() {
        // The service refreshes when it reconnects to the broker, which is the moment the
        // values that never change are worth sending again.
        deviceInfoPublished = false
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
            TYPE_STORAGE -> return STORAGE_FREE
            TYPE_UPTIME -> return UPTIME
            TYPE_WIFI_SIGNAL -> return WIFI_SIGNAL
            TYPE_IP_ADDRESS -> return IP_ADDRESS
            TYPE_APP_VERSION -> return APP_VERSION
            TYPE_ANDROID_VERSION -> return ANDROID_VERSION
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
            TYPE_STORAGE -> return context.getString(R.string.mqtt_sensor_storage_free)
            TYPE_UPTIME -> return context.getString(R.string.mqtt_sensor_uptime)
            TYPE_WIFI_SIGNAL -> return context.getString(R.string.mqtt_sensor_wifi_signal)
            TYPE_IP_ADDRESS -> return context.getString(R.string.mqtt_sensor_ip_address)
            TYPE_APP_VERSION -> return context.getString(R.string.mqtt_sensor_app_version)
            TYPE_ANDROID_VERSION -> return context.getString(R.string.mqtt_sensor_android_version)
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
            TYPE_STORAGE -> return UNIT_GB
            TYPE_UPTIME -> return UNIT_SECONDS
            TYPE_WIFI_SIGNAL -> return UNIT_DBM
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
            TYPE_BATTERY -> return "battery"
            TYPE_STORAGE -> return "data_size"
            TYPE_UPTIME -> return "duration"
            TYPE_WIFI_SIGNAL -> return "signal_strength"
        }
        return null
    }

    /**
     * Home Assistant only keeps long term statistics for sensors that declare a state
     * class, so every numeric reading is marked as a measurement.
     */
    private fun getSensorStateClass(sensorType: Int): String? {
        return when {
            // Uptime only ever climbs, back to zero on a reboot, which is the shape Home
            // Assistant's long term statistics keep for a counter rather than a reading.
            sensorType == TYPE_UPTIME -> STATE_CLASS_TOTAL_INCREASING
            isNumericSensor(sensorType) -> STATE_CLASS_MEASUREMENT
            else -> null
        }
    }

    private fun isNumericSensor(sensorType: Int): Boolean {
        return when (sensorType) {
            TYPE_IP_ADDRESS, TYPE_APP_VERSION, TYPE_ANDROID_VERSION -> false
            else -> true
        }
    }

    private fun isDiagnosticSensor(sensorType: Int): Boolean {
        return when (sensorType) {
            TYPE_STORAGE, TYPE_UPTIME, TYPE_WIFI_SIGNAL,
            TYPE_IP_ADDRESS, TYPE_APP_VERSION, TYPE_ANDROID_VERSION -> true
            else -> false
        }
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
            if (event == null) {
                return
            }
            if (event.sensor.type == Sensor.TYPE_LIGHT) {
                lightSensorEvent = event
            }
            // The light sensor reports only when the level moves, which can be less often
            // than a reading cycle, so its last value rides along with whatever else fires.
            lightSensorEvent?.let { light ->
                if (publishedSensorTypes.add(Sensor.TYPE_LIGHT)) {
                    publishSensorEvent(light)
                }
            }
            // One reading per sensor per cycle. A single flag for all of them meant the
            // first sensor to fire was the only one Home Assistant ever heard from, leaving
            // the rest of the advertised entities without a state.
            if (publishedSensorTypes.add(event.sensor.type)) {
                publishSensorEvent(event)
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        }
    }

    private fun publishSensorEvent(event: SensorEvent) {
        val data = JSONObject()
        data.put(VALUE, event.values[0])
        data.put(UNIT, getSensorUnit(event.sensor.type))
        data.put(ID, event.sensor.name)
        publishSensorData(getSensorName(event.sensor.type), data)
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
            // System-wide CPU usage, from two samples of /proc/stat a second apart.
            val tokens = readCpuStat() ?: return
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

                val tokens2 = readCpuStat() ?: return
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
        } catch (e: java.io.IOException) {
            // Covers both a policy that hides the file and one that opens it but refuses
            // the read. The file is asked again rather than the sensor being written off on
            // one failed read: dropping it from the reading list removes the entity from
            // Home Assistant, and its history goes with it.
            cpuUsageUnavailable = !canReadCpuStat()
            Timber.w("Could not read $PROC_STAT (sensor dropped: $cpuUsageUnavailable): ${e.message}")
        } catch (e: Exception) {
            Timber.e(e, "Error reading CPU usage")
        }
    }

    /**
     * The aggregate `cpu` line of /proc/stat, split on whitespace.
     */
    private fun readCpuStat(): List<String>? {
        val line = java.io.File(PROC_STAT).bufferedReader().use { it.readLine() } ?: return null
        return line.split(Regex("\\s+")).filter { it.isNotEmpty() }
    }
    
    private fun getMemoryUsage() {
        try {
            val meminfo = readMemInfo()
            val totalMem = meminfo["MemTotal"] ?: 0L
            // MemAvailable only exists on kernels from 3.14 onwards. Older devices, which
            // includes Android 5 era hardware, get the approximation that predates it --
            // without a fallback they report every byte of memory as in use.
            val availMem = meminfo["MemAvailable"] ?: ((meminfo["MemFree"] ?: 0L) +
                    (meminfo["Buffers"] ?: 0L) + (meminfo["Cached"] ?: 0L))

            val totalSystemMemoryMB = totalMem / 1024
            val availableSystemMemoryMB = (availMem / 1024).coerceIn(0L, totalSystemMemoryMB)
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

    /**
     * /proc/meminfo as a map of field name to kilobytes. Reading the whole file rather than
     * the two fields the reading wants keeps the fallback for kernels without MemAvailable
     * to a lookup.
     */
    private fun readMemInfo(): Map<String, Long> {
        val values = HashMap<String, Long>()
        java.io.File(PROC_MEMINFO).bufferedReader().use { reader ->
            reader.forEachLine { line ->
                val parts = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (parts.size >= 2) {
                    parts[1].toLongOrNull()?.let { values[parts[0].trimEnd(':')] = it }
                }
            }
        }
        return values
    }


    /**
     * Free space on the partition the application's own data lives on, which is the one
     * that actually runs out first on a kiosk that has been caching pages for months.
     */
    private fun getStorageReading() {
        try {
            val stat = StatFs(Environment.getDataDirectory().absolutePath)
            val blockSize = stat.blockSizeLong
            val freeBytes = stat.availableBlocksLong * blockSize
            val totalBytes = stat.blockCountLong * blockSize
            val data = JSONObject()
            data.put(VALUE, bytesToGigabytes(freeBytes))
            data.put(UNIT, UNIT_GB)
            data.put(ID, "internal_storage")
            data.put("total", bytesToGigabytes(totalBytes))
            data.put("freeBytes", freeBytes)
            data.put("totalBytes", totalBytes)
            publishSensorData(STORAGE_FREE, data)
        } catch (e: Exception) {
            Timber.e(e, "Error reading free storage")
        }
    }

    /**
     * Decimal gigabytes. Home Assistant's `data_size` device class reads GB as a thousand
     * million bytes when it converts between units, and Android's own storage screen counts
     * the same way, so the reading agrees with both. The raw byte counts are published
     * alongside it for anyone who wants the exact figure.
     */
    private fun bytesToGigabytes(bytes: Long): Double {
        return Math.round(bytes / 1000.0 / 1000.0 / 1000.0 * 100.0) / 100.0
    }

    /**
     * Time since the device last booted. Deep sleep counts, so this tracks the device
     * rather than the application and an unexpected reset is a real reboot.
     */
    private fun getUptimeReading() {
        try {
            val uptimeMillis = SystemClock.elapsedRealtime()
            val seconds = TimeUnit.MILLISECONDS.toSeconds(uptimeMillis)
            val data = JSONObject()
            data.put(VALUE, seconds)
            data.put(UNIT, UNIT_SECONDS)
            data.put(ID, "device_uptime")
            // A breakdown rather than three totals: "days":16 next to a total "hours":397
            // reads as 16 days and 397 hours to anyone templating on it.
            data.put("days", TimeUnit.SECONDS.toDays(seconds))
            data.put("hours", TimeUnit.SECONDS.toHours(seconds) % 24)
            data.put("minutes", TimeUnit.SECONDS.toMinutes(seconds) % 60)
            data.put("formatted", formatUptime(seconds))
            publishSensorData(UPTIME, data)
        } catch (e: Exception) {
            Timber.e(e, "Error reading the device uptime")
        }
    }

    private fun formatUptime(totalSeconds: Long): String {
        val days = TimeUnit.SECONDS.toDays(totalSeconds)
        val hours = TimeUnit.SECONDS.toHours(totalSeconds) % 24
        val minutes = TimeUnit.SECONDS.toMinutes(totalSeconds) % 60
        return if (days > 0) {
            String.format(Locale.US, "%dd %dh %dm", days, hours, minutes)
        } else {
            String.format(Locale.US, "%dh %dm", hours, minutes)
        }
    }

    /**
     * Facts about the install that never change while the process is alive. Published once
     * per run of the reading cycle rather than on every pass, and again when the service
     * refreshes the sensors on a broker reconnect. See [DEVICE_INFO_SENSORS].
     */
    private fun getDeviceInfoReadings() {
        try {
            val appData = JSONObject()
            appData.put(VALUE, applicationVersion())
            appData.put(ID, context.packageName)
            publishSensorData(APP_VERSION, appData)
        } catch (e: Exception) {
            Timber.e(e, "Error reading the application version")
        }

        try {
            val androidData = JSONObject()
            androidData.put(VALUE, Build.VERSION.RELEASE ?: "")
            androidData.put(ID, "android_version")
            androidData.put("sdk", Build.VERSION.SDK_INT)
            androidData.put("manufacturer", Build.MANUFACTURER)
            androidData.put("model", Build.MODEL)
            publishSensorData(ANDROID_VERSION, androidData)
        } catch (e: Exception) {
            Timber.e(e, "Error reading the Android version")
        }
    }

    private fun applicationVersion(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: PackageManager.NameNotFoundException) {
            Timber.e(e, "Could not read the application version")
            ""
        }
    }

    /**
     * The network readings. Each one publishes nothing at all where the value can't be read,
     * which is what an interface that hasn't come up yet looks like -- the entity stays and
     * starts reporting once there is something to report.
     */
    private fun getNetworkReadings() {
        try {
            readIpAddress()?.let { address ->
                val data = JSONObject()
                data.put(VALUE, address)
                data.put(ID, "ip_address")
                publishSensorData(IP_ADDRESS, data)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error publishing the device IP address")
        }

        try {
            readWifiRssi()?.let { rssi ->
                val data = JSONObject()
                data.put(VALUE, rssi)
                data.put(UNIT, UNIT_DBM)
                data.put(ID, "wifi_signal")
                data.put("percentage", rssiToPercentage(rssi))
                publishSensorData(WIFI_SIGNAL, data)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error publishing the Wi-Fi signal strength")
        }
    }

    /**
     * The IPv4 address a user could reach this device on. Reading the interface list rather
     * than the Wi-Fi connection info covers the wired kiosk devices too, and needs no
     * permission beyond the ones the application already holds.
     */
    private fun readIpAddress(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            interfaces.toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { networkInterface ->
                    networkInterface.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        // A link-local address is what an interface gives itself when DHCP
                        // fails, and nothing on the network can reach it.
                        .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                        .map { networkInterface to it }
                }
                .minByOrNull { (networkInterface, address) -> addressRank(networkInterface, address) }
                ?.second
                ?.hostAddress
        } catch (e: Exception) {
            Timber.e(e, "Error reading the device IP address")
            null
        }
    }

    /**
     * Ranks candidate addresses, lowest first. A tablet can hold addresses on a VPN tunnel
     * or a mobile interface at the same time as the local network, and the address worth
     * reporting is the one the local network can open the built in web server on.
     */
    private fun addressRank(networkInterface: NetworkInterface, address: Inet4Address): Int {
        val localNetwork = networkInterface.name.orEmpty().let {
            it.startsWith("wlan") || it.startsWith("eth")
        }
        // The interface decides before the address range does: a tunnel holding a private
        // address is still not where the local network reaches this device, while Wi-Fi
        // handed a carrier grade address is.
        return when {
            localNetwork && address.isSiteLocalAddress -> 0
            localNetwork -> 1
            address.isSiteLocalAddress -> 2
            else -> 3
        }
    }

    private fun wifiManager(): WifiManager? {
        return context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }

    /**
     * The signal strength of the current association. A real reading is always a negative
     * number above the platform's invalid marker, so anything outside that range counts as
     * no reading -- which is what an unassociated radio reports, in whichever form the
     * device's Wi-Fi stack chooses to report it.
     */
    private fun readWifiRssi(): Int? {
        return try {
            val rssi = wifiManager()?.connectionInfo?.rssi ?: return null
            if (rssi in (UNKNOWN_RSSI + 1)..-1) rssi else null
        } catch (e: Exception) {
            Timber.e(e, "Error reading the Wi-Fi signal strength")
            null
        }
    }

    /**
     * Linear over the range Android's own signal bars use. WifiManager can do this, but
     * its two argument form was deprecated in API 30 and its replacement only exists from
     * API 30, so neither covers the range of versions this application supports.
     */
    private fun rssiToPercentage(rssi: Int): Int {
        return when {
            rssi <= RSSI_WORST -> 0
            rssi >= RSSI_BEST -> 100
            else -> ((rssi - RSSI_WORST) * 100) / (RSSI_BEST - RSSI_WORST)
        }
    }

    companion object {
        const val TYPE_BATTERY: Int = -100
        const val TYPE_CPU: Int = -101
        const val TYPE_MEMORY: Int = -102
        const val TYPE_STORAGE: Int = -103
        const val TYPE_UPTIME: Int = -104
        const val TYPE_WIFI_SIGNAL: Int = -105
        const val TYPE_IP_ADDRESS: Int = -107
        const val TYPE_APP_VERSION: Int = -108
        const val TYPE_ANDROID_VERSION: Int = -109

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
        const val STORAGE_FREE: String = "storageFree"
        const val UPTIME: String = "uptime"
        const val WIFI_SIGNAL: String = "wifiSignal"
        const val IP_ADDRESS: String = "ipAddress"
        const val APP_VERSION: String = "appVersion"
        const val ANDROID_VERSION: String = "androidVersion"

        /**
         * The readings published once per refresh rather than every cycle. Their messages
         * have to be retained: sent only once, a message that arrives before Home Assistant
         * subscribes, or before it restarts, would leave the entity unknown until the next
         * broker reconnect.
         */
        val DEVICE_INFO_SENSORS: Set<String> = setOf(APP_VERSION, ANDROID_VERSION)
        const val UNIT_C: String = "°C"
        const val UNIT_PERCENTAGE: String = "%"
        const val UNIT_HPA: String = "hPa"
        const val UNIT_UT: String = "uT"
        const val UNIT_LX: String = "lx"
        const val UNIT_MB: String = "MB"
        const val UNIT_GB: String = "GB"
        const val UNIT_DBM: String = "dBm"
        const val UNIT_SECONDS: String = "s"
        const val STATE_CLASS_MEASUREMENT: String = "measurement"
        const val STATE_CLASS_TOTAL_INCREASING: String = "total_increasing"
        const val VALUE = "value"
        const val UNIT = "unit"
        const val ID = "id"

        // What WifiManager hands back when it has nothing to report.
        private const val UNKNOWN_RSSI = -127
        private const val RSSI_WORST = -100
        private const val RSSI_BEST = -50

        private const val PROC_STAT = "/proc/stat"
        private const val PROC_MEMINFO = "/proc/meminfo"
    }
}