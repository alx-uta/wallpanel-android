---
title: Lockdown
---

A wall tablet is easy to leave: a swipe down opens the notification shade and its quick
settings, and Home or Recents leave the dashboard for the rest of the tablet. **Settings
&rarr; Lockdown &rarr; Lockdown** keeps WallPanel on screen instead. It works in one of
two ways, depending on how much Android lets WallPanel do.

## Screen pinning

On any tablet, Lockdown pins WallPanel to the screen. Android asks to confirm the first time
the dashboard comes up, and while pinned, Home, Recents, the notification shade and other
apps stop working. The settings screen, the voice assistant and the file picker for
[settings backup](./getting-started.md#copying-settings-to-another-device) still work, since they open inside WallPanel.

Pinning is meant for handing a phone to someone, so it can always be undone on the device:
Android says how when it pins. Holding Back and Recents together is the usual way, or swiping
up and holding with gesture navigation. A pin undone on the device comes back the next time
the dashboard does, after the settings or a restart for instance, and asks again.

## Device owner

For a real kiosk, make WallPanel the tablet's *device owner*. Lockdown then locks the
screen without asking, and nothing on the device undoes it: the only ways out are
turning Lockdown off in the settings, the `lockdown` setting by command, or `adb`. As the
device owner WallPanel is also the home app while Lockdown is on, so a reboot lands on the
dashboard, and it can reboot the tablet with the `reboot` command.

A device owner is set once, over `adb`, on a tablet that has no accounts added
(**Settings &rarr; Accounts** in Android; remove them first, or set it up before adding
any):

```bash
adb shell dpm set-device-owner xyz.wallpanel.pro/.DeviceAdmin
```

Android answers `Success: Device owner set to package ...`. If it refuses because accounts
are present, or because the tablet was already set up with several users, removing those is
the only way short of a factory reset.

To give it up, tap **Settings &rarr; Lockdown &rarr; Device Admin**. WallPanel cannot
then be made the device owner again without `adb`, and Android may need the accounts gone
again. A device owner can't be uninstalled until it has been given up.

### Opening other apps

While locked down, a [link to another app](./launch-external-apps.md) does nothing but
show "Lockdown does not allow that app". As the device owner, **Apps Allowed in Lockdown**
takes package names separated by commas, `com.spotify.music, org.videolan.vlc` for
instance, and those apps may open. Home brings the dashboard back from them.

Screen pinning allows no other apps.

### Leaving

**Exit WallPanel** and the `exit` command let go of the lock first, and as the device
owner WallPanel stops being the fixed home app until it next opens, so another launcher can
be picked.

## Turning the screen off

`{"wake": false}` only lets Android's own display timeout run, as an ordinary app cannot
blank the screen. As a *device admin* it can: `{"screenOff": true}` turns the screen off
the way the power button does, and the **Screen Off** button appears in Home Assistant. A
wake command, motion or a face turn it back on with the dashboard over the lock screen.

Tap **Settings &rarr; Lockdown &rarr; Device Admin** and confirm on the screen Android
shows. A device owner is always a device admin. Tapping it again gives device admin up.

With a PIN or pattern set, turning the screen off locks the tablet the same way the power
button does, so opening the settings afterwards asks for it.

## From Home Assistant

With [MQTT Discovery](./remote-control/sensors.md#home-assistant-discovery), **Lockdown** is
a switch, so a routine can let the tablet go for someone to use it and lock it again
later. With screen pinning, someone has to confirm on the tablet when it locks.
