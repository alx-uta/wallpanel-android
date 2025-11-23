# WallPanel Android - AI Coding Agent Instructions

## Project Overview
WallPanel is an Android kiosk application for displaying web-based dashboards (primarily Home Assistant). It features dual browser engine support (WebView/GeckoView), MQTT/HTTP remote control, camera streaming with motion/face detection, and device sensor publishing.

**Status**: Archived project (maintainer accepting transfer requests). Active fork with GeckoView integration in progress.

## Architecture & Key Components

### Browser Engine System (Dual Implementation)
- **Runtime selection** between standard WebView and Mozilla GeckoView (experimental)
- Toggle in `Configuration.useGeckoView` (SharedPreferences)
- `BrowserActivityNative.kt` handles engine switching with fallback strategy
- Both engines coexist in `activity_browser.xml` with visibility toggling
- Adapter pattern: `GeckoWebClientAdapter`/`GeckoWebChromeClientAdapter` map GeckoView APIs to `WebClientCallback` interface
- GeckoView requires minSdk 21 (changed from 19), provides modern web features for Android 8+ devices

### Dependency Injection (Dagger 2)
- All activities extend `DaggerAppCompatActivity`
- `ApplicationComponent.java` + `AndroidBindingModule.kt` define injection graph
- Key injected components: `Configuration`, `MqttModule`, `SensorReader`, `CameraReader`, `ScreenUtils`
- ViewModels use `DaggerViewModelFactory` for constructor injection

### MQTT & HTTP Control Architecture
- `WallPanelService.kt` (1120 lines) is the core service - runs as `LifecycleService`
- Dual MQTT protocol support: `MQTT3Service`/`MQTT5Service` via HiveMQ client
- `MQTTModule` wraps both services, auto-selects based on `mqttOptions.getVersion()`
- HTTP REST server uses `AsyncHttpServer` (port 2971 default)
- Commands defined in `MqttUtils.kt` constants (COMMAND_URL, COMMAND_WAKE, etc.)
- Single command processor: `WallPanelService.processCommand(JSONObject)` handles both MQTT/HTTP

### Sensor & Camera Pipeline
- `SensorReader.kt`: Publishes battery, light, pressure, temperature, magneticField to MQTT
- `CameraReader.kt`: Manages camera lifecycle, provides JPEG frames via LiveData
- `MotionDetector`/`StreamingDetector`: OpenCV-based motion detection from camera frames
- Face detection uses Google Play Services Vision API
- QR Code scanning integrated into camera stream
- MJPEG streaming endpoint: `GET /camera/stream` (managed in WallPanelService)

### Configuration Management
- `Configuration.kt`: Centralized SharedPreferences wrapper (418 lines)
- All settings accessed via getter/setter properties (no direct SharedPreferences calls)
- Settings fragments inherit `BaseSettingsFragment` with common navigation/help logic
- Settings changes trigger `hasSettingsUpdates()` mechanism for activity restart

## Build System Specifics

### Gradle Configuration
- **Gradle 8.5** required (upgraded from 7.4 for Java 21 compatibility)
- **Java 21** + **Kotlin 1.9.22** (upgraded for compatibility)
- AGP 7.3.0 (reverted from 8.x due to jlink issues with JDK 21)
- `android-retrofix` plugin removed (incompatible with Gradle 8.5)
- Maven Mozilla repo added for GeckoView: `maven { url "https://maven.mozilla.org/maven2/" }`

### Build Flavors (Critical for Development)
Three flavors in `productFlavors` block:
1. **`dev`**: Requires `local.properties` with code/hassUrl/broker credentials (reads via `BuildConfig`)
2. **`qa`**: No special config required
3. **`prod`**: Hard-coded defaults (CODE=1234, empty URLs)

**Build Commands** (PowerShell):
```powershell
.\gradlew.bat assembleProdDebug  # No local.properties needed
.\gradlew.bat assembleDevDebug   # Requires local.properties setup
.\gradlew.bat installProdDebug   # Direct device install
```

