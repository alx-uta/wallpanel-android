---
title: MQTT Sensors
---

If MQTT is enabled in the settings and properly configured, the application can publish data and states for various device sensors. The application will post device sensors data per the API description and Sensor Reading Frequency.

## Sensor Data

### Hardware sensors

These come from the device's own sensors, so what you get depends on the hardware.

Sensor | Keys | Example | Notes
-|-|-|-
battery | unit, value, charging, acPlugged, usbPlugged | ```{"unit":"%", "value":"39", "acPlugged":false, "usbPlugged":true, "charging":true}``` |
light | unit, value | ```{"unit":"lx", "value":"920"}``` |
magneticField | unit, value | ```{"unit":"uT", "value":"-1780.699951171875"}``` |
pressure | unit, value | ```{"unit":"hPa", "value":"1011.584716796875"}``` |
temperature | unit, value | ```{"unit":"°C", "value":"24"}``` |
humidity | unit, value | ```{"unit":"%", "value":"48"}``` |

*NOTE:* Sensor values are device specific. Not all devices will publish all sensor values.

### System and device sensors

These are published on every device, on the same Sensor Reading Frequency -- except
`appVersion` and `androidVersion`, which only change when the app or the OS is updated,
so they publish once when sensor readings start and again each time the app reconnects
to the broker, instead of every cycle. Their messages are retained, so Home Assistant still
has them after it restarts.

Sensor | Keys | Example | Notes
-|-|-|-
cpuUsage | unit, value, id | ```{"unit":"%", "value":12, "id":"system_cpu"}``` | System-wide, read from `/proc/stat`. See the note below -- not available on every device
memoryUsage | unit, value, total, available, percentage | ```{"unit":"MB", "value":1840, "total":2834, "available":994, "percentage":64}``` |
storageFree | unit, value, total, freeBytes, totalBytes | ```{"unit":"GB", "value":9.95, "total":11.72, "freeBytes":9946419200, "totalBytes":11721045606}``` | Free space on the partition holding the app's data. `value`/`total` are decimal GB (10⁹ bytes) -- the same units Home Assistant's `data_size` device class and Android's own storage screen use, not binary GiB
uptime | unit, value, days, hours, minutes, formatted | ```{"unit":"s", "value":307800, "days":3, "hours":13, "minutes":30, "formatted":"3d 13h 30m"}``` | Time since the device booted, not since the app started. `days`/`hours`/`minutes` are a breakdown, not three totals
ipAddress | value | ```{"value":"192.168.1.42"}``` | The IPv4 address the local network can reach the device on. A private address on `wlan*`/`eth*` wins, so a VPN tunnel or a mobile interface doesn't take precedence over the LAN address you'd reach the built-in web server on. Wired devices report too
wifiSignal | unit, value, percentage | ```{"unit":"dBm", "value":-58, "percentage":84}``` | Only published when the device is associated with a Wi-Fi network
appVersion | value, id | ```{"value":"0.0.9", "id":"xyz.wallpanel.pro"}``` | Published once per broker connection, retained -- see above
androidVersion | value, sdk, manufacturer, model | ```{"value":"13", "sdk":33, "manufacturer":"samsung", "model":"SM-N9005"}``` | Published once per broker connection, retained -- see above

:::note cpuUsage isn't on every device
WallPanel only creates a Home Assistant entity for `cpuUsage` where the value can
actually be read -- an entity that never receives a value would otherwise sit there as
`unknown` forever.

Some devices' SELinux policy denies the app's `untrusted_app` domain read access to
`/proc/stat`. Checked directly on hardware, it worked on our Android 8.1 device and was
denied on both the Android 13 and Android 15 ones, so this is more common on newer
builds, not less. Don't check this with `adb shell cat /proc/stat` -- the adb shell runs
in a different SELinux domain and can read the file on all three devices, including the
two where the app itself is denied, so it isn't a reliable stand-in for whether the
sensor will work.
:::

:::note There is no network name sensor
WallPanel reports Wi-Fi signal strength but not the network name. From Android 8.1 onwards
the name goes only to apps holding a location permission, with location services switched
on, and WallPanel does not ask for that permission -- so the sensor would read as unknown
on every supported device. `wifiSignal` needs no such permission and is unaffected.
:::

* Sensor values are constructed as JSON per the above table
* For MQTT
  * WallPanel publishes all sensors to MQTT under ```[baseTopic]sensor```
  * Each sensor publishes to a subtopic based on the type of sensor
    * Example: basetopic: ```wallpanel/mywallpanel/``` battery sensor data is published to: ```wallpanel/mywallpanel/sensor/battery```

### Home Assistant Examples

:::note
WallPanel supports Home Assistant auto-detection, so manually configuring these sensors is usually not needed.
:::

