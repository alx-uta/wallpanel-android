---
title: Scheduled Tasks
---

Scheduled Tasks let the device run an action on its own, at a time of day and on the
days you choose, without anything sending it a command. This is useful when you want
the kiosk to reload, switch dashboards, or restart itself on a routine, even if your
home automation system or network is unreachable at the time -- the schedule fires
locally and dispatches through the same command handling MQTT and HTTP commands
already use.

WallPanel is a general kiosk browser, not a Home Assistant-only tool, so a schedule can
point at any URL -- a different dashboard in the morning, a different website in the
evening, a status page overnight, and so on.

## Adding a task

Go to **Settings &rarr; Scheduled Tasks**. The screen lists your existing tasks; tap the
**+** button to add a new one. Each task has:

- **Enabled** -- switch a task off without deleting it.
- **Time** -- tap the time to open the time picker.
- **Days** -- toggle any combination of days of the week. There is no separate "every
  day" option; selecting all seven days is the same as "every day," and at least one day
  must be selected before the task can be saved.
- **Action** -- one of the six actions below.

Tap an existing task to edit it, or use its delete button to remove it. Turning a task
off (or deleting it) cancels the alarm backing it; saving a task arms (or re-arms) the
alarm for its next matching day and time.

## Actions

- **Restart Application** -- kills and relaunches the WallPanel process. WallPanel has
  no root access and isn't provisioned as a device owner, so it cannot trigger an actual
  OS-level reboot; restarting the app itself is the closest equivalent. This is the same
  action as the standalone [`restartApp`](./remote-control/commands.md) MQTT/HTTP
  command.
- **Reload Page** -- reloads the current page, same as the `reload` command.
- **Clear Cache** -- clears the browser cache, same as the `clearCache` command.
- **Relaunch Dashboard** -- browses back to the configured launch URL, same as the
  `relaunch` command.
- **Browse To URL** -- browses to a URL you enter when creating the task, same as the
  `url` command. Use this to switch to a different dashboard or website at a set time.
- **Shell Command** -- runs a shell command you enter when creating the task, same as
  the `shell` command. This only works if **Settings &rarr; HTTP &rarr; Enable Shell
  Commands** is turned on; if it's off, the task editor shows a warning and the task
  will not run until you enable it. See [Shell Command](./remote-control/commands.md#shell-command)
  for what the command can and cannot do -- it runs unprivileged, with no root, exactly
  the same as when it's triggered over MQTT or HTTP.

## Timing

Tasks are scheduled with an inexact Android alarm, so a task can fire a few minutes
after its set time rather than exactly on it. This is deliberate: an exact alarm would
require a runtime permission prompt on Android 12 and above, which WallPanel avoids.
For actions like reloading a page or switching dashboards, firing a few minutes late is
an acceptable trade-off.

Scheduled tasks survive a device reboot -- they are re-armed automatically when
WallPanel starts back up.
