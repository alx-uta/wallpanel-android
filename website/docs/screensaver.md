---
title: Screensaver and Brightness Control
---

On some older devices, there is no screensaver support such as Google Daydream that automatically dims the screen.  Therefore the application provides a screensaver feature.  This feature along with the screen brightness option, allows the screen to dim when the screensaver is active.  With the Camera and Motion feature, the device can be automatically awaken when motion is detected.  Optionally, you can send an MQTT command to wake the screen or just touch the screen to deactivate the screensaver.

There is a setting to dim the screen by a set percentage when the screensaver is active. When set to a value above 0%, the screen dims by that percentage, so at 75% it is left at a quarter of its usual brightness.

The Dim Screen Saver works whether or not the screen brightness option is on. With it off, the application dims only its own window: the device's brightness level and its automatic brightness setting are left alone, and the screen goes back to them as soon as the screensaver ends. On Android 8 and older with automatic brightness on, the device does not report the brightness on screen, so the page is shaded by the dim percentage instead. With the screen brightness option on, the application sets the device brightness itself, to the dimmed level during any screensaver and back to the captured level afterwards.

Using the screen brightness option requires some extra permissions.  This is because controlling the devices screen brightness is considered a dangerous permission by Google and so users have to manually turn this on.  When you first select the screen brightness option, you will be taken to the setting on your device to enable the permission.  The screen brightness feature behaves in the following manner:

- There is a general brightness setting that must be enabled (and permissions given) in order for the application to manually set the device brightness.  If at any time you revoke the permissions in the device settings for the application to control the brightness, this option will be disabled.

- The brightness mode can be disabled in the settings. The device goes back to automatic brightness if that is what it was on before the option was enabled, and stays on manual brightness otherwise. If brightness is disabled the application will no longer be able to change the devices brightness level including via MQTT commands.

- Brightness level is read at the time brightness control is enabled and permissions granted. However, there is also a new capture button in the settings to manually capture the devices current brightness level. To use this, first go into the app settings, then adjust your devices brightness level, then press the capture button to save the new brightness level.  The application will then use this brightness level to set the device brightness level.

- If you have brightness enabled, you can at any time manually set a new brightness level using the MQTT commands (see commands section above).  The device will then use the new setting as the default brightness level of the device.

## Clock

**Clock Size** makes the clock screensaver larger, up to three times its normal size. The
clock and the date shrink back as far as they need to fit the screen, so a large clock on
a small or portrait screen is not cut off.

The clock moves to a new place every minute so it does not burn into the screen. That
matters on AMOLED screens and less on LCD ones, which is where **Move Clock** can be
switched off to keep it in the middle.

## Web screensaver

**Web Screensaver** shows a web page of your choice, set in **Web Screensaver Url**. A
touch anywhere on it ends the screensaver, so the page is for looking at rather than using.
**Web Screensaver Zoom** draws it larger or smaller, for a page made for a different screen
size: at 150% everything on it is half as big again. 100% shows the page at its own size.
The screensaver always uses the WebView, whichever engine the dashboard uses, and the zoom
applies the next time the screensaver comes up.

## Reloading after the screensaver

**Settings &rarr; Reload After Screensaver** reloads the page whenever a screensaver goes
away: on a touch, a `wake` command, motion or a face, or the screen coming back on. A
dashboard that has been in the background for hours, or whose connection dropped while
the screen was off, then shows current values. See
[Reloading the dashboard](./dashboard-refresh.md) for the other ways to keep a page fresh.

## Changing the screensaver remotely

The screensaver type, its timeout, the dim level, the web screensaver's page and
**Prevent Sleep** can all be changed by the [`config` command](./remote-control/commands.md#changing-settings),
or from the matching Home Assistant entities, to dim at night and stay bright by day.