```yaml
sensor:
  - platform: mqtt
    state_topic: "wallpanel/mywallpanel/sensor/battery"
    name: "WallPanel Battery Level"
    unit_of_measurement: "%"
    value_template: '{{ value_json.value }}'

 - platform: mqtt
    state_topic: "wallpanel/mywallpanel/sensor/temperature"
    name: "WallPanel Temperature"
    unit_of_measurement: "°C"
    value_template: '{{ value_json.value }}'

  - platform: mqtt
    state_topic: "wallpanel/mywallpanel/sensor/light"
    name: "WallPanel Light Level"
    unit_of_measurement: "lx"
    value_template: '{{ value_json.value }}'

  - platform: mqtt
    state_topic: "wallpanel/mywallpanel/sensor/magneticField"
    name: "WallPanel Magnetic Field"
    unit_of_measurement: "uT"
    value_template: '{{ value_json.value }}'

  - platform: mqtt
    state_topic: "wallpanel/mywallpanel/sensor/pressure"
    name: "WallPanel Pressure"
    unit_of_measurement: "hPa"
    value_template: '{{ value_json.value }}'
```

## Camera Motion, Face, and QR Codes Detection

In additional to device sensor data publishing, the application can also publish states for Motion detection and Face detection, as well as the data from QR Codes derived from the device camera.  Note that this feature requires that the camera be enabled in the settings.

Detection | Keys | Example | Notes
-|-|-|-
motion | value | ```{"value": false}``` | Published immediately when motion detected, and back to false once there has been no motion for the motion reset time
face | value | ```{"value": false}``` | Published immediately when face detected
qrcode | value | ```{"value": data}``` | Published immediately when QR Code scanned

* For MQTT
  * WallPanel publishes all sensors to MQTT under ```[baseTopic]/sensor```
  * Each sensor publishes to a subtopic based on the type of sensor
    * Example: ```wallpanel/mywallpanel/sensor/motion```

### Home Assistant Examples

```YAML
binary_sensor:
  - platform: mqtt
    state_topic: "wallpanel/mywallpanel/sensor/motion"
    name: "Motion"
    payload_on: '{"value":true}'
    payload_off: '{"value":false}'
    device_class: motion 
    
binary_sensor:
  - platform: mqtt
    state_topic: "wallpanel/mywallpanel/sensor/face"
    name: "Face Detected"
    payload_on: '{"value":true}'
    payload_off: '{"value":false}'
    device_class: motion 
  
sensor:
  - platform: mqtt
    state_topic: "wallpanel/mywallpanel/sensor/qrcode"
    name: "QR Code"
    value_template: '{{ value_json.value }}'
    
```

## Application State Data

The application can also publish state data about the application such as the current dashboard url loaded or the screen state.

