---
title: Troubleshooting
---

## The log

WallPanel keeps a log on the device, so a tablet that went wrong days ago can still say what happened. It holds what the app did and every warning and error: start-ups, MQTT and network connections coming and going, pages that failed to load, the browser engine crashing, and the full error when the app itself crashes. Once an hour it adds a line with how much memory is in use, so a gap in those lines shows when the app stopped running.

The log stops at about 2 MB, the oldest lines going first, and stays on the device until you send it somewhere.

### Sharing it

Open **Settings &rarr; Share Log**, at the end of the section with Export Settings, and pick one:

- **Share** hands the log to an app on the device, such as an email or chat app.
- **Save** writes a text file to a folder you choose, for a device with no such app.

### Reading it over the network

With the [REST API](remote-control/commands.md) switched on, the same log is at:

```sh
curl http://192.168.1.1:2971/api/log
```

It needs the [access token](remote-control/commands.md#access-token) when one is set. Without a token, anyone on your network can read it, the same as the rest of the REST API.

### What is in it

The log starts with the app and Android versions, the device, the browser engine, memory, your settings that are switches or numbers, and on Android 11 and later how the app last ended: a crash, not responding, killed for low memory and so on.

Before anything is written to the log, WallPanel removes:

- the MQTT and dashboard passwords, the HTTP access token, and the Home Assistant token
- anything after the `?` or `#` in a web address, and any login inside one
- text that follows a word like `username`, `password` or `token`, and anything shaped like a Home Assistant token

Commands are logged by name only, so `speak` appears but not what was said, and `eval` and `shell` appear without their scripts and output.

What stays is the addresses of your MQTT broker and Home Assistant, the paths of the pages the device opened, and the topic names. Read through the log before posting it somewhere public if those matter to you.

## Inspecting the dashboard

When a dashboard card stays blank or a page will not load, the browser's console and network errors usually say why. **Settings &rarr; Remote Debugging**, off by default, lets a computer look at them while the dashboard runs on the device.

1. Connect the computer to the device with [ADB](https://developer.android.com/tools/adb), over USB or over Wi-Fi.
2. Switch on **Remote Debugging**. It takes effect straight away, without a restart.
3. On the computer:
   - with the standard WebView, open `chrome://inspect` in Chrome and pick the dashboard under the device's name;
   - with GeckoView, open `about:debugging` in Firefox, enable USB devices, and pick the device.

On a device running a custom Android build such as LineageOS (a `userdebug` build), the WebView can always be inspected, whatever the setting says; the setting still controls GeckoView there.

Switch it off again when you are done. While it is on, anything with ADB access to the device can read and change the open page, including the Home Assistant login it holds.
