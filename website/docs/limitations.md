---
title: Limitations
---

Android devices use WebView to render webpages, This WebView does not have full feature parity with Chrome for Android and therefore pages that render in Chrome may not render nicely in Wall Panel. For example, WebView that shipped with Android 4.4 (KitKat) devices is based on the same code as Chrome for Android version 30.

This WebView does not have full feature parity with Chrome for Android and is given the version number 30.0.0.0. If you find that you cannot render a webpage, it is most likely that the version of WebView on your device does not support the CSS/HTML of that page. You have little recourse but to update the webpage, as there is nothing to be done to the WebView to make it compatible with your code.

An old WebView also ages against the pages you point it at. Home Assistant's own dashboard is the common case: recent releases of its frontend no longer lay out correctly in the WebView that shipped with Android 8 and earlier, and on a tablet screen the sidebar can end up drawn over the cards. Switching that device to the GeckoView engine in Settings renders the same dashboard correctly, because GeckoView is built into WallPanel rather than supplied by the device. Grafana dashboards are another: current Grafana does not load in the WebView 84 that Android 8.1 devices are left with, and does under GeckoView on the same device.

Setting WallPanel as the default Home application will always load this application as your home. Removing this feature is difficult without uninstalling the application. So please do this is you wish to use the application as a "kiosk" type application.

On Android 10 and later, WallPanel can only bring the dashboard back by itself after a crash, the `restartApp` command or a scheduled restart, or open it on boot, if it may display over other apps. Without that permission, Android blocks the start while the screen is on and leaves the device on the home screen; with the screen off or locked it still works, which is the usual state just after a boot. WallPanel asks for it once, on first start, and Settings → Reopen After a Restart shows whether it is granted and opens the system screen to grant it. Newer Android versions open a list of apps there, where you pick WallPanel Pro.

The back key moves back through the dashboard's history, and out of a fullscreen video. On the first page it does nothing, so a kiosk is not left by a single press.

After a reboot, WallPanel holds the dashboard back until the device is ready, and shows a spinner with "Waiting for the network and the clock…" meanwhile. Some tablets start the application before Android has set the clock, and a dashboard opened while the clock still reads 1970 is one Home Assistant treats as logged out: it discards its saved token and asks you to sign in again. The wait applies only in the first ten minutes after a boot, and ends as soon as the clock is set and a network connection has held for five seconds, which in practice is a few seconds. It needs a connection, not internet access, so a Home Assistant that only answers on your own network is fine. The wait is capped so the screen never sits there: a minute if the device has no network at all, since the dashboard cannot load without one, and five minutes for a clock that never looks right.
