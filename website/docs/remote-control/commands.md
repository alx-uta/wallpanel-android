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
url | URL | ```{"url": "http://<url>"}``` | Browse to a new URL immediately
wake | true | ```{"wake": true, "wakeTime": 180}``` | Wakes the screen if it is asleep. Optional wakeTime (in seconds). If no wake time provided, screen will wake but return to screensaver mode on user inactivity.  Sending false value will return app to normal screensaver mode and display screensaver on user inactivity.
wake | false | ```{"wake": false}``` | Release screen wake (Note: screen will not turn off before Androids Display Timeout finished)
speak | data | ```{"speak": "Hello!"}``` | Uses the devices TTS to speak the message
settings | data | ```{"settings": true}``` | Opens the settings screen remotely.
brightness | data | ```{"brightness": 1}``` | Changes the screens brightness, value 0-255 (0 turns the backlight off).
camera | data | ```{"camera": true}``` | Turns on/off camera, this will also disable streaming, motion, QRCode, and face detection.
volume | data | ```{"volume": 100}``` | Changes the audio volume, value 0-100 (in %. Does not effect TTS volume).
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
produced (via `curl` + `adb logcat`, the same as the ["No success/failure
feedback"](#no-successfailure-feedback-via-http-or-mqtt) section describes) -- not
theoretical. If you're managing more than one WallPanel device, this is the kind of
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

### No success/failure feedback via HTTP or MQTT

Neither the HTTP response (`{"result": true}` just confirms the request was valid JSON,
not that the shell command succeeded) nor MQTT gives back any indication of whether a
shell command succeeded or failed. WallPanel does log every shell command it runs to
`adb logcat` -- both the exit code and any output the command produced -- so that's the
place to check when a command doesn't seem to be doing what you expect. As covered
above, though, a `0` exit code in the log isn't a guarantee the command actually had an
effect if what it tried to do needed a permission the app doesn't have.

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
  shell commands the same as anyone who can POST to the HTTP endpoint.
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
