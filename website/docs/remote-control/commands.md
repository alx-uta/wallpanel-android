---
title: MQTT and HTTP commands
---

Interact and control the application and device remotely using either MQTT or HTTP (REST) commands, including using your device as an announcer with Google Text-To-Speach.

## Commands

Key | Value | Example Payload | Description
-|-|-|-
clearCache | true | ```{"clearCache": true}``` | Clears the browser cache
eval | JavaScript | ```{"eval": "alert('Hello World!');"}``` | Evaluates Javascript in the dashboard
audio | URL | ```{"audio": "http://<url>"}``` | Play the audio specified by the URL immediately
relaunch | true | ```{"relaunch": true}``` | Relaunches the dashboard from configured launchUrl
reload | true | ```{"reload": true}``` | Reloads the current page immediately
restartApp | true | ```{"restartApp": true}``` | Restarts the WallPanel app (kills and relaunches the process) -- this is not a device reboot
url | URL | ```{"url": "http://<url>"}``` | Browse to a new URL immediately
wake | true | ```{"wake": true, "wakeTime": 180}``` | Wakes the screen if it is asleep. Optional wakeTime (in seconds). If no wake time provided, screen will wake but return to screensaver mode on user inactivity.  Sending false value will return app to normal screensaver mode and display screensaver on user inactivity.
wake | false | ```{"wake": false}``` | Release screen wake (Note: screen will not turn off before Androids Display Timeout finished)
speak | data | ```{"speak": "Hello!"}``` | Uses the devices TTS to speak the message
settings | data | ```{"settings": true}``` | Opens the settings screen remotely.
brightness | data | ```{"brightness": 1}``` | Changes the screens brightness, value 0-255 (0 turns the backlight off).
camera | data | ```{"camera": true}``` | Turns on/off camera, this will also disable streaming, motion, QRCode, and face detection. The REST API stays up; the stream endpoint answers 503 while the camera is off.
cameraResolution | size or auto | ```{"cameraResolution": "1280x720"}``` | Runs the camera at `320x240`, `640x480` or `1280x720` until told otherwise; `auto` goes back to the configured resolution. See [Camera resolution and frame rate](#camera-resolution-and-frame-rate).
cameraFps | 1-30 or auto | ```{"cameraFps": 20}``` | Runs the camera at this frame rate until told otherwise; `auto` goes back to the configured one. See [Camera resolution and frame rate](#camera-resolution-and-frame-rate).
volume | data | ```{"volume": 100}``` | Sets the device's media volume, value 0-100 (in %). Applies to audio playback and Text-To-Speech alike.
toast | data | ```{"toast": "Dinner is ready"}``` | Shows a short toast message over the dashboard
screensaver | true/false | ```{"screensaver": true}``` | Shows or dismisses the screensaver. Does nothing if no screensaver is configured in the settings.
shell | command | ```{"shell": "log -t WallPanel hello-from-wallpanel"}``` | Runs a shell command on the device. Opt-in and unprivileged -- see [Shell Command](#shell-command) below before relying on this.

* The base topic value (default is "mywallpanel") should be unique to each device running the application unless you want all devices to receive the same command. The base topic and can be changed in the applications ```MQTT settings```.
* Commands are constructed via valid JSON. It is possible to string multiple commands together:
  * eg, ```{"clearCache":true, "relaunch":true}```
* For REST
  * POST the JSON to URL ```http://<the.device.ip.address>:2971/api/command```
* For MQTT
  * WallPanel subscribes to topic ```wallpanel/[baseTopic]/command```
    * Default Topic: ```wallpanel/mywallpanel/command```
  * Publish a JSON payload to this topic (be mindful of quotes in JSON should be single quotes not double)

## Wake and the screen switch

`{"wake": false}` releases the screen wake lock, but that's not the same as turning the
screen off. An ordinary Android app -- WallPanel has no device-admin or root privileges --
has no way to blank the display directly; only the OS's own inactivity timeout can do
that. Releasing the lock just stops WallPanel from holding the screen on; the screen
itself goes dark whenever the device's own timeout next expires.

If you're using [MQTT Discovery](./mqtt-setup.md), this shows up as the **Keep Screen
Awake** switch in Home Assistant appearing to misbehave: turn it off, and if the display
hasn't timed out yet it springs back to on a moment later, because the switch reads the
real screen state rather than remembering what was last sent. It then goes off on its
own once the OS actually blanks the screen. That's the platform being accurately
reported, not a bug in the switch.

## Camera resolution and frame rate

`cameraResolution` and `cameraFps` set what the camera runs at, over the settings and over
a [motion boost](../video-streaming.md#motion-boost). Each one replaces only its own half:
pin the resolution and a boost still raises the frame rate, and the other way round. Send
`"auto"` to hand either back.

```json
{"cameraResolution": "1280x720", "cameraFps": 15}
{"cameraResolution": "auto", "cameraFps": "auto"}
```

- The resolution is one of `320x240`, `640x480` or `1280x720`. A camera without the size
  asked for opens at the closest one it has; `cameraResolutionActive` in the
  [application state](./sensors.md#application-state-data) reports what it got.
- The frame rate is a whole number from 1 to 30, sent as a number or as text.
- A value outside those is ignored and logged, and the other key in the same command still
  applies.
- The camera only takes a new size or frame rate when it opens, so each change restarts
  it. The stream and the detectors pause for two to three seconds on an older device.
- Neither survives the app restarting, which puts the camera back on its settings.

With [MQTT Discovery](./sensors.md#home-assistant-discovery) these are the **Camera
Resolution** and **Camera FPS** selects, which is the easy way to have an automation raise
the resolution when something happens and lower it again afterwards.

## Volume

`volume` sets the device's media stream volume, the same one the hardware volume keys
control. It applies to the `audio` command and to Text-To-Speech, and it survives across
playbacks -- the current level comes back in the [application state](./sensors.md#application-state-data)
as `volume`.

Setting the volume to `0` can be refused on some Android versions, which treat silencing
a stream as a Do Not Disturb change requiring a permission WallPanel doesn't hold. The
attempt is logged to `adb logcat` when that happens.

## Shell Command

The `shell` command runs a command through the device's shell (`sh -c "<command>"`) and
is disabled by default. Enable it in **Settings &rarr; HTTP &rarr; Enable Shell Commands
( Security Risk )** -- you don't need to turn on **REST API** first, even though the
toggle lives on the same screen. Once enabled, `shell` works over both HTTP and MQTT,
the same as any other command in the table above -- the setting only lives on the HTTP
settings screen, it is not HTTP-only.

```json
{"shell": "log -t WallPanel hello-from-wallpanel"}
```

Full example using curl:

```sh
curl --location --request POST 'http://192.168.1.1:2971/api/command' \
--header 'Content-Type: application/json' \
--data-raw '{"shell": "log -t WallPanel hello-from-wallpanel"}'
```

Then confirm it actually ran with `adb logcat | grep WallPanel`.

### It runs unprivileged -- there is no root access, and currently there are no plans to add it

The command runs as a normal child process of the WallPanel app itself, with exactly
the same permissions the app has been granted and nothing more. There is no `su`, no
root, no privilege escalation of any kind, and this is deliberate -- it was considered
and turned down, not just left unbuilt. Even on a device that does have root available,
WallPanel will not attempt to use it. If you need a shell command that genuinely
requires root, this feature isn't the way to get it.

This matters because it rules out the commands people most often try first. For
example, `{"shell": "input keyevent 26"}` (to wake the screen, or send any other key
event) will never actually do anything: `input keyevent` requires the `INJECT_EVENTS`
permission, which Android only grants to system apps or apps signed with the platform
key. A regular installed app -- which is what WallPanel is, even with shell commands
turned on -- can never hold that permission. The same is true of most commands that
change system settings, control other apps, or touch hardware directly; if a command
needs a permission the WallPanel app itself doesn't have, it will fail no matter what
you put in the payload.

Worth knowing: how visibly this fails depends on the device. On a stock-ish Android 13
device this shows up clearly in `adb logcat` as a failed command (non-zero exit code)
with the real reason attached:

```
Shell command [input keyevent 3] failed with exit code 255, output: Exception occurred while executing 'keyevent':
java.lang.SecurityException: Injecting input events requires the caller (or the source
of the instrumentation, if any) to have the INJECT_EVENTS permission.
```

But on at least one Android 8.1 device we tested, the exact same command exited with
code `0` and no output at all -- same underlying permission failure, but nothing in the
log to show for it. Don't take a "succeeded" log line as proof a command like this
actually had an effect; check for the effect itself (e.g. did the screen actually wake
up?).

### What actually works

Commands that only need the permissions an ordinary app already has will work fine,
for example:

- Writing to WallPanel's own private storage, useful for lightweight logging or state
  from a script:

  ```json
  {"shell": "echo hello > /data/data/xyz.wallpanel.pro/files/test.txt"}
  ```

- Reading device or system properties:

  ```json
  {"shell": "getprop ro.build.version.release"}
  ```

- Writing to logcat, which you can then watch with `adb logcat`:

  ```json
  {"shell": "log -t WallPanel hello-from-wallpanel"}
  ```

- Invoking any other command-line tool present on the device that doesn't itself
  require elevated permissions.

### Useful examples for a kiosk fleet

These are all real commands verified against real devices, with the actual output they
produced (via `curl` + `adb logcat`, the same as ["Getting the result
back"](#getting-the-result-back) describes) -- not theoretical. If you're managing more than one WallPanel device, this is the kind of
thing the `shell` command is actually good for: cheap, ad-hoc fleet diagnostics without
installing a separate MDM tool.

**Which device am I talking to?** Handy when you're scripting against several devices
and want to confirm you hit the right one:

```json
{"shell": "getprop ro.product.model"}
```
```
SM-N9005
```

**Is the device still alive, and has it rebooted recently?** An unexpected uptime reset
across your fleet is a good sign something is crash-looping:

```json
{"shell": "uptime"}
```
```
21:58:42 up 3 days, 13:10, 0 users, load average: 10.56, 10.31, 10.46
```

**How much memory is free?** Useful if a device's browser session has been up for weeks
and you suspect a leak:

```json
{"shell": "free -m"}
```
```
             total       used       free     shared    buffers
Mem:          2834       2683        150         13         56
-/+ buffers/cache:       2626        207
Swap:            0          0          0
```

**How much storage is left?**

```json
{"shell": "df -h /data"}
```
```
Filesystem     Size  Used Avail Use% Mounted on
/dev/block/...  11G  0.9G  9.3G  10% /data
```

**Is the device's network actually working, not just showing a Wi-Fi icon?**

```json
{"shell": "ping -c 2 8.8.8.8"}
```
```
PING 8.8.8.8 (8.8.8.8) 56(84) bytes of data.
64 bytes from 8.8.8.8: icmp_seq=1 ttl=116 time=12.2 ms
64 bytes from 8.8.8.8: icmp_seq=2 ttl=116 time=19.2 ms

--- 8.8.8.8 ping statistics ---
2 packets transmitted, 2 received, 0% packet loss, time 1002ms
```

**Is the device running hot?** Relevant if you've hit the background-CPU issue covered
in [issue #17](https://github.com/alx-uta/wallpanel-android/issues/17) -- this reads the
first thermal sensor the kernel exposes:

```json
{"shell": "cat /sys/class/thermal/thermal_zone0/temp"}
```

The units aren't consistent across devices -- one of our test devices reported `74000`
(millidegrees C, i.e. 74°C), another reported `36` (whole degrees C, i.e. 36°C). Sanity
check what your specific device is giving you before trusting the number.

### Not every read-only command is portable across devices

It's tempting to assume anything that just *reads* something (rather than changing it)
is safe to rely on everywhere. It isn't -- we hit two examples of this firsthand while
testing:

- `{"shell": "ip -4 addr show wlan0"}` (reading the device's own IP address) worked
  cleanly on one test device, but failed on another (a stock-ish Android 13 phone) with
  `Cannot bind netlink socket: Permission denied`.
- `{"shell": "cat /sys/class/power_supply/battery/capacity"}` (battery percentage) was
  readable when run manually via `adb shell`, but denied with `Permission denied` when
  the exact same file was read by WallPanel itself -- `adb shell` and the app run as
  different, differently-privileged users, so testing a command with `adb shell` first
  does not prove WallPanel can run it.

If a command in this guide doesn't work on your device, that's most likely why -- try it
against your actual hardware before depending on it, the same way we did here.

### Writing to shared storage depends on the Android version

On Android 6 through 9 (API 23-28), turning the shell-command setting on triggers a
one-time runtime permission prompt for storage access. Granting it lets shell commands
write to shared storage, e.g. `/sdcard/...`.

On Android 10 and above, this prompt never appears, and there is no way to grant it
from within the app: Android's scoped-storage rules make that permission ineffective on
these versions regardless of whether it's granted. On Android 10+, shell commands are
confined to WallPanel's own private storage (`/data/data/xyz.wallpanel.pro/files/...`)
as shown above. This is a restriction Android itself places on non-rooted apps, not
something WallPanel can change.

### Getting the result back

The HTTP response (`{"result": true}`) only confirms the request was valid JSON, not that
the shell command succeeded. What the command actually did is published over MQTT to
`[baseTopic]sensor/shell`:

```json
{"value": "SM-N9005", "command": "getprop ro.product.model", "exitCode": 0, "output": "SM-N9005"}
```

`value` is the output truncated to 255 characters, because that's the longest state Home
Assistant will accept; `output` carries the same output truncated to 16384 characters
instead, long enough for most command output but not unbounded -- Home Assistant keeps
attributes in every state it records, so an unbounded one would be paid for on every
update. With [MQTT discovery](./mqtt-setup.md)
and shell commands both enabled this arrives as a **Shell Result** sensor, with the
command and exit code as attributes.

Every shell command is also logged to `adb logcat` with its exit code and output. As
covered above, a `0` exit code isn't a guarantee the command had an effect if what it
tried to do needed a permission the app doesn't have.

### Treat it as a real attack surface, even without root

"No root" limits the damage a shell command can do, but it doesn't make this safe to
expose carelessly -- anyone who can reach your MQTT broker or the device's HTTP port can
run arbitrary commands as the WallPanel app: read anything the app can read, write to
its private storage, and use the network the device is on. That's exactly why the
Settings toggle is labeled "Security Risk." Only turn it on if you actually need it, and:

- Don't expose the HTTP port (`2971` by default) to the open internet or an untrusted
  network -- keep it on a trusted local network only.
- If you're using MQTT, use broker authentication and, ideally, TLS (see
  [MQTT Setup](./mqtt-setup.md)) -- anyone who can publish to your command topic can run
  shell commands the same as anyone who can POST to the HTTP endpoint. Note that with
  MQTT discovery on, enabling this also puts a **Shell Command** input box in Home
  Assistant for anyone with access to that dashboard.
- Turn it off again when you're done if you only needed it temporarily.

## Google Text-To-Speech (TTS) Command

You can send a command using either HTTP or MQTT to have the device speak a message using Google's Text-To-Speach. Note that the device must be running Android Lollipop or above.

Example format for the message topic and payload:

```json
{"topic":"wallpanel/mywallpanel/command", "payload":"{'speak':'Hello!'}"}
```

If you are using HTTP and sending text with special characters, such as those used in a Cyrillic or Spanish language, you would need to make sure your content type is set to utf-8, here is an example using curl to post a message in Spanish:

```sh
curl --location --request POST 'http://192.168.1.1:2971/api/command' \
--header 'Content-Type: application/json;charset=UTF-8' \
--data-raw '{
    "speak": "¡Aló mundo"                        
}'
```
