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

## Known debt

- The `gradlew` wrapper script is bash-specific and should be regenerated with
  `gradle wrapper --gradle-version 8.5`.
- `geckoview-nightly:134.+` is a dynamic version against Mozilla's nightly repo, which
  prunes old builds. It resolves today; it will eventually stop resolving.
