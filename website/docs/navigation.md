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

## Fullscreen video

A video player's fullscreen button works with both browser engines. While a video is
fullscreen the settings button and the navigation buttons step aside. The back key, or
the player's own button, leaves fullscreen.
