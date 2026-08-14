"""Play Store feature graphic, 1024x500.

Built from the app's OWN rendered frames — the ASCII goldens its screen tests
compare against — so every disc on the banner is a real thing the app draws, at
the real panel geometry, rather than an illustration of one.

**13x13 goldens, not 25x25.** That is the Phone (4a) Pro's panel: the device this
app is built around and the only one it has been tested on. The 25-wide frames
are the Phone (3), and putting them on the store banner would advertise the
hardware the app has never run on. Fewer, larger cells also survive being looked
at as a thumbnail, which is how most people will see this.
"""
import math, re, subprocess, sys

W, H = 1024, 500

# The Phone (4a) Pro's Glyph Matrix.
PANEL = 13
OUT = "/tmp/claude-1000/-home-linuxct-Dev-nothing-4a-pro-toys/44c76f9f-a465-4034-aa95-d31ea46b45ad/scratchpad"
GOLDENS = "app/src/test/resources/goldens"

BG = "#000000"
DOT_OFF = "#1c1c1c"
# Lifted from #5a5a5a: the compass carries its cardinal marks at the dim level,
# and at 11 px a cell on a black field they were disappearing, leaving the panel
# as a bare needle. Still clearly below DOT_MID, so the shading order the app
# renders survives.
DOT_DIM = "#767676"
DOT_MID = "#a8a8a8"
DOT_ON = "#ffffff"
# The wordmark is set in the same face the app uses for its own headlines —
# NType82-Regular, which Theme.kt loads at runtime from the device's font dirs
# and never bundles. Body copy stays in a plain sans, matching the app: NType82
# is the *headline* cut there too, and it is a light serif that loses legibility
# at 24 px.
#
# **The quotes are load-bearing. Do not remove them.**
#
# `font-family` is parsed as CSS, and an unquoted family name is a sequence of
# CSS identifiers. "82" is not a valid identifier — identifiers cannot start with
# a digit — so `font-family="NType 82"` is an INVALID declaration. rsvg drops it
# silently and falls back to its default serif, which looks enough like a serif
# that a banner shipped in the wrong face twice: once because a scratch
# fontconfig was not honoured, and once because of this.
#
# Quoted, it resolves to the real font. Verified by rendering the same string
# through the font FILE directly and comparing: `magick -font
# /usr/share/fonts/OTF/NType82-Regular.otf -pointsize 72 label:GlyphWorks` inks
# 354 px, the quoted SVG inks 354 px, the unquoted one inks 362 px in a face that
# is not this one.
#
# Family names come from `fc-list : family`, never from the filename. NType 82 is
# the Regular cut — the same one Theme.kt loads for in-app headlines, and the one
# that matches Nothing's own wordmark. Its siblings "NType 82 Headline" and
# "NType 82 Mono" are heavier and wider respectively. One weight, so the wordmark
# is set at 400: asking for 700 would faux-bold it.
FONT_TITLE = "'NType 82'"

# Body copy in Noto Sans, to match what the APP renders its ordinary text in.
#
# `buildTypography` in Theme.kt only re-families the display/headline/title
# styles with NType82. Everything else — bodyLarge, bodyMedium, labelLarge, i.e.
# every settings row, subtitle and caption — keeps M3's `FontFamily.Default`,
# which on Android is the platform sans. So the banner's tagline should be a
# neutral Google-lineage grotesque, not DejaVu, which is what it was and which
# reads noticeably wider and older.
#
# Noto Sans rather than Roboto only because Roboto is not on this machine and
# Noto is Google's own. If an exact match matters more than convenience, drop
# Roboto-Regular.ttf into /usr/share/fonts and change this to "'Roboto'".
FONT = "'Noto Sans'"

# Golden charset -> colour. ' ' is off-panel/unlit, '.' dim, '+' mid, '#' full.
SHADE = {" ": DOT_OFF, ".": DOT_DIM, "+": DOT_MID, "#": DOT_ON}


def load(name, size=PANEL):
    rows = open(f"{GOLDENS}/{name}.txt").read().split("\n")[:size]
    return [r.ljust(size) for r in rows]


