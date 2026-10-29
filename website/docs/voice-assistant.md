---
title: Voice Assistant
---

WallPanel can be a push-to-talk voice assistant for Home Assistant's
[Assist](https://www.home-assistant.io/voice_control/). Tap the microphone button at the
bottom of the dashboard and speak. Home Assistant works out what you said and does it,
and the answer is shown over the dashboard and spoken.

## Setting it up

Under **Settings &rarr; Home Assistant**:

- **Home Assistant Access Token** is required. Create a long-lived access token at the
  bottom of your Home Assistant profile page.
- **Home Assistant Address** can stay empty when the dashboard is served by Home Assistant.
  Its address is used.
- **Voice Assistant** shows the microphone button.
- **Assist Pipeline** picks one of the assistants set up under Home Assistant's **Settings
  &rarr; Voice assistants**, by name or id. Empty uses the one marked as preferred there.

The first tap asks for the microphone permission.

## How it listens

The speech is turned into text by whichever of these is available:

1. **Home Assistant's own speech-to-text**, when the assistant has one: Whisper,
   Home Assistant Cloud, or any other. WallPanel streams the microphone to Home Assistant,
   which also decides when you have stopped talking.
2. **The tablet's speech recogniser**, when the assistant has none, for instance the one
   that comes with the Google app. Only the text it hears is sent to Home Assistant.

With neither, the button says there is no speech-to-text. Many tablets without Google's
apps have no recogniser of their own, so for those, add a speech-to-text engine to the
assistant in Home Assistant.

Listening stops when you stop talking, after 15 seconds at most, or when you tap the
button again. A tap while the answer is playing stops it.

## How it answers

The answer is shown over the dashboard and played from the audio the assistant's
text-to-speech makes, at the media volume. When the assistant has no text-to-speech, or its
audio can't be played, the answer is spoken the way the `speak` command speaks, through
[Home Assistant](./remote-control/commands.md#speaking-through-home-assistant) or the
tablet's own voice.

When Home Assistant asks something back, "Which light?" for instance, WallPanel listens
again straight away. A question asked within five minutes of the last one carries on the
same conversation.

## Starting it from Home Assistant

The `{"assist": true}` [command](./remote-control/commands.md) starts listening as a tap
on the button does, and with [MQTT Discovery](./remote-control/sensors.md#home-assistant-discovery)
there is a **Voice Assistant** button entity for it, for a physical button or an
automation. It only works with **Voice Assistant** switched on, and only while the
dashboard is on screen. Sent together with `{"wake": true}`, it waits a few seconds for
the screen to come on.

## The dashboard's own Assist button

Home Assistant's dashboard has an Assist dialog of its own, and WallPanel lets the page
use the microphone. Browsers only allow that over `https://`, though, so with a Home
Assistant reached over plain `http://` the dialog has no microphone, and WallPanel's button
is the way to talk to it.

## Limits

This is push to talk. There is no wake word.
