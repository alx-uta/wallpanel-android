---
title: MQTT Sensors
---

If MQTT is enabled in the settings and properly configured, the application can publish data and states for various device sensors. The application will post device sensors data per the API description and Sensor Reading Frequency. Currently device sensors for Pressure, Temperature, Light, and Battery Level are published.

## Sensor Data

Sensor | Keys | Example | Notes
-|-|-|-
battery | unit, value, charging, acPlugged, usbPlugged | ```{"unit":"%", "value":"39", "acPlugged":false, "usbPlugged":true, "charging":true}``` |
light | unit, value | ```{"unit":"lx", "value":"920"}``` |
magneticField | unit, value | ```{"unit":"uT", "value":"-1780.699951171875"}``` |
pressure | unit, value | ```{"unit":"hPa", "value":"1011.584716796875"}``` |
temperature | unit, value | ```{"unit":"°C", "value":"24"}``` |

*NOTE:* Sensor values are device specific. Not all devices will publish all sensor values.

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
motion | value | ```{"value": false}``` | Published immediately when motion detected
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
brightness | number | ```{"brightness":100}``` | Current brightness value of the screen.
androidVersion | string | ```{"androidVersion":"14"}``` | Android release reported by the device.
appVersion | string | ```{"appVersion":"1.1.7"}``` | WallPanel application version.
deviceOwner | true/false | ```{"deviceOwner":true}``` | Whether WallPanel is the Android device owner.
ipAddress | string | ```{"ipAddress":"192.168.1.20"}``` | IPv4 address of the active network connection (Wi-Fi or Ethernet).
manufacturer | string | ```{"manufacturer":"Amazon"}``` | Device manufacturer.
model | string | ```{"model":"Fire HD 10"}``` | Device model.
screenSaver | true/false | ```{"screenSaver":false}``` | Whether the WallPanel screensaver is active.
storageFree | number | ```{"storageFree":2048}``` | Free application storage in MB.
uptime | number | ```{"uptime":86400}``` | Device uptime in seconds.
volume | number | ```{"volume":50}``` | Media volume as a percentage.
wifiSignal | number or null | ```{"wifiSignal":-58}``` | Wi-Fi RSSI in dBm; null when unavailable.
wifiSsid | string | ```{"wifiSsid":"Office"}``` | Wi-Fi SSID when Android permits access.

* State values are presented together as a JSON block
  * eg, ```{"currentUrl":"http://hasbian:8123/states","screenOn":true}```
* For REST
  * GET the JSON from URL ```http://[mywallpanel]:2971/api/state```
* For MQTT
  * WallPanel publishes state to topic ```[baseTopic]state```
    * Default Topic: ```wallpanel/mywallpanel/state```

:::note
On newer Android versions, Android can redact the Wi-Fi SSID unless location-related
permissions and system location services are enabled. WallPanel does not request those
permissions solely for MQTT telemetry, so `wifiSsid` can be empty and `wifiSignal` can
be unavailable on affected devices.
:::

## Selecting sensors for MQTT Discovery

In Sensors Settings each sensor listed above can be turned on or off
individually (battery &amp; hardware sensors, application state, face/motion/QR). Turning a
sensor off deletes its retained discovery message, so Home Assistant removes the entity.