Key | Value | Example | Description
-|-|-|-
currentUrl | URL String | ```{"currentUrl":"http://hasbian:8123/states"}``` | Current URL the Dashboard is displaying
screenOn | true/false | ```{"screenOn":true}``` | If the screen is currently on.
screenAwake | true/false | ```{"screenAwake":false}``` | Whether a `wake` command is currently holding the screen awake.
brightness | 0-255 | ```{"brightness":100}``` | Brightness the screen is showing right now. While the screensaver is dimming, this is the dimmed value.
brightnessSetpoint | 0-255 | ```{"brightnessSetpoint":150}``` | Brightness the application is configured to use, i.e. what the last `brightness` command set.
volume | 0-100 | ```{"volume":60}``` | Current media volume, as a percentage.
camera | true/false | ```{"camera":false}``` | Whether the camera is enabled.
screenSaverOn | true/false | ```{"screenSaverOn":false}``` | Whether the screensaver is currently showing.
cameraResolution | size or auto | ```{"cameraResolution":"auto"}``` | The resolution the last `cameraResolution` command pinned, or `auto`.
cameraFps | 1-30 or auto | ```{"cameraFps":"auto"}``` | The frame rate the last `cameraFps` command pinned, as text, or `auto`.
cameraResolutionActive | size or null | ```{"cameraResolutionActive":"640x480"}``` | The resolution the camera is running at, which a boost or a camera without the size asked for can make different from the settings. `null` while the camera is off or could not be opened, for instance without the camera permission.
cameraFpsActive | number or null | ```{"cameraFpsActive":15}``` | The frame rate the running camera was asked for, `null` while it is not running.
cameraBoosted | true/false | ```{"cameraBoosted":false}``` | Whether motion has the camera on its [boost](../video-streaming.md#motion-boost) resolution and frame rate.

* State values are presented together as a JSON block
  * eg, ```{"currentUrl":"http://hasbian:8123/states","screenOn":true}```
* For REST
  * GET the JSON from URL ```http://[mywallpanel]:2971/api/state```
* For MQTT
  * WallPanel publishes state to topic ```[baseTopic]state```
    * Default Topic: ```wallpanel/mywallpanel/state```

## Home Assistant Discovery

With **MQTT Discovery** enabled, WallPanel publishes retained discovery configs so Home
Assistant creates everything above -- plus a set of controls -- as entities on a single
device, with no YAML. Turning a feature off republishes an empty config, which removes
the entity rather than leaving it behind as unavailable.

### Controls

Every control publishes the same JSON to the command topic that you could send by hand,
so nothing here does anything the [commands](./commands.md) can't already do.

Entity | Type | Command sent
-|-|-
Reload Page | button | `{"reload": true}`
Clear Cache | button | `{"clearCache": true}`
Relaunch Dashboard | button | `{"relaunch": true}`
Wake Screen | button | `{"wake": true}`
Restart App | button | `{"restartApp": true}`
Open Settings | button | `{"settings": true}`
Keep Screen Awake | switch | `{"wake": true / false}`
Camera | switch | `{"camera": true / false}`
Camera Resolution | select (auto, 320x240, 640x480, 1280x720) | `{"cameraResolution": "<value>"}`
Camera FPS | select (auto, 5, 10 ... 30) | `{"cameraFps": "<value>"}`
Screensaver | switch | `{"screensaver": true / false}`
Brightness | number (0-255) | `{"brightness": <value>}`
Volume | number (0-100) | `{"volume": <value>}`
Navigate URL | text | `{"url": "<value>"}`
Text to Speech | text | `{"speak": "<value>"}`
Toast Message | text | `{"toast": "<value>"}`
Shell Command | text | `{"shell": "<value>"}`

The switches read their state back from the application state topic, so they follow the
device rather than just remembering what was last pressed. The text boxes are write-only;
Home Assistant keeps whatever was last typed in them.

:::note Keep Screen Awake is not a screen on/off switch
Turning it **on** holds a wake lock, which turns the display on for the length of your
inactivity timeout (30 seconds by default) and then releases it -- so the switch returns
to off by itself. Turning it **off** releases that lock, which does not blank the screen;
Android's own display timeout does that whenever it gets there.

An app without root or device-owner rights cannot force a display off, so this is as far
as it goes. The switch shows whether that wake lock is held (`screenAwake`), not whether
the display is lit; `screenOn` in the application state reports the display.
:::

**Brightness** only appears when the application is set to control the screen brightness
(**Settings &rarr; Device &rarr; screen brightness**), because `brightness` commands are
ignored otherwise. The slider tracks `brightnessSetpoint` rather than the live screen
value, so a screensaver dimming the display doesn't drag the slider down with it.

Switching **MQTT Discovery** off removes every entity again rather than leaving them
behind. Discovery configs are retained on the broker, so an application that simply
stopped publishing would strand them in Home Assistant as unavailable forever. Changing the
client id or the discovery base topic likewise removes the entities published under the
old one, and Home Assistant builds a new device under the new one.

Switching **MQTT** off altogether removes the entities the same way before the application
disconnects. That needs the broker to be reachable at the time; if it is not, the entities
stay behind as unavailable until MQTT is switched back on. Closing or uninstalling the
application removes nothing: the device only shows as unavailable, which is how Home
Assistant marks a device that is temporarily offline.

**Camera Resolution** and **Camera FPS** only appear while the camera is on, and read
back `cameraResolution` and `cameraFps` from the application state. A frame rate set by
hand to a value the select does not list, such as 12, still applies; Home Assistant just
logs that the select holds an option it does not know.

**Shell Command** and its **Shell Result** sensor only appear once shell commands are
enabled in **Settings &rarr; HTTP** -- see [Shell Command](./commands.md#shell-command)
for what that exposes.

### Choosing what gets published

Under **Settings &rarr; MQTT &rarr; MQTT Discovery** there are two pickers, **Controls to
publish** and **Sensors to publish**, listing everything on this page. Everything is
ticked until you change it, so an existing device carries on publishing what it always
did.

What's actually stored is the list of what you've *unticked* -- ticked is just the
absence of that. That means an entity WallPanel adds in a future version arrives ticked
(published) automatically, on a device that has never seen the new settings screen,
rather than silently staying off until someone opens it and ticks it by hand.

Unticking an entity removes it from Home Assistant rather than leaving it behind
unavailable, and ticking it again brings it back. This is the way to drop controls you
don't want on a shared dashboard -- **Restart App** and **Shell Command**, say -- or to cut
down sensor noise across a fleet.

**Publish Controls** is still there as a single switch for all fifteen controls at once,
which is quicker than unticking them one by one if you only want the sensors. Turning it
off also greys out **Controls to publish** -- there's nothing to pick from a list of
controls that aren't being published at all.

Being listed in the picker isn't a promise the entity appears. A control still needs its
underlying feature switched on, and a sensor still needs hardware that reports it -- so
ticking `cpuUsage` on a device whose SELinux policy denies it changes nothing.

### What isn't there

The list above is shorter than what some other kiosk apps offer, because it only covers
things WallPanel can actually carry out as an ordinary installed app:

- **Keyboard keys, key combos, and remote-control buttons** (Back, Home, Up/Down, Play/Pause
  and so on) need the `INJECT_EVENTS` permission, which Android grants only to system
  apps or apps signed with the platform key. This is the same limit described under
  [shell commands](./commands.md#shell-command).
- **Lock** and **Reboot** need device-owner provisioning or root.
- **Manufacturer** and **Model** aren't separate sensors -- Home Assistant already shows
  both on the device page, and they're in the `androidVersion` payload if you want to
  template on them.
