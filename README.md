## Recent Improvements

### Browser Engine
- **GeckoView Integration** - Added Mozilla Firefox's GeckoView engine as an alternative to WebView
- Runtime browser engine switching (WebView/GeckoView)
- Better modern web standards support (ES6+, WebGL, advanced CSS)
- Improved dark mode support with proper rendering

### Performance Optimisations
- **Camera CPU Optimisation** - Reduced motion detection CPU usage by 85-92% through frame skipping and low-resolution processing
- Configurable frame skip for motion detection (default: process every 5th frame)
- Optional low-resolution camera mode (320x240) for better performance on low-end devices
- Camera-only-during-screensaver feature to save battery
- **Debug Logging Cleanup** - Removed 55+ high-frequency debug logs saving ~7.5 MB/day (2.7 GB/year)
  - Eliminated logs from MQTT publish path (17,280 daily calls)
  - Removed sensor update cycle logs (1,440 daily calls)
  - Cleaned browser progress callbacks (100+ per page load)
  - Reduced GC pressure and CPU overhead on low-power devices

### Architecture & Dependencies
- Updated to the latest AndroidX libraries (2024 releases)
- Migrated from RxJava to Kotlin Coroutines (-2.4 MB)
- Removed legacy support libraries
- Updated OkHttp to 4.12.0 with TLS 1.3 support
- Removed Firebase/Google Services tracking
- Modern Fragment API implementation

### Build System
- Gradle 8.5 with Java 17 support
- Removed deprecated plugins and dependencies
- Professional codebase cleanup

### System Monitoring & Control
- **CPU Usage Sensor** - Real-time system CPU usage monitoring via `/proc/stat`
- **Memory Usage Sensor** - System memory tracking with total/available/used metrics
- **Shell Command Support** - Remote command execution via MQTT/HTTP API (opt-in with security warnings)
- Full MQTT Discovery support for Home Assistant auto-configuration

### User Experience
- WebView caching enabled for 30-50% faster page loads
- Improved screensaver functionality
- Better error handling and stability

---

# WallPanel

WallPanel is an Android application for Web Based Dashboards and Home Automation Platforms. You can either sideload the application to your Android device from the [release section](https://github.com/alx-uta/wallpanel-android/releases).

## Screenshots

<img src="img/dashboard2.png" width="640" />
<img src="img/dashboard3.png" width="640" />
<img src="img/dashboard1.png" width="640" />

## Support

For issues, feature requests, use the [Github issues tracker](https://github.com/alx-uta/wallpanel-android/issues). For examples and to learn how to use each feature, visit [WallPanel Documentation](https://wallpanel.xyz/).

## Features

- Web Based Dashboards and Home Automation Platforms support.
- Set application as Android Home screen (optional)
- Use code to access the settings and make the settings button invisible.
- Camera support for streaming video, motion detection, face detection, and QR Code reading.
- Google Text-to-Speech support to speak notification messages using MQTT or HTTP.
- MQTT or HTTP commands to remotely control device and application (url, brightness, wake, etc.).
- Sensor data reporting for the device (temperature, light, pressure, battery, CPU usage, memory usage).
- System resource monitoring with CPU and memory sensors for device health tracking.
- Remote shell command execution via MQTT/HTTP (opt-in, security warning, runs unprivileged -- see the [Shell Command docs](https://wallpanel.xyz/docs/remote-control/commands#shell-command) for what does and doesn't work).
- Streaming MJPEG server support using the device camera.
- Screensaver feature that can be dismissed with motion or face detection.
- Support for Android 4.4 (API level 19) and greater devices.
- Support for launching external applications using intent URL

## Hardware & Software

**_ If you have need support for older Android 4.0 devices (those below Android 4.4), you want to use the [legacy](https://github.com/thanksmister/wallpanel-android-legacy) version of the application. Alternatively you can download an APK from the release section prior to release v0.8.8-beta.6 _**

## Quick Start

You can load the application to your device from the [release section](https://github.com/alx-uta/wallpanel-android/releases). The application will open to the welcome page with a link to update the settings. Open the settings by clicking the dashboard floating icon. In the settings, set your web page or home automation platform url. Also set the code for accessing the settings, the default is 1234.

## Building the Application

To build the application locally, checkout the code from Github and load the project into Android Studio with Android API 31 or higher.

## Limitations

Setting WallPanel as the default Home application will always load this application as your home. Removing this feature is difficutl without uninstalling the application. So please do this is you wish to use the application as a "kiosk" type application.

## Contribution

All are welcome to propose a feature request, report or bug, or contribute to the project by updating examples or with a PR for new features. Thanks to all the [contributes](https://github.com/alx-uta/wallpanel-android/graphs/contributors) who have contributed to the project!

## Special Thanks

- [TheTimeWalker](http://github.com/TheTimeWalker) & [ThanksMister](https://github.com/thanksmister) for maintaining and continued development of WallPanel.
- [quadportnick](https://github.com/quadportnick) for starting [the original WallPanel (formerly HomeDash)](https://github.com/WallPanel-Project/wallpanel-android).