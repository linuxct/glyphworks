# Technical details

Everything the [README](../README.md) leaves out: the interface, the first run, the build flavours,
the tests, how the code is laid out, and the fine print on the Essential Key.

The toys have their own page — [TOYS.md](TOYS.md) — and so does the design file format:
[glyph-design-format.md](glyph-design-format.md).

## The interface

A Jetpack Compose app styled to feel native to Nothing OS, with four tabs behind a floating pill:

- **Glyph Toys** — one card for each toy. Drag the handle to reorder the cycle. The **Play** button
  puts that toy on the matrix. A dot marks the toy that the matrix shows now. A switch turns a toy
  off, and a gear opens the settings of that toy.
- **Create** — one card for each of your designs, with the name, the author, the frame count, the
  sizes and the last edit. The ⋮ menu holds show on matrix, duplicate, delete, export and share. An
  import action sits at the top. The first visit offers the guided demo one time.
- **Settings** — the **Initial setup** checklist, then the **App settings**: the key capture switch,
  Menu mode, the 12-hour clock, the Glyph brightness, your creator name and the update check. The
  checklist checks the always-on toy through the system's real toy binding. It collapses once every
  row is a check mark, and opens again while a row is open.
- **Tutorials** — short guides for the hard parts.

### Tutorials

- **Essential Key tutorial** — an animation in pure Compose, with no image assets. A phone lies face
  down and shows its camera island, its Glyph Matrix and its Essential Key. Short timelines repeat
  what one, two and three presses do, in both modes, at the real blink rate.
- **Create your own design** — a guided demo, not a page of text. It opens the real Create tab and
  the real editor on a temporary design, then performs each gesture for you: the **+** button, the
  questions for a new design, a stroke, an undo, a duplicate, a new frame, a drag, a frame duration,
  then **Design settings** for key mode and repeat. A spotlight and one line of text follow along.
  Nothing is saved, and it never touches the matrix.
- **Hand over the Essential Key** — the steps in the system settings.
- **Restricted settings** — the steps to unlock a sideloaded app, with a button to App info.

### Theme

The theme is monochrome — black, white and greys. Contrast does the work that colour usually does:
state, selection, errors, emphasis. Three exceptions, and no more:

1. The **+** button on the Create tab, in Nothing red `#D71921` and blue `#110E56`. An AGSL shader
   warps a field of sines into red and blue lobes. It draws only while the button is on screen and
   the app is resumed.
2. The small red dot on the phone illustration. It is a picture of a square on the back of the real
   phone, not an accent.
3. The setup badge: a 16 dp `#D71921` disc with a white **!**. It appears on the Settings chip while
   the checklist has an open row. It's the only place a hue means something, and it's never the only
   signal. The chip also reads out as "Settings, setup incomplete".

The greys come from Nothing OS Settings in both modes: a `#F2F2FA` page with white cards in light
mode, and a black page with `#191C20` cards in dark mode. The **NType82-Regular** headline font is
not in the repository. The app loads it at run time from `/system/fonts`, so the title matches the
system, and no proprietary file enters the repository.

### Quick Settings tile

A "Capture Essential Key" tile turns key capture on and off from the shade. It also works on the
lock screen.

## The first run

The very first launch opens onboarding instead of the main screen. An animated copy of the Glyph
Matrix heads each page: a disc of 489 LEDs, a 25×25 grid under a circular mask. It lights up in a
pseudo-random order and draws the art for that page.

The pages, in order. **Next** skips any step, and the main screen holds all of them again:

1. **Take over the Essential Key** — what the accessibility service does, and what it does not do.
   It shows a live status line, a button to the Accessibility settings, and a card for sideloaded
   installs with a direct button to App info.
2. **Put GlyphWorks on the matrix** — the always-on toy, and a deep link to the picker of the system.
3. **Permissions** — optional access for posting Timer notifications, the microphone, approximate
   location and exact alarms, plus separate notification-listener access for the Notifications toy.
   **Set up Weather** opens the shared weather settings during onboarding: explicit opt-in,
   approximate location, then the explanation and link for Android's separate background-location
   grant. The same controls are always available under **Settings → Initial setup → Weather &
   location**, including before weather is enabled. States refresh on returning from Android Settings.
4. **Key mode** — Regular mode or Menu mode. This page appears only when the service is on. A
   **"How do they work?"** button opens the animated tutorial.
5. **Ready-made toys, or your own** — the toys in the app, the Create tab and the Tutorials tab. It
   does not explain the editor, because the guided demo does that. A **Take me to Create** button
   ends the flow on that tab.
6. **Welcome** — a recap of your setup, then the app.

The flow re-reads system state each time you come back from Settings, so the status lines and the
conditional page stay correct. Finishing the flow sets a preference. Until then, MainActivity sends
you back to onboarding.

