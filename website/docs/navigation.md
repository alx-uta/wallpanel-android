---
title: Navigating and Logging In
---

## Going back

The back key moves back through the dashboard's history, and out of a fullscreen video.
On the first page it does nothing, so a kiosk is not left by a single press.

Many wall tablets have no back key, or hide it in fullscreen. Under **Settings &rarr;
Navigation**:

- **Swipe From the Edges**: swipe in from the left edge of the screen to go back a page, and
  in from the right edge to go forward. A swipe has to start right at the edge and go a
  little way across the screen. Leave it off if the dashboard uses swipes of its own near
  the edges, such as [hass-swipe-navigation](https://github.com/zanna-37/hass-swipe-navigation).
- **Back and Home Buttons** puts two small buttons in a bottom corner of the screen, the
  one the settings button does not use. Back goes back a page, Home goes back to the
  dashboard as it is set in the settings.

Both step aside while a video is fullscreen.

## Tap sound and vibration

Pages in a browser make no click when touched, unlike Android's own buttons. Two switches
under **Settings &rarr; Navigation** add that feedback to the dashboard:

- **Tap Sound** plays the system click sound on each tap. It follows the system volume
  (**Settings &rarr; Sound** on the device, often called the system or touch volume), not
  the media volume, and it plays whether or not Android's own touch sounds are switched on.
- **Tap Vibration** gives a short vibration on each tap. On Android 13 and later it also
  needs Android's own touch vibration setting (**Settings &rarr; Sound and vibration** on
  the device) to be on. It is greyed out on a tablet with no vibration motor, which is most
  wall tablets.

Only taps count. Scrolling, pinching and long presses give no feedback, so a slider or a
scrolling dashboard does not buzz the whole way along.

## Dashboards behind a login

For a dashboard behind a username and password (HTTP basic authentication, often added by
a reverse proxy), set **Settings &rarr; Dashboard Login &rarr; Username** and **Password**.
The browser gives them to the dashboard's server when it asks, so the dashboard loads
unattended after a reboot, and they stay out of the dashboard address, which WallPanel
publishes to Home Assistant as the Current URL. A username and password left in the address
are taken out before it is published.

They are only given to the servers named in the dashboard address, so a page that links
somewhere else can't collect them. If the server turns them down, or no login is set, the
browser asks on screen instead.

## Cookies in embedded pages

A dashboard often embeds other pages, such as an iframe card showing a Squeezebox (LMS)
player, Grafana or a camera's web page. To the browser those pages are third-party, and
some of them refuse to work without cookies, with messages like "Your browser does not
allow cookies". **Settings &rarr; Third-Party Cookies**, on by default, lets them keep
cookies, and shares them with the same site opened on its own, so a login made there
carries over to the card. Turn it off and pages from other sites keep no cookies at all.
The setting applies to both browser engines and to the web screensaver, the next time the
page loads.

A recent WebView is stricter still. It only lets an embedded page keep a cookie that the
page itself marks `SameSite=None; Secure`, and a secure cookie needs HTTPS. An embedded page
served over plain `http://` from a different address than the dashboard, as many local
services are, keeps no cookies in it whatever this setting says. GeckoView
(**Settings &rarr; Use GeckoView**) keeps them, and so does an old WebView, such as the
version 84 that Android 8.1 tablets are left with.

## Fullscreen video

A video player's fullscreen button works with both browser engines. While a video is
fullscreen the settings button and the navigation buttons step aside. The back key, or
the player's own button, leaves fullscreen.
