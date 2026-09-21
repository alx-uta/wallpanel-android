# WallPanel — agent notes

Android kiosk browser for Home Assistant dashboards. Single Gradle module,
`WallPanelPro`, package `xyz.wallpanel.pro`.

## Toolchain — read this first

Everything runs in Docker. Nothing needs a JDK, the Android SDK or Node on the host.
[tools/run.sh](tools/run.sh) is the entry point:

```bash
./tools/run.sh android ./gradlew assembleProdDebug      # debug APK
./tools/run.sh android ./gradlew testProdDebugUnitTest  # JVM unit tests
./tools/run.sh android ./gradlew lintProdDebug          # Android Lint
./tools/run.sh node    npm run build                    # Docusaurus site
```

The `android` image carries `python3` alongside the JDK and SDK, because the scripts that
need it also need `adb` -- driving the UI by parsing `uiautomator dump`, for instance. It
is not a separate service for that reason. `node` has no Python.

Two things that will bite you:

- Invoke the wrapper as `./gradlew`, **not** `sh gradlew`. The script is bash-specific
  (bash arrays, `function` keyword); `/bin/sh` in the container is dash and fails at
  line 154.
- `gradlew` must keep LF endings. [.gitattributes](.gitattributes) enforces this.
  With CRLF it dies as `gradlew: 2: : not found`.

The container mounts [tools/android/container.local.properties](tools/android/container.local.properties)
over `local.properties`, because Gradle prefers `sdk.dir` there over `ANDROID_HOME`.
Your own `local.properties` is untouched.

`/root/.gradle` and `/root/.android` are named volumes. The second one matters more than
it looks: it holds `debug.keystore` and `adbkey`. Without it every `run --rm` mints a
fresh debug key, so an app APK and a test APK built in separate invocations carry
different signatures and instrumentation fails with *"does not have a signature matching
the target"* — and every device would re-prompt for USB-debugging authorisation.

## Test devices

Instrumented tests run against real devices on the LAN, listed in `tools/devices.json`
(gitignored; copy [tools/devices.example.json](tools/devices.example.json)):

```bash
cp tools/devices.example.json tools/devices.json   # then fill in addresses
./tools/instrument.sh                              # all enabled devices, prod flavor
./tools/instrument.sh -d kitchen                   # one device by name
./tools/instrument.sh -f qa                        # different flavor
```

Set `"enabled": false` to skip a device rather than deleting the entry — JSON has no
comment syntax, which is also why each device carries a `notes` field.

The container runs its own adb server and connects out to the devices over TCP, so
`connectedAndroidTest` works natively and Gradle produces its usual reports in
`WallPanelPro/build/reports/androidTests/connected/`. A device must have adb over TCP
listening (`adb tcpip 5555`, once, over USB) and must have authorised the container's
adb key.

Keep at least one Android 8.1-era device enabled — it is what catches `minSdk 21`
regressions that never show up on a modern device.

## Home Assistant discovery checks

[tools/ha-verify.py](tools/ha-verify.py) checks MQTT discovery against a real broker and
a real Home Assistant. Plain Python 3, no third-party packages, runs on the host. It does
import [tools/mqtt_minimal.py](tools/mqtt_minimal.py), a standard-library-only MQTT 3.1.1
client living next to it, so the two move together:

```bash
./tools/ha-verify.py                        # clientId from local.testconfig.properties
./tools/ha-verify.py --client-id wptest     # a device configured with its own client id
./tools/ha-verify.py --base-topic wallpanel/kitchen/   # a device with a custom base topic
./tools/ha-verify.py --exercise             # also drive the controls, then put them back
```

It reads the retained discovery configs off the broker, then asks Home Assistant whether
it built a device and an entity for each one, whether any are unavailable, and whether
their states match what the published payloads should render to. `--exercise` goes
further and calls Home Assistant services against the controls, which is the only way to
cover the `command_template`s — Home Assistant, not the app, is what renders those.

Point it at a device configured with a throwaway `clientId`/`baseTopic` rather than a
real one. It creates a real device in Home Assistant, and clearing that means clearing
the retained configs (turning MQTT Discovery off on the device does exactly that).

`--exercise` changes brightness, volume, the screensaver and the loaded URL, and puts
each back afterwards. It leaves text-to-speech and the camera alone on purpose: one makes
the device talk out loud, the other switches on a camera in somebody's house.

## Build configuration

| | |
|---|---|
| AGP | 8.2.2 |
| Gradle | 8.5 (wrapper) |
| Kotlin | 1.9.22, kapt |
| JDK | 17 (container), bytecode target 17 |
| compileSdk / buildTools | 35 / 35.0.0 |
| minSdk / targetSdk | 21 / 33 |