**Make Ambient your own** in Tutorials uses the shared `TutorialInfoDialog` presentation. It explains
background selections and ordering, manual and 15-second cycling, menu behavior, overlay priority,
and setup for Notifications and Weather. Its action opens the same Ambient settings dialog used by
the Glyph Toys page.

## The two flavours

The build has one flavour dimension, `distribution`, with **`github`** and **`play`**.

The Play build ships without the design assistant and without the update check. `src/github/`
holds `ai/`, `core/ai/`, `update/`, the AI dialogs and their strings; `src/play/` excludes them.
Starting with 3.3.0, **Weather is in both flavours** and `INTERNET` is declared in the common
manifest. Optional weather requests send approximate coordinates to Open-Meteo, which also receives
the connection's IP address. The [privacy policy](privacy.md) describes those requests and caching.

Review both merged manifests and the compiled Play classes when checking flavour separation.
The Play APK should contain the weather client and the new listener, with no assistant or updater
classes. Internet permission is now expected in both APKs; its absence is no longer a valid check.

Each excluded entry point is a seam. One function has two declarations with the same signature: a
real one in `src/github/…/ui/OptionalFeatures.kt`, and an empty one in
`src/play/…/ui/OptionalFeatures.kt`. `src/main` always calls those, and never names an AI type or an
updater type. Only a build of the other flavour proves the two files agree, so CI builds both.

`testPlayDebugUnitTest` runs fewer tests than `testGithubDebugUnitTest`. That's on purpose, not a
gap. The AI and updater tests live in `src/testGithub/` and test code the Play build doesn't hold.

## R8

The release build runs R8 for code and resources. This cuts the Compose runtime by a factor of ten.
R8 never removes the logs, because that needs an `-assumenosideeffects` rule, and
`app/proguard-rules.pro` forbids one. That file also keeps the Glyph SDK, the frozen component names
and `DebugLog`.

The official Glyph Matrix SDK sits at `app/libs/glyph-matrix-sdk-2.0.aar`.

## Tests and ASCII goldens

Data ports hide the platform, so every toy draws in pure Kotlin. The JVM tests draw each toy at
13×13 and 25×25, then compare the result to an **ASCII golden file** in
`app/src/test/resources/goldens/`. Open one and you can see the frame with your own eyes.

For the new information toys, `InformationScreensTest` also writes enlarged panel sheets and
replayable weather SVGs to `app/build/reports/information-toys/`. These use the same rendered frames
and physical `PanelMask` as the assertions. Preview generation lives in the JVM tests; no device or
separate image-generation tool is needed. The Phone (4a) Pro artwork uses 13×13, then Phone (3) gets
its own 25×25 design.

After an intended visual change, update only the affected goldens, inspect the resulting artwork,
and run the test again in comparison mode:

```sh
./gradlew :app:testGithubDebugUnitTest --tests '*InformationScreensTest' -DupdateGoldens=true
./gradlew :app:testGithubDebugUnitTest --tests '*InformationScreensTest'
```

The notification counter tests cover duplicate posts, removals, grouping, excluded entries and
listener lifecycle. Weather repository tests exercise refresh timing, provider responses, movement,
cache expiry and permission changes without real network or location access. Ambient tests use
fake elapsed time for manual cycling, 15-second boundaries, overlays and hidden backgrounds.
Notification presentation tests cover all four styles, bell/count marquee boundaries and visibility
resets, and parity with Ambient. Preferences schema 4 removes `ambientChargingStyle` without
copying it over `batteryShowWatts`; Battery, Ambient's charging overlay, and its Battery gauge
background use one preference-aware renderer.

## CI and releases

Two workflows live in `.github/workflows/`:

- **CI** (`ci.yaml`) — builds and tests both flavours on each push and pull request, then uploads
  the two debug APKs.
