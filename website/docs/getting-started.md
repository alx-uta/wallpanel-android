---
title: Getting Started
sidebar_position: 1
---

## Installation

You can either side load the application to your device from the [release section](https://github.com/alx-uta/wallpanel-android/releases) or clone the repository and build the application using Android Studio.

:::important
If you have need support for older Android 4.0 devices (those below Android 4.4), you want to use the [legacy version](https://github.com/thanksmister/wallpanel-android-legacy) of the application. Alternatively you can download an APK from the release section prior to release v0.8.8-beta.6
:::

## Using the App

To begin using WallPanel, you need to do two things.  First enter the settings screen and reset your settings password. The default code to enter the settings is **`1234`**. There is a floating button on the screen in the lower-right corner.

![Settings Button](/img/settings_button.png)

Once you enter the settings screen, you can change the settings code to any 4-digit code. By default, the code is required whenever Settings open: from the dashboard button, the `settings` command, the Home Assistant Settings button or the app shortcut in the launcher. You can disable this requirement in the Settings Button section if your device does not need that protection. Now edit the Dashboard url, this is the web site or home automation platform dashboard you want to use with the application. Note that initially, the application shows the Github page for the project.

![Setting Code](/img/settings_dashboard.png)

You also have the option in the settings to change the location of the settings button to any of the four corners of the application, as well as make the button transparent.  If you make the button transparent, you must recall the last location of the button in order to press the now invisible button on your screen. 

**Settings Disabled** removes the button altogether, and asks before it does. Settings can then still be opened by long-pressing the WallPanel icon in the launcher and picking **Settings** (Android 7.1 and later), with the [`settings` command](./remote-control/commands.md) over MQTT or HTTP, or with the Settings button WallPanel adds to Home Assistant through MQTT discovery. The security code is asked for on each of them.

![Setting Button Options](/img/settings_button_options.png)

Once you have setup the code and dashboard url, each time you enter the settings, you will be prompted to enter the code unless the security-code requirement is disabled.

![Settings Code](https://github.com/thanksmister/wallpanel-android/blob/master/img/settings_code.png)

## Copying settings to another device

**Settings &rarr; Backup &rarr; Export Settings** saves the settings to a JSON file, to set
up another tablet the same way, or this one again after a reinstall. Passwords, access
tokens and the settings code are left out unless you tick **Include passwords, access
tokens and the settings code**. Keep a file with them in it somewhere safe.

**Import Settings** reads such a file and restarts WallPanel. The settings in the file
replace the device's, and anything the file does not have is left as it is. By default the
device keeps its own MQTT client ID, base topic and device name: two devices sharing a
client ID knock each other off the broker, and with the same base topic both would take
each other's commands. Untick **Keep this device's MQTT client ID, base topic and device
name** when restoring a device from its own backup.

