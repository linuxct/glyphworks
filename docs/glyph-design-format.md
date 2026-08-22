# The `glyph.design` format

One JSON file that holds a still image or an animation for a Nothing Glyph Matrix. Write one from
your own tool and GlyphWorks will play it.

It's an interchange format, not an app-private one. The same bytes go to storage, to export and
through import.

This page describes **format version 1**, as shipped in GlyphWorks 2.0.0. The code lives in
`app/src/main/kotlin/space/linuxct/glyphworks/core/design/` (`Design.kt`, `DesignCodec.kt`,
`DesignFrames.kt`). If this page and the code disagree, the code wins.

## Contents

1. [At a glance](#1-at-a-glance)
2. [The envelope](#2-the-envelope)
3. [`variants` — keyed by Pokémon codename](#3-variants--keyed-by-pokémon-codename)
4. [`levels` — the palette](#4-levels--the-palette)
5. [`cells` — the pixel encoding](#5-cells--the-pixel-encoding)
6. [Timestamps](#6-timestamps)
7. [Validation — every rule a file must satisfy](#7-validation--every-rule-a-file-must-satisfy)
8. [A complete example](#8-a-complete-example)
9. [Writing your own exporter](#9-writing-your-own-exporter)
10. [Forward compatibility](#10-forward-compatibility)

---

## 1. At a glance

```json
{
  "format": "glyph.design",
  "formatVersion": 1,
  "id": "9f2c4a1e6b7d40f8a1c3e5d7b9f0a2c4",
  "name": "Slow Ember",
  "author": "linuxct",
  "createdAt": "2026-07-30T12:00:00Z",
  "modifiedAt": "2026-07-30T12:34:56Z",
  "createdWith": "GlyphWorks 2.0.0",
  "kind": "dynamic",
  "keyMode": "playPause",
  "loop": true,
  "levels": [0, 2048, 4095],
  "variants": {
    "bellsprout": { "frames": [ { "durationMs": 120, "cells": "0012…" } ] },
    "arbok":      { "frames": [] }
  }
}
```

The whole file is one JSON object. UTF-8, no BOM needed, strict JSON so no comments. Export and
sharing use the MIME type `application/json` and the extension `.json`. Files get posted in gists
and read by people, so they get JSON's own type rather than a private one.

## 2. The envelope

Every top-level field, in the order GlyphWorks writes them.

| Field | Type | Required | Meaning |
|---|---|---|---|
| `format` | string | **yes** | Magic string. Must be exactly `glyph.design`. Without it, the file is not a design. |
| `formatVersion` | integer | no (default `1`) | Which version of this spec the file follows. |
| `id` | string | **yes** | Stable identity. It becomes a filename, so it is limited to `[A-Za-z0-9_-]`, 1–64 characters. GlyphWorks writes a 32-character lowercase hex UUID. |
| `name` | string | no (default `""`) | Title, ≤ 64 characters. Any Unicode. Never used as a path. |
| `author` | string | no (default `""`) | Who made it, ≤ 64 characters. Set once at creation. GlyphWorks pins it back to the stored value on every save, so editing somebody else's design never puts your name on it. |
| `createdAt` | string | **yes** | ISO-8601 UTC instant. See [§6](#6-timestamps). |
| `modifiedAt` | string | **yes** | ISO-8601 UTC instant, restamped on every save. |
| `createdWith` | string | no (default `""`) | Free text naming the program that made it, ≤ 64 characters, e.g. `GlyphWorks 2.0.0`. Put something useful here. It's what tells a maintainer which tool produced a broken file. |
| `kind` | `"static"` \| `"dynamic"` | no (default `"static"`) | What the author says this is. A `static` design plays **only its first frame**, even if it stores more. |
| `keyMode` | `"playOnce"` \| `"playPause"` | no (default `"playPause"`) | What one press of the Essential Key does. See below. |
| `loop` | boolean | no (default `false`) | Whether a `playPause` animation repeats. See below. |
| `levels` | array of integers | no (default `[0, 2048, 4095]`) | The brightness palette. See [§4](#4-levels--the-palette). |
| `variants` | object | **yes** | Artwork per device, keyed by Pokémon codename. See [§3](#3-variants--keyed-by-pokémon-codename). |

"Required" means required for the file to be accepted. Every field has a default in the model, so a
slightly wrong file decodes far enough to be rejected with a reason instead of throwing. But `id`,
`createdAt` and `modifiedAt` have defaults that cannot pass validation, and an empty `variants` is
rejected outright. So those four have to be there and be well-formed.

### `kind`, `keyMode` and `loop` together

These three decide playback. `screens/CustomScreen.kt` implements them like this:

- **`kind: "static"`** — one frame goes to the matrix and nothing else happens. `keyMode` and `loop`
  are ignored, and the Essential Key does nothing.
- **`kind: "dynamic"` with one frame** behaves the same. There is nowhere to advance to.
- **`keyMode: "playOnce"`** — the matrix rests on frame 0. A press plays the animation once and
  returns to frame 0, which is drawn, so you see it come back. A press during a run restarts it from
  frame 0. **`loop` is not read in this mode.**
- **`keyMode: "playPause"`** — playback starts by itself when the toy appears. It does not wait for a
  press, because a still design on an always-on display would look broken. A press pauses it, the
  next press resumes. At the last frame of a non-looping design, a press starts it over. At the end,
  `loop: true` restarts at frame 0 and keeps going, and `loop: false` holds the last frame.

## 3. `variants` — keyed by Pokémon codename

`variants` maps a **device codename** to that device's artwork. The codename is Nothing's own
internal name for the model, not a pixel count:

| Key | Device | Matrix | Cells per frame |
|---|---|---|---|
| `bellsprout` | Nothing Phone (4a) Pro | 13 × 13 | 169 |
| `arbok` | Nothing Phone (3) | 25 × 25 | 625 |

A value is an object with one field:

| Field | Type | Required | Meaning |
|---|---|---|---|
| `frames` | array of frame objects | no (default `[]`) | The artwork, in playback order. |

A frame object is:

| Field | Type | Required | Meaning |
|---|---|---|---|
| `durationMs` | integer | no (default `120`) | How long the frame is held, in milliseconds. Static designs carry it too and ignore it, so switching a design to dynamic never has to invent timing. |
| `cells` | string | **yes** | The pixels. See [§5](#5-cells--the-pixel-encoding). |

Three rules to know:

- **An unknown codename is dropped, not fatal.** A key that isn't in the table above is discarded
  and the rest of the design loads. A design from a future Nothing phone still plays on a Phone (4a)
  Pro as long as it carries `bellsprout`. The variant is dropped before its frames are checked, so a
  malformed frame under an unknown codename is not an error either.
- **An empty variant is legal.** `{"frames": []}` means "no artwork for this device yet". Nothing is
  ever auto-scaled between the two geometries, so a blank second size is normal. It still satisfies
  the "at least one known variant" rule, and GlyphWorks draws its "nothing to play" placeholder.
- **Order doesn't matter.** `variants` is a JSON object, and a reader looks up its own codename.

## 4. `levels` — the palette

`levels` is an array of raw panel brightnesses, `0` to `4095`. The matrix is 12-bit and white only.
A cell in `cells` doesn't carry a brightness — it carries an **index into this array**.

```json
"levels": [0, 2048, 4095]
```

Index `0` is off, index `1` is 50 % grey, index `2` is white. That's the default, and what the
editor offers today.

It's data rather than a constant so that an editor offering five or nine steps just writes a longer
list. Old files keep their meaning, and re-palettising a whole design is a one-line edit.

A palette holds up to **36** entries, which is what one base36 character can address
([§5](#5-cells--the-pixel-encoding)).

This is the one field where a reader is lenient. An entry outside `0..4095` is clamped into range
instead of rejected. Compare `durationMs`, where a `0` would spin the render scheduler, so that one
is rejected.

## 5. `cells` — the pixel encoding

One character per cell. The character is the **palette index in base36**: `0`–`9` for 0–9, then
`a`–`z` for 10–35.

Cells run **row-major**. The character at string position `y * size + x` is the cell at column `x`,
row `y`, with `(0, 0)` top-left. `cells.length` must be exactly `size²` for the variant's codename:
169 for `bellsprout`, 625 for `arbok`.

Decoding looks like this:

```
for i in 0 until size*size:
    index = base36(cells[i])          // reject if not a base36 digit
    out[i] = levels[index]            // reject if index >= levels.length
```

`out` is the brightness array pushed to the panel.

Four things matter if you're writing a tool:

- **Case.** Readers accept `A`–`Z` as well as `a`–`z`, because files get hand-typed and pasted
  through tools that change case. GlyphWorks only ever writes lower-case.
- **ASCII only.** Only ASCII `0`–`9`, `a`–`z`, `A`–`Z` count as digits. Non-ASCII decimal digits
  (Arabic-Indic, Devanagari and friends) are rejected, so two files that look different can't decode
  to the same frame.
- **The panel is a disc.** The grid is square but the real matrix is circular, so the corner cells
  are masked in hardware. They still have to be in the string, they just won't light up. Keep your
  art centred.
- **It diffs well.** A frame reads as `0001110000…` in a pull request, and a wrong-length frame is
  caught by a length check rather than a parse.

## 6. Timestamps

`createdAt` and `modifiedAt` are **ISO-8601 UTC instants**, like `2026-07-30T12:00:00Z`. GlyphWorks
truncates to whole seconds, so they always have that compact shape.

Strings rather than epoch millis, for two reasons. A community format should make sense when a human
opens it. And ISO-8601 UTC strings sort lexicographically, so the design list sorts by modified time
without parsing anything.

A reader parses them with `java.time.Instant.parse`, which also accepts an explicit offset
(`2026-07-30T12:00:00+02:00`) and sub-second precision (`2026-07-30T12:00:00.500Z`). Both are
**accepted and normalised**: GlyphWorks rewrites every timestamp to `yyyy-MM-ddTHH:mm:ssZ` on
decode, before the design reaches storage or the list.

Normalising is what keeps the sort honest. Left alone, both spellings sort wrongly as text:

- `2026-07-30T12:00:00+02:00` means 10:00 UTC but sorts after `2026-07-30T11:00:00Z`, which is later.
- `…T12:00:00.500Z` sorts *before* `…T12:00:00Z`, because `.` is below `Z` in ASCII.

Parsing has already pinned the text to an absolute instant, so re-formatting changes the spelling and
nothing else. Emit the `Z` form truncated to whole seconds anyway — it's what GlyphWorks writes, and
what a re-export of your file will contain — but a file that doesn't is imported, not refused.

One timestamp shape *is* refused: a year outside `0000`–`9999`, like `+12026-07-30T12:00:00Z`. A
wider year field changes the string length, and a variable-width prefix can't be ordered by character
comparison at all.

## 7. Validation — every rule a file must satisfy

Design files are made to be shared, so the decoder treats every one as hostile input. It never
throws. It returns either the design or one user-facing sentence explaining the refusal.

Here is the full list of refusals, in the order the checks run. The **first** failure wins. The
constants come from `DesignCodec`. Satisfy all of these and your exporter is fine.

| # | Rule | Constant | Message |
|---|---|---|---|
| 1 | The file must be at most **1 MB** (`MAX_BYTES = 1048576`). A stream is read through a bounded reader that stops one byte past the limit; a string is refused above 1 048 576 characters. Size is checked **before** parsing, which is the defence against a JSON bomb. | `REASON_TOO_LARGE` | "This file is too large to be a Glyph design." |
| 2 | An I/O error while reading is reported with the underlying message appended. | `REASON_UNREADABLE` | "This design file could not be read." |
| 3 | The bytes must parse as JSON. | `REASON_NOT_JSON` | "This file is not valid JSON." |
| 4 | The root must be a JSON **object** with a `format` member that is a JSON **string** equal to `glyph.design`. Checked on the parse tree before mapping, so `{}` can't inherit the magic string from a default. | `REASON_NOT_A_DESIGN` | "This is not a Glyph design file." |
| 5 | A field of the wrong JSON type (`"loop": "yes"`, `"levels": 4`) fails the mapping. | `REASON_NOT_JSON` | "This file is not valid JSON." |
| 6 | `formatVersion` must not be **greater than 1**. A future version may repurpose a field, and half-understanding someone's art is worse than declining it. | `REASON_NEWER_VERSION` | "This design was made with a newer version of the app." |
| 7 | `formatVersion` must not be **less than 1**. | `REASON_OLDER_VERSION` | "This design declares a format version this app cannot read." |
| 8 | `id` must match `[A-Za-z0-9_-]{1,64}` **in full**. No separators, no dots (so no `..`), no NUL, no spaces, no Unicode. It's the only value in the file that reaches the filesystem. Absent or empty fails here. | `REASON_BAD_ID` | "This design has an unusable id." |
| 9 | `name` ≤ **64** characters. | `REASON_NAME_TOO_LONG` | "This design's name is too long." |
| 10 | `author` ≤ **64** characters. | `REASON_AUTHOR_TOO_LONG` | "This design's author name is too long." |
| 11 | `createdWith` ≤ **64** characters. | `REASON_CREATED_WITH_TOO_LONG` | "This design's originating app name is too long." |
| 12 | `createdAt` **and** `modifiedAt` must both parse as ISO-8601 instants (`java.time.Instant.parse`) whose canonical UTC form has a four-digit year. Absent, and so empty, fails here. An explicit offset and sub-second precision are accepted and normalised to `yyyy-MM-ddTHH:mm:ssZ`; see [§6](#6-timestamps). | `REASON_BAD_TIMESTAMP` | "This design has an unreadable timestamp." |
| 13 | `levels` must not be empty. | `REASON_EMPTY_PALETTE` | "This design has no brightness levels." |
| 14 | `levels` must hold at most **36** entries. | `REASON_PALETTE_TOO_LONG` | "This design has too many brightness levels." |
| 15 | Each known variant must hold at most **240** frames. At the 20 ms floor that's still nearly five seconds. | `REASON_TOO_MANY_FRAMES` | "This design has too many frames." |
| 16 | Every `durationMs` must be **20 ≤ d ≤ 60000**. 20 ms is one 50 Hz step, and anything faster is invisible and just burns binder calls. A minute on one frame is a static image with extra steps. Out-of-range durations are **rejected, not clamped**. | `REASON_BAD_DURATION` | "This design has a frame duration outside 20 ms to 60 s." |
| 17 | Every `cells` string must be exactly **`size²`** characters for its codename. Rejected, never padded or truncated — handing back art that isn't what somebody made is worse than saying the file is broken. | `REASON_BAD_FRAME_SIZE` | "This design has a frame that is the wrong size for its device." |
| 18 | Every character of every `cells` string must be an ASCII base36 digit whose value is a valid index into `levels` (`< levels.length`). | `REASON_BAD_FRAME_CELL` | "This design has a frame using a brightness level it does not define." |
| 19 | After unknown codenames are dropped, **at least one** variant must remain. | `REASON_NO_VARIANTS` | "This design contains no artwork for any known device." |

Rules 15–18 run per variant, and only on variants whose codename is known.

Three things are **not** errors:

- **Unknown fields**, top-level or nested, are ignored. A field added in version 2 must not stop a
  version-1 reader.
- **An unrecognised enum value** — `"kind": "kaleidoscope"`, `"keyMode": "morse"` — falls back to
  that field's default (`static`, `playPause`). A JSON `null` does the same.
- **Palette entries outside `0..4095`** are clamped, as in [§4](#4-levels--the-palette).

An accepted file is **normalised** on the way in: timestamps rewritten to canonical UTC, palette
entries clamped, unknown variants dropped. GlyphWorks stores the normalised design, so a re-export
isn't guaranteed to be byte-identical to what you imported. It is guaranteed to mean the same thing
on every device the format knows.

Two more rules that belong to the app rather than the file, but a tool author should know them:

- **An import always makes a new design.** GlyphWorks reassigns `id` every time, not just on a
  collision, so an imported file can never overwrite a design already on the phone.
- **The export filename comes from `name`**, sanitised to letters and digits with everything else
  collapsed to hyphens, falling back to the `id`. It isn't part of the format. Don't encode meaning
  in it.

## 8. A complete example

A valid two-frame `bellsprout` design: a diamond that pulses out to a grey halo and back, over and
over until you pause it. Copy it as-is — it imports.

Frame 0 (`0` off, `2` white), 13 rows of 13:

```
0000000000000
0000000000000
0000000000000
0000000000000
0000002000000
0000022200000
0000222220000
0000022200000
0000002000000
0000000000000
0000000000000
0000000000000
0000000000000
```

Frame 1, the same diamond with a grey (`1`) halo:

```
0000000000000
0000000000000
0000001000000
0000011100000
0000112110000
0001122211000
0011222221100
0001122211000
0000112110000
0000011100000
0000001000000
0000000000000
0000000000000
```

Concatenate each one row by row into a single string, and you get:

```json
{
  "format": "glyph.design",
  "formatVersion": 1,
  "id": "pulse0001",
  "name": "Pulse",
  "author": "example",
  "createdAt": "2026-07-30T12:00:00Z",
  "modifiedAt": "2026-07-30T12:00:00Z",
  "createdWith": "spec example",
  "kind": "dynamic",
  "keyMode": "playPause",
  "loop": true,
  "levels": [0, 2048, 4095],
  "variants": {
    "bellsprout": {
      "frames": [
        {
          "durationMs": 300,
          "cells": "0000000000000000000000000000000000000000000000000000000000200000000000222000000000222220000000002220000000000020000000000000000000000000000000000000000000000000000000000"
        },
        {
          "durationMs": 300,
          "cells": "0000000000000000000000000000000010000000000011100000000011211000000011222110000011222221100000112221100000001121100000000011100000000000100000000000000000000000000000000"
        }
      ]
    }
  }
}
```

It carries no `arbok` variant, which is legal. A Phone (3) draws the "nothing to play" placeholder,
and opening it in the editor gives you a blank 25 × 25 canvas for the second size.

## 9. Writing your own exporter

The smallest valid file is shorter than the example, because most fields have defaults. This is
everything you strictly have to emit:

```json
{
  "format": "glyph.design",
  "id": "my-first-design",
  "createdAt": "2026-07-30T12:00:00Z",
  "modifiedAt": "2026-07-30T12:00:00Z",
  "variants": {
    "bellsprout": {
      "frames": [ { "cells": "…169 characters…" } ]
    }
  }
}
```

That's a **static** design (`kind` defaults to `static`) on the default `[0, 2048, 4095]` palette,
with `durationMs` defaulting to 120.

Your checklist:

1. Emit `format` and a valid `id` (`[A-Za-z0-9_-]`, 1–64 characters). A UUID with the hyphens
   stripped is what GlyphWorks uses.
2. Emit both timestamps as `2026-07-30T12:00:00Z`. Other ISO-8601 spellings are normalised on
   import, but emitting the canonical form keeps your file and a GlyphWorks re-export in agreement.
3. Emit at least one variant with a **known** codename — `bellsprout` or `arbok`.
4. Emit `cells` of exactly 169 (bellsprout) or 625 (arbok) characters, row-major, every character a
   base36 index that is `< levels.length`.
5. If you emit `levels`, keep it non-empty and ≤ 36 entries. The values are raw 0–4095 panel
   brightnesses, not percentages.
6. If you emit `durationMs`, keep it in 20…60000.
7. Set `createdWith` to something that names your tool. Nobody needs it until a file misbehaves, and
   then it's the first thing anyone asks for.
8. Stay under 1 MB.

For a **dynamic** design, also set `"kind": "dynamic"`, pick a `keyMode`, and set `loop` if you want
a `playPause` animation to repeat instead of holding its last frame
([§2](#kind-keymode-and-loop-together)).

## 10. Forward compatibility

What you can rely on as the format changes:

- **`formatVersion` only rises when meaning changes.** Adding a field needs no bump, since unknown
  fields are ignored everywhere. It rises when an older reader would *misread* an existing field —
  and that older reader then declines the file instead of half-understanding it.
- **New devices arrive as new `variants` keys.** No bump. Readers that don't know the codename drop
  that variant and play what they do know.
- **More brightness levels arrive as a longer `levels` array.** No bump, up to the 36-entry ceiling
  the one-character encoding allows.
- **An unrecognised enum value falls back to a default** instead of failing, so a future `kind` or
  `keyMode` still opens.

So a version-1 file you write today stays readable. And a version-1 reader meeting a later file
either reads it or tells you clearly why it can't.