Three flavors: `dev` (reads credentials from `local.testconfig.properties`), `qa`, `prod`
(hard-coded defaults). Use **prod** for routine verification — it needs no local config.
ABI splits produce per-architecture APKs plus a universal one.

For testing against a real Home Assistant instance and MQTT broker, copy
[local.testconfig.properties.example](local.testconfig.properties.example) to
`local.testconfig.properties` (repo root, gitignored) and fill in
`hassUrl`/`broker`/`brokerUsername`/`brokerPass`/etc. A `dev` debug build reads it once,
on first launch, to seed `Configuration` — same copy-the-example-then-fill-in-real-values
convention as `tools/devices.json`. `local.properties` itself is reserved for Gradle/SDK
configuration (see `tools/android/container.local.properties`); test credentials live in
their own file so they read the same on the host and in Docker.

`hassToken` in that same file is the one key no build reads. It is a Home Assistant
long-lived access token used by the MQTT discovery end-to-end check
([tools/ha-verify.py](tools/ha-verify.py)), which needs the Home Assistant API to
confirm the entities were really created and hold the states expected — publishing the
right payload to the broker and Home Assistant accepting it are different claims. Without
it [tools/ha-verify.py](tools/ha-verify.py) stops with an error, since every check it
makes needs the API; the builds themselves do not care.

## Architecture

- **DI is Dagger 2.** Activities extend `DaggerAppCompatActivity`. New injectable types
  need `@Inject constructor` *and* registration in `di/AndroidBindingModule.kt` —
  forgetting the binding gives a runtime crash, not a compile error.
- **`Configuration.kt` owns all settings.** Never touch `SharedPreferences` directly. A
  new setting means: a property there, `key_setting_*` and `default_setting_*` strings,
  and an entry in the right `res/xml/pref_*.xml`.
- **`WallPanelService.kt` is the control plane.** MQTT and HTTP both funnel into
  `processCommand(JSONObject)`. HTTP server listens on port 2971; MQTT topic base is
  `wallpanel/[baseTopic]/command`. New commands need a constant in `MqttUtils.kt`, a
  branch in `processCommand`, and a docs page update.
- **`MqttDiscovery.kt` owns the Home Assistant entities.** It returns the full entity
  list, a null `config` meaning "remove this one", and the service publishes each to its
  discovery topic. A control is just a discovery entity aimed at the same command topic,
  so exposing a command means adding an entry there — the entity list must stay complete,
  since it is also what clears retained configs for switched-off features.
- **Two browser engines coexist**, WebView and GeckoView, both in
  `activity_browser.xml` with visibility toggling, chosen at runtime by
  `Configuration.useGeckoView`. `GeckoWebClientAdapter` maps GeckoView's API onto the
  shared `WebClientCallback` interface. Browser changes generally need to land in both.
- **MQTT is HiveMQ**, with `MQTT3Service`/`MQTT5Service` behind `MQTTModule`, selected
  by `mqttOptions.getVersion()`. The `android-retrofix` plugin backports
  `CompletableFuture`/Streams so HiveMQ works below API 24.
- **Logging is Timber** (`Timber.d/i/e`), never `android.util.Log`.

## Conventions

- Apache 2.0 header on new source files.
- Preference keys `key_setting_<category>_<name>`, defaults
  `default_setting_<category>_<name>`, preference XML `pref_<category>.xml`.
- Settings fragments extend `BaseSettingsFragment`; changes apply on activity restart
  via `hasSettingsUpdates()`.
- This is a kiosk that runs unattended for weeks. Favour defensive handling over
  crashing, and be deliberate about wakelocks, camera handles and leaks.

## Agents

- `app-developer` (Opus) — features, fixes, refactors under `WallPanelPro/`
- `app-tester` (Sonnet) — unit and instrumented tests, lint, device runs
- `docs-writer` (Sonnet) — `website/docs/`, README, this file

### Review bar

A code review of this repo reports defects a user would notice: a crash, a hang, a leaked
camera or wakelock, a command that does nothing, an entity Home Assistant gets wrong, a
race that can drop or duplicate a published message. Say what breaks and how to reach it.

Leave out naming, formatting, comment wording, and micro-optimisations of code that runs
once. Performance is worth raising when it lands on the main thread in a measurable way —
this is a kiosk on decade-old hardware — but bring a number, not a suspicion. A pre-existing
problem the change does not touch belongs in a note at the end rather than the findings.

## Known debt

- The `gradlew` wrapper script is bash-specific and should be regenerated with
  `gradle wrapper --gradle-version 8.5`.
- `geckoview-nightly:134.+` is a dynamic version against Mozilla's nightly repo, which
  prunes old builds. It resolves today; it will eventually stop resolving.
