<p align="center" width="100%">
  <img src="art/ic_launcher_512.png" alt="GlyphWorks logo" width="160"><br/>
</p>

# GlyphWorks

**Glyph Toys on the Nothing Phone (4a) Pro, driven by the Essential Key.**

[![Latest Version](https://img.shields.io/github/v/release/linuxct/glyphworks)](https://github.com/linuxct/glyphworks/releases/latest)
![Compatibility](https://img.shields.io/badge/compatible-Nothing%20Phone%204(a)%20Pro-black)
![Compatibility](https://img.shields.io/badge/compatible-Nothing%20Phone%203-white)
[![License: AGPL v3](https://img.shields.io/badge/license-AGPL--3.0-black.svg)](LICENSE)

> **Vibe-coded project notice:** This app was designed by humans but its code was written from scratch by Claude.

---

## Download

<p align="center">
  <a href="https://github.com/linuxct/glyphworks/releases/latest">
    <img src="https://i.imgur.com/eKVKAIk.png" alt="Download from GitHub"><br/>
  </a>
</p>

<p align="center">
  <a href="https://play.google.com/store/apps/details?id=space.linuxct.glyphworks">
    <img src="https://i.imgur.com/RvsPBjV.png" alt="Download from Google Play"><br/>
  </a>
</p>

## Screenshots

| Glyph Toys | Toy settings | Your designs |
|:---:|:---:|:---:|
| <a href="https://i.imgur.com/TxTS87k.png"><img src="https://i.imgur.com/TxTS87k.png" alt="The toy list" width="230"></a> | <a href="https://i.imgur.com/uKybpck.png"><img src="https://i.imgur.com/uKybpck.png" alt="Settings for one toy" width="230"></a> | <a href="https://i.imgur.com/feRYL5c.png"><img src="https://i.imgur.com/feRYL5c.png" alt="The list of your designs" width="230"></a> |
| **Pixel editor** | **Settings** | **Tutorials** |
| <a href="https://i.imgur.com/KCaXzDX.png"><img src="https://i.imgur.com/KCaXzDX.png" alt="The pixel editor" width="230"></a> | <a href="https://i.imgur.com/4qhhCzE.png"><img src="https://i.imgur.com/4qhhCzE.png" alt="The settings tab" width="230"></a> | <a href="https://i.imgur.com/vEEdbIY.png"><img src="https://i.imgur.com/vEEdbIY.png" alt="The Essential Key tutorial" width="230"></a> |

## What is this?

The Phone (4a) Pro has a Glyph Matrix but no Glyph Button, so Nothing gives it a single always-on
toy that you can't tap or switch.

GlyphWorks remaps the **Essential Key** to do that job. You get 19 toys, a pixel editor for your own
designs, and control of all of them from the key — on the lock screen, on the always-on display, and
while you use the phone. The Phone (3) works too, through its real Glyph Button.

## How the key works

An accessibility service counts your presses and acts on them. It also dismisses Essential Space and
Essential Voice if the system opens them.

**Regular mode** (default)

- **1 press** — the current toy's action (roll, flip, +1, start the timer…)
- **2 presses** — next toy
- **3 presses** — back to the first toy in your list

**Menu mode** (optional)

Same, except 2 presses open a picker on the matrix. One press then cycles through your toys, which
is quicker when the toy you want is far away. Double press again to confirm, or wait a few seconds.
It's selected once the matrix stops blinking.

The Tutorials tab in the app shows all of this as an animation, plus how to set up the key in
Nothing OS.

## Toys

**Interactive** (a single press does something): Dice · Coin Flip · Dino Run · Spin the Bottle ·
Rock Paper Scissors · Counter · Breathing · Timer · Custom Design

**Passive:** Ambient (10 backgrounds, plus charge and music layers) · Clock · Eyes · Download Speed ·
Battery · Solar Path · Moon Phase · Compass · Level · Music Visualizer

All of them can be switched off, reordered and configured in the app. [`docs/TOYS.md`](docs/TOYS.md)
says what each one actually does.

## Custom designs

The **Create** tab is a full pixel editor for the Glyph Matrix — static images or animations, with a
live preview on the real matrix while you draw. The `Custom Design` toy plays whichever one you pick.

Designs export and import as a single JSON file, so you can share them with anyone else running
GlyphWorks. The format is documented in [`docs/glyph-design-format.md`](docs/glyph-design-format.md).
The Tutorials tab has a guided walkthrough of the editor.

## Setup

Install, open, and the app's built-in onboarding process walks you through it. In short:

1. Turn on the accessibility service (sideloaded builds may need *Allow restricted settings* first).
2. Pick GlyphWorks as your always-on Glyph Toy.
3. Grant what you want: microphone (visualizer), location (solar path, compass), notifications and
   exact alarms (timer).
4. Hand the key over — keep Essential Space and Recorder enabled, then:
   - Settings → Intelligence Toolkit → **Essential Key Settings** → turn **on** "Activate with single
     tap before use"
   - Settings → Intelligence Toolkit → **Essential Voice** → turn **off** "Activate via Essential Key"
5. Press the key twice.

Only the Phone (3) and the Phone (4a) Pro are supported — the app refuses to run without a Glyph
Matrix.

## Build it

JDK 17, Android SDK platform 37, your SDK path in `local.properties`.

```sh
./gradlew :app:assembleGithubDebug      # debug APK
./gradlew :app:assembleGithubRelease    # release APK
./gradlew :app:testGithubDebugUnitTest  # tests
adb logcat -s GlyphWorks                # everything logs under one tag
```

There's a second flavour, `play`, which ships without the design assistant and the update checker.
[`docs/TECHNICAL.md`](docs/TECHNICAL.md) covers that, the tests, releases and the code layout.

## Privacy

The GitHub version does automatically only one network call: a daily check of this repo's GitHub 
Releases for a new version. Nothing else leaves your phone. If you wish to enable it, the design assitant
will send the custom design you are currently editing and help you customize it to your liking with the help
of OpenAI's models. On the other hand, the Play build has no `INTERNET` permission at all.

## Contributing

Issues and PRs welcome. I daily drive a Phone (4a) Pro and only test there, so Phone (3) reports are
especially useful.

## License

[AGPL-3.0](LICENSE)
