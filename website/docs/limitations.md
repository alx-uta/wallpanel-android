---
title: Limitations
---

Android devices use WebView to render webpages, This WebView does not have full feature parity with Chrome for Android and therefore pages that render in Chrome may not render nicely in Wall Panel. For example, WebView that shipped with Android 4.4 (KitKat) devices is based on the same code as Chrome for Android version 30.

This WebView does not have full feature parity with Chrome for Android and is given the version number 30.0.0.0. If you find that you cannot render a webpage, it is most likely that the version of WebView on your device does not support the CSS/HTML of that page. You have little recourse but to update the webpage, as there is nothing to be done to the WebView to make it compatible with your code.

An old WebView also ages against the pages you point it at. Home Assistant's own dashboard is the common case: recent releases of its frontend no longer lay out correctly in the WebView that shipped with Android 8 and earlier, and on a tablet screen the sidebar can end up drawn over the cards. Switching that device to the GeckoView engine in Settings renders the same dashboard correctly, because GeckoView is built into WallPanel rather than supplied by the device.

Setting WallPanel as the default Home application will always load this application as your home. Removing this feature is difficult without uninstalling the application. So please do this is you wish to use the application as a "kiosk" type application.

After a reboot, WallPanel holds the dashboard back until the device is ready, and shows a spinner with "Waiting for the network and the clock…" meanwhile. Some tablets start the application before Android has set the clock, and a dashboard opened while the clock still reads 1970 is one Home Assistant treats as logged out: it discards its saved token and asks you to sign in again. The wait applies only in the first ten minutes after a boot, and ends as soon as the clock is set and a network connection has held for five seconds, which in practice is a few seconds. It needs a connection, not internet access, so a Home Assistant that only answers on your own network is fine. The wait is capped so the screen never sits there: a minute if the device has no network at all, since the dashboard cannot load without one, and five minutes for a clock that never looks right.
