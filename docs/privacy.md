# Privacy Policy — GlyphWorks for Nothing

Applies to **GlyphWorks for Nothing** (`space.linuxct.glyphworks`), version 3.0.0 and later, as
distributed on Google Play. Last updated: 2026-08-08.

This covers the Google Play build only. The GitHub build adds two features that use the network: an
AI design assistant and an update checker. If you installed the app anywhere other than Google Play,
read [Other builds](#other-builds) instead.

## No data collected, no data shared

**This app collects no data and shares no data.** That's the same answer as on the Play Data Safety
form, and it's meant literally. Nothing about you or your device leaves your phone, because the app
has no way to send it.

There are no accounts, no analytics, no crash reporting, no advertising, and no third-party SDK that
contacts a server.

## You can check this, not just trust it

The Play build doesn't request `android.permission.INTERNET`. Android only grants network access to
packages that hold it, so the platform refuses every socket this process could try to open. You can
verify that on the exact file you installed:

```
aapt2 dump permissions base.apk
```

`android.permission.INTERNET` does not appear in the output. The app is also open source (AGPL-3.0),
so you can read the code behind every statement below.

## What the app touches, and where it stays

### Microphone

The Music Visualizer and the music-reactive ambient layer read the **output mix** through Android's
`android.media.audiofx.Visualizer`, at a capture size of 256 samples, and take an FFT of it. What
comes back is a set of frequency magnitudes. Those become the heights of the bars on the Glyph
Matrix.

No audio is recorded. Nothing is written to a file, kept in memory beyond the current frame, or
transmitted — the app couldn't transmit it if it wanted to. The capture engine releases itself after
five seconds without a poll, so nothing is captured once an audio toy stops being drawn. Deny the
permission and the visualizer shows its idle pattern.

**Why it asks for the microphone, and why your phone may say the mic is in use.** Two permissions
are involved and the app chooses neither. Android requires `RECORD_AUDIO` for any use of
`Visualizer`, whatever it's attached to. It requires `MODIFY_AUDIO_SETTINGS` to attach to the output
mix — the app changes no audio setting with it. Android also accounts for this under the microphone
app-op, so the privacy indicator can light up and background restrictions apply while the visualizer
runs.

What's being read is the mix your phone is already playing, not the room. The app contains no
`AudioRecord` and no `MediaRecorder`. The only audio APIs in it are `android.media.audiofx.Visualizer`
and `android.media.RingtoneManager` for the Timer chime. Recording the microphone isn't something the
app declines to do, it's something it has no code to do. You can check that in the source linked at
the bottom of this page.

### Location

Two toys ask about your position: the **Compass**, to correct magnetic north to true north, and the
**Solar Path**, to place the sun on its daily arc where you actually are.

Only `ACCESS_COARSE_LOCATION` is requested. The app never asks for a fix and never starts location
updates. It reads `LocationManager.getLastKnownLocation` — a position some other app already asked
for — and caches it for ten minutes, so a toy redrawn many times a second doesn't re-query it. The
coordinates go to `android.hardware.GeomagneticField` for the declination angle, and to the on-device
solar maths for sunrise and sunset. They are never stored to disk and never leave the device. Deny
the permission and the Compass points at magnetic north while the Solar Path falls back to a nominal
06:00/18:00 day.

### The Essential Key, and the accessibility service

The Essential Key drives the toys. An accessibility service is the only way an ordinary app can
observe a hardware key, including while the phone is locked or on the Always-On Display. The service
exists for that and nothing else.

It is declared `android:canRetrieveWindowContent="false"`, so the platform doesn't give it your
screen contents. It cannot read what's on screen, in this app or any other. It receives key events,
plus one event type — `typeWindowStateChanged` — scoped to two packages
(`com.nothing.ntessentialspace`, `com.nothing.ntessentialrecorder`). That event is used only to close
Essential Space when the system reacted to a press the app had already consumed. It is declared
`android:isAccessibilityTool="false"`, because it isn't an assistive tool and doesn't pretend to be
one.

Key presses are counted in memory and turned into a toy action. They are not logged, not persisted,
and not transmitted.

The service does one thing to another app's window, and that's worth saying plainly. It consumes the
key press so Essential Space doesn't open on top of the toy you just acted on. On firmware where
Essential Space opens anyway, it closes it once, immediately after. That's the only use of the
accessibility API's global actions in this app. It happens only within three seconds of a press
GlyphWorks itself captured, and never while key capture is switched off. The in-app disclosure — the
first screen you see on a new install — says the same thing before you're asked to enable anything.

### Designs you draw

Designs made in the Create tab are `glyph.design` JSON files in the app's own device-protected
storage. They hold your artwork, a name, and your creator name if you set one in Settings. All of it
is text you typed.

The app never uploads a design. Sharing is something you do: the ⋮ menu hands **one** file to
Android's share sheet through a `FileProvider` that is not exported, and the app you pick receives
it. Where it goes after that is between you and that app. Copies staged for sharing sit in the cache
and are cleaned up after a day.

### Settings

Preferences — which toys are enabled, their order, brightness, creator name — are stored in
device-protected `SharedPreferences` on this phone. Uninstalling removes them.

### Notifications and alarms

The Timer schedules an exact alarm as a backstop, so the chime lands even if the app's process has
been killed. It posts a notification when it fires. Nothing about a timer leaves the device.

## Permissions in the Play build

| Permission | Why |
|---|---|
| `com.nothing.ketchum.permission.ENABLE` | Draw on the Glyph Matrix through Nothing's Glyph SDK. |
| Accessibility service (`BIND_ACCESSIBILITY_SERVICE`) | Observe Essential Key presses. Cannot read screen content. |
| `RECORD_AUDIO` | On-device FFT for the visualizer. Optional. |
| `MODIFY_AUDIO_SETTINGS` | Required alongside it: the visualizer attaches to the output mix, and Android gates that on this permission. It changes no audio setting. |
| `ACCESS_COARSE_LOCATION` | Compass declination and solar path. Optional. |
| `POST_NOTIFICATIONS` | The Timer chime notification. Optional. |
| `SCHEDULE_EXACT_ALARM` | The Timer's backstop alarm. Optional. |
| `VIBRATE` | Haptic feedback on each recognised key press. |
| `ACCESS_NETWORK_STATE` | Reads the connection *type* (Wi-Fi / cellular / none) for the connection-status background. It grants no network access. |

This build has no `INTERNET` permission, no foreground service, and no background network work of
any kind. The authoritative list for the version you installed is whatever `aapt2 dump permissions`
prints for it.

## Children

The app has no accounts, no user-to-user features, no ads and no data collection. So there is
nothing here that's collected from anyone, of any age.

## Other builds

The GitHub release is a different package configuration. It holds `INTERNET` and adds two optional
features:

- an **AI design assistant**, which sends the prompts you type and the design you're editing to
  OpenAI under your own account, over HTTPS
- an **update checker**, an unauthenticated GET to the GitHub Releases API, once a day

Neither exists in the Google Play build. The code is excluded from it, not merely switched off. The
privacy policy for those features ships with that build.

## Changes

Material changes to this policy get published here, with the date at the top updated. The history of
this file is public in the repository below.

## Contact and source

- Email: **glyphworks@linuxct.space**
- Source code, AGPL-3.0: **https://github.com/linuxct/glyphworks**

GlyphWorks is an independent project. It is not affiliated with, endorsed by, or connected to Nothing
Technology Limited.
