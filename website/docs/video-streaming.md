---
title: Webcam MJPEG Video Streaming
---

If video streaming is enabled, then the stream can be accessed with this URL:

```plain
http://yourip:2971/camera/stream
```

The endpoint answers `503` while the camera is switched off, and while it is switched on
but not running -- when the camera permission has not been granted, or the camera could
not be opened.

## Frame rate

**Settings &rarr; HTTP &rarr; MJPEG Stream FPS** caps how many frames a second the stream
sends, from 1 to 30. It never sends more than the camera takes (**Settings &rarr; Camera
&rarr; Camera FPS**). Every frame is converted, rotated and compressed on the CPU, so raise it
only as far as the device keeps up.

Until it is set, it follows what earlier versions sent, which depended on the camera FPS:
about 2 frames a second at a camera FPS of 10 or less, 3 at 15, 4 at 20, and as many as
the camera gives above that.

Frames are only encoded while somebody is watching. With no stream open the camera still
runs motion, face and QR detection, without the cost of the stream.

## Resolution

**Settings &rarr; Camera &rarr; Camera Resolution** offers 320x240, 640x480 and 1280x720. A
camera without the size picked opens at the closest one it has. Motion detection samples
every frame down to about 320x240, so it behaves the same at every resolution and its
**Minimum Luma** setting means the same brightness at each.

For a sense of the cost, a TD-1050 PRO (Android 8.1) running the
dashboard with motion detection on and a 3 frame a second stream measured, as a share of
one CPU core:

Resolution | No stream open | One stream open
-|-|-
320x240 | 5% | 10%
640x480 | 5% | 13%
1280x720 | 17% | 40%

1280x720 is there for devices with the headroom for it.

## Motion boost

**Settings &rarr; Camera &rarr; Motion Detection &rarr; Motion Boost** runs the camera at a
low resolution while nothing is happening, and switches it to a higher resolution and
frame rate while there is motion:

- **Boost Resolution** and **Boost FPS** are what the camera switches to when motion is
  detected.
- **Boost Hold Time** is how long after the last motion it switches back. Motion during
  the hold starts it again.

It needs motion detection on. The camera only takes a new size when it opens, so each
switch restarts it, and the stream pauses for two to three seconds on an older device.
The first moments after the camera opens are not reported as motion, because the
exposure is still settling, so switching back does not set off another boost.

The `cameraResolution` and `cameraFps` [commands](./remote-control/commands.md#camera-resolution-and-frame-rate)
override either half, from Home Assistant or anything else that can send a command.
`cameraBoosted` and `cameraResolutionActive` in the
[application state](./remote-control/sensors.md#application-state-data) show what the
camera is doing.