### Firebase Removal (Local Builds)
Firebase dependencies are commented out but referenced in documentation. Remove entirely if building without Google Services:
- `com.google.firebase:firebase-crashlytics-ktx`
- `com.google.gms.google-services` plugin

## Development Workflows

### Testing GeckoView Changes
1. Modify `Configuration.useGeckoView` default or set via UI Settings → General
2. App restarts automatically when exiting Settings (if `hasSettingsUpdates()` returns true)
3. Check fallback logic in `BrowserActivityNative.configureWebView()` for Toast error messages
4. GeckoView version: `org.mozilla.geckoview:geckoview-nightly:124.+` (dynamic resolution)

### MQTT Command Testing
Send commands via HTTP POST to `http://<device-ip>:2971/api/command`:
```json
{"url": "https://example.com"}
{"speak": "Hello World"}
{"clearCache": true, "reload": true}
```
Or MQTT publish to `wallpanel/[baseTopic]/command`

### Debugging Sensor Publishing
- Enable sensor publishing in Settings → Sensors
- Check MQTT topics: `wallpanel/[baseTopic]/sensor/{battery,light,pressure,temperature}`
- State data: `wallpanel/[baseTopic]/state` (currentUrl, screenOn, brightness)

## Project-Specific Conventions

### Kotlin Code Style
- Activities: `<Feature>Activity` extends `DaggerAppCompatActivity`
- Fragments: `<Feature>Fragment` extends `BaseSettingsFragment` (for settings)
- Utils: Static-like classes with `@Inject constructor`
- Modules: Service-layer classes in `xyz.wallpanel.app.modules` (MQTTModule, SensorReader, etc.)

### Resource Naming
- Preference keys: `R.string.key_setting_<category>_<name>` (e.g., `key_setting_mqtt_enabled`)
- Default values: `R.string.default_setting_<category>_<name>`
- Preference XML: `pref_<category>.xml` (pref_mqtt.xml, pref_http.xml)

### Logging
Uses Timber throughout. Standard pattern:
```kotlin
Timber.d("Debug message")      // Debug info
Timber.i("Info message")       // Important events
Timber.e("Error: ${e.message}") // Errors
```

## Critical Files to Understand
- `BrowserActivityNative.kt` (552 lines): Main activity, engine switching, URL loading, JavaScript execution
- `WallPanelService.kt` (1120 lines): MQTT/HTTP server, command processing, sensor publishing
- `Configuration.kt` (418 lines): All app settings accessor methods
- `MQTTModule.kt`: MQTT lifecycle management, dual protocol handling
- `build.gradle` (WallPanelApp): Dependencies, flavors, GeckoView integration

## Common Pitfalls
1. **Build flavor confusion**: Dev builds fail without `local.properties`, use `prodDebug` for quick testing
2. **GeckoView minSdk**: Requires API 21+ (changed from 19), incompatible with older devices
3. **Settings restart**: Changes in settings fragments don't apply until activity restart
4. **MQTT version mismatch**: Ensure broker supports selected protocol (3.1.1 or 5.0)
5. **Hardware acceleration**: GeckoView requires enabled, WebView works with disabled (see `onStart()` in BrowserActivityNative)
6. **Camera permissions**: Required for motion/face/QR detection AND MJPEG streaming

## External Integration Points
- **MQTT Broker**: HiveMQ client, supports both MQTT 3.1.1 and 5.0 protocols
- **Google Play Services Vision**: Face detection (may require Google Services on device)
- **Home Assistant**: Primary use case, MQTT discovery supported for sensor auto-config
- **WebView component**: Android system WebView (updateable via Play Store, version-dependent rendering)

## Documentation References
- Build instructions: `BUILD_INSTRUCTIONS.md`
- GeckoView implementation log: `IMPLEMENTATION_LOG.md`, `PHASE3_SUMMARY.md`
- Website docs: `website/docs/` (getting-started, remote-control commands/sensors, screensaver)
