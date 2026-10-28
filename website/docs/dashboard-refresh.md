---
title: Reloading the Dashboard
---

A kiosk page runs for days. These settings, under **Settings**, keep it current and bring
it back to the dashboard when someone has wandered off to another page.

- **Reload After Screensaver** reloads the page whenever a screensaver is dismissed, so
  the values on it are fresh when somebody looks.
- **Return to Dashboard** loads the dashboard again after this many minutes without a
  touch, if another page is showing. Pages Home Assistant redirects to on the way, such
  as its default dashboard, count as the dashboard. A page loaded by the `url` command
  stays up for the same time before it goes back. With a playlist of several pages this
  does nothing, since the playlist moves between pages by itself. 0 turns it off.
- **Reload Interval** reloads the page on view every this many minutes, for a page that
  does not update itself, such as a slideshow or a status page regenerated on a server.
  A reload that falls due while somebody is using the screen waits until they have left
  it alone for a minute, and one that falls due with the screen off happens when it comes
  back on. 0 turns it off.
- **Network Disconnect** reloads the page when the network comes back after a drop.

For a reload or a change of page at set times of day, use
[Scheduled Tasks](./scheduled-tasks.md). The `reload` and `relaunch`
[commands](./remote-control/commands.md) do the same on demand.