- **Release** (`release.yaml`) — a manual run. It decodes the keystore from the repository secrets
  (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`), builds the `github` APK and
  the `play` bundle from one commit with one key, and publishes a release with the tag
  `v<versionName>`:

  | Asset | What it is |
  |---|---|
  | `glyphworks-<version>.apk` | The sideload build. Install this one. |
  | `glyphworks-<version>-play.aab` | The Play build for the Play Console. You cannot install it. |
  | `glyphworks-<version>-play.apk` | The same Play build as an APK, so you can put it on a phone. |

The signature belongs to the `release` build type, so both variants take the same certificate. The
workflow verifies the signature before it creates the tag. Without a `keystore.properties`, Gradle
writes an unsigned release instead of failing, and the Play Console rejects that hours later. Put a
`keystore.properties` in the repository root for the same setup on your machine.

### Version 3.3.0 release and listing notes

Version 3.3.0 uses version code **21**. Its release text should highlight selectable Ambient
backgrounds, single-press cycling, optional 15-second cycling, Notifications (0–9 / 9+), and Weather
with the icon/temperature marquee. Both new toys are standalone and Ambient backgrounds. There are
20 registered toys; Rock Paper Scissors remains excluded from the registry.

Before publishing this version:

- Update the store description and privacy link for optional Weather in both builds and separate
  notification access. Remove any claim that the current Play build cannot access the internet.
- Review the Play Console Data safety answers against the actual release: optional approximate
  coordinates sent to Open-Meteo, provider IP/log handling, and local-only notification metadata.
  The old blanket “no data collected” declaration must not be carried forward without this review.
- Describe the background-location use case as keeping the user-enabled Weather display current
  when the phone moves while locked/AOD. Provide the required review material showing Weather
  opt-in, the in-app explanation, Android's separate grant, and the resulting display. Foreground
  location remains usable without that additional grant.
- Keep the accessibility disclosure specific to Essential Key control. Notification access uses
  its own Android service; neither notification data nor accessibility events enter weather requests.

Open-Meteo's free service supports noncommercial apps without advertising or subscriptions, fitting
GlyphWorks' permanently noncommercial distribution. Retain its data attribution and review its
[current terms](https://open-meteo.com/en/terms) if distribution or request volume changes.
AGPL-3.0 continues to cover GlyphWorks' source; weather data uses CC BY 4.0.

## Essential Key coexistence

The accessibility service watches window events from the Essential Space and Essential Recorder
packages. On some firmware the system acts on the key before the key filter can consume it. GlyphWorks
then closes the pop-up: BACK when the phone is unlocked, HOME when it's locked.

The clean answer is still the hand-off in the system settings. Keep both apps on.

## Layout of the code

```
app/src/main/kotlin/space/linuxct/glyphworks/
├── core/      GlyphLink (SDK binding + self-repair), ScreenManager, SessionArbiter,
│              scheduler, prefs (device-protected storage), ports
│   ├ design/  The glyph.design format: model, codec, validation, cell encoding
│              (pure Kotlin, JVM tests — see glyph-design-format.md)
│   ├ ambient/ Stable background IDs, selection and elapsed-time cycling
│   ├ notifications/ In-memory notification counting model
│   └ weather/ Weather snapshots, provider parsing, caching and refresh policy
├── designs/   The design file store (device-protected, atomic writes) and its port
├── matrix/    Pure-Kotlin drawing primitives and a 3×5 dot font
├── screens/   All toys, and the ambient/ compositor with its backgrounds
├── key/       Essential Key accessibility service, click count, action routes,
│              Quick Settings tile
├── toy/       The system Glyph Toy service, and the alarm backstop for the timer
├── audio/     The shared FFT engine
├── sensors/   Shake, tilt, incline, compass and light
├── notifications/ Android notification listener and access checks
├── weather/   Open-Meteo client, approximate-location updates and private cache
└── ui/        Compose UI: the tabbed main screen, the onboarding, the Essential Key
               tutorial, the setup guides, the design list with import and export,
               design/ (the pixel editor, the canvas and the timeline),
               theme/ (the monochrome Nothing style and the run-time NType82)
```

The assistant and update checker live under `app/src/github/kotlin/space/linuxct/glyphworks/`.

## Key behaviour, in detail

- Presses within **400 ms** of each other count as one gesture. So a single press acts about 400 ms
  after you let go, because the app has to wait and see whether a second press arrives. Each press
  it recognises gives a short vibration.
- A single press only fires on interactive toys (the ✅ rows in [TOYS.md](TOYS.md)). Double and
  triple press work on every toy.
- Key capture is a master switch, with a Quick Settings tile beside it. While capture is on, every
  press is consumed and Essential Space never sees the key. Turn it off and the key is untouched.
  There is no interception at all.

In **Menu mode**, the picker adds a few rules:

- A single press moves the preview to the next toy, and does **not** fire that toy's action.
- A double press confirms and closes the picker; the blinking stops.
- A triple press leaves the picker and goes back to Ambient.
- After **5 seconds** with no press, the previewed toy is selected anyway. Every press restarts that
  countdown.

Outside the picker, Menu mode changes nothing: single press still fires the toy action, triple press
still goes home. Only the double press is repurposed.

## Debugging

Everything — key capture, click routing, toy switches, the Glyph service binding — logs under one
tag, in release builds too:

```sh
adb logcat -s GlyphWorks
```

The scan code of the Essential Key varies between firmware revisions; 250 and 304 have both been
seen. An unrecognised hardware key is logged with its scan code, so if your unit uses a new one, the
log shows it and it can be added to `KNOWN_SCAN_CODES`.

To replay onboarding (this resets the completed flag and routes through the real first-launch path):

```sh
adb shell am start -S -n space.linuxct.glyphworks/.ui.MainActivity --ez restart_onboarding true
```