def disc(rows, cx, cy, cell, size=PANEL):
    """One Glyph panel: every LED drawn, lit or not, clipped to the real disc."""
    out = []
    r = size / 2 - 0.2
    c = (size - 1) / 2
    px = cell * 0.70
    for y in range(size):
        for x in range(size):
            if math.hypot(x - c, y - c) > r:
                continue
            fill = SHADE[rows[y][x]]
            dx = cx + (x - c) * cell - px / 2
            dy = cy + (y - c) * cell - px / 2
            out.append(
                f'<rect x="{dx:.2f}" y="{dy:.2f}" width="{px:.2f}" height="{px:.2f}" '
                f'rx="{px * 0.22:.2f}" fill="{fill}"/>'
            )
    return "\n".join(out)


s = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">']
s.append(f'<rect width="{W}" height="{H}" fill="{BG}"/>')

# A very soft vignette so the right-hand panels sit on something, not on flat black.
s.append(
    '<defs><radialGradient id="glow" cx="0.68" cy="0.5" r="0.55">'
    '<stop offset="0%" stop-color="#ffffff" stop-opacity="0.055"/>'
    '<stop offset="100%" stop-color="#ffffff" stop-opacity="0"/>'
    "</radialGradient></defs>"
)
s.append(f'<rect width="{W}" height="{H}" fill="url(#glow)"/>')

# --- Left: wordmark, in a column that ENDS before the panels begin. ---
#
# Size measured, not guessed: the wordmark is rendered alone and trimmed at each
# candidate size to get its true inked width. At 72 px the real NType 82 inks
# 354 px and ends at x=430, clearing the leftmost panel (475) by 45. 80 px
# overruns.
#
# It was 48 px, which was sized against a fallback face that rendered far wider
# and in all capitals. Once the real font was resolving, 48 px left the wordmark
# looking lost against the panels.
X = 76
s.append(
    f'<text x="{X}" y="220" font-family="{FONT_TITLE}" font-size="72" font-weight="400" '
    f'fill="{DOT_ON}">GlyphWorks</text>'
)
s.append(f'<rect x="{X}" y="248" width="70" height="3" fill="{DOT_ON}" opacity="0.5"/>')
for i, line in enumerate(
    ["Glyph Matrix toys for your", "Nothing phone, driven by", "the Essential Key."]
):
    s.append(
        f'<text x="{X}" y="{296 + i * 34}" font-family="{FONT}" font-size="24" '
        f'font-weight="400" fill="#c2c2c2">{line}</text>'
    )

# --- Right: three real frames, hero in the middle. ---
#
# Half-widths are 6.5 * cell, so the extremes are 545-70=475 and 915+70=985 —
# inside the frame with ~40 px to spare, which matters because Play crops the
# edges of this image on some surfaces. Cells are ~11 and ~13 px against the
# 25-wide version's 5.6 and 6.8: same discs, half the LEDs, twice the dot.
#
# Three different KINDS of toy: something that reads out a number, something
# that points, something you press. One frame each, so the banner is not three
# pictures of the same idea.
panels = [
    ("compass_13_north", 545, 250, 10.8),
    ("clock_13_1234_t0", 730, 250, 13.1),
    ("dice_13_face5", 915, 250, 10.8),
]
for name, cx, cy, cell in panels:
    s.append(disc(load(name), cx, cy, cell))

s.append("</svg>")
open(f"{OUT}/feature.svg", "w").write("\n".join(s))
# The SYSTEM fontconfig, deliberately — no FONTCONFIG_FILE override.
#
# This used to point at a hand-written fonts.conf holding only the NType82 file,
# so that rendering one image would not install a font on the machine. `fc-match`
# reported the correct file under that config, but rsvg/Pango did not honour it
# at render time and silently drew the wordmark in a fallback face — which is how
# a banner went out in the wrong type while every check said it was right.
#
# NType82 is installed system-wide now (/usr/share/fonts/OTF), so there is
# nothing to work around. If the wordmark ever renders in the wrong face again,
# check FONT_TITLE against `fc-list : family | grep -i ntype` before anything
# else, and verify by rendering the family against a deliberately bogus one:
# identical output means the family was never applied.
subprocess.run(
    ["rsvg-convert", "-w", str(W), "-h", str(H), f"{OUT}/feature.svg",
     "-o", f"{OUT}/feature_graphic.png"],
    check=True,
)
print(subprocess.run(["identify", f"{OUT}/feature_graphic.png"],
                     capture_output=True, text=True).stdout.strip())
