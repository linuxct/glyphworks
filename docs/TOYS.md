# The toys

What each toy in GlyphWorks does. ✅ means a single press of the Essential Key does something.

| Toy | Press | What you see |
|---|:---:|---|
| Ambient | ✅ | Home base. Enable any of 12 backgrounds: digital or analog clock, connection, battery %, download speed, tilt ball, pixel clock, battery gauge, solar path, moon phase, notifications, weather. Press to advance; optionally change automatically every 15 seconds. Music and charging take priority. Night gating and shake-to-show keep it quiet when you want. |
| Clock | – | A stacked HH/MM pixel clock. Themes add a battery bar, a battery ring, or an analog dial in the panel border. |
| Eyes | – | Two eyes that wander and blink. |
| Download Speed | – | Your live network download speed. |
| Battery | – | The matrix fills to your charge level. On the charger it adds a rising wave and a pulsing bolt, or the wattage instead. |
| Solar Path | – | Where the sun sits on today's arc for your location. Without location access it falls back to a 06:00/18:00 day. |
| Moon Phase | – | Tonight's moon on a textured lunar surface: dim maria, bright highlands, a soft terminator and faint earthshine. |
| Notifications | – | Outstanding dismissible notifications: 0–9, then 9+. Choose a large envelope, lowercase “txt”, count with notification dot, or full-size bell/count marquee. Requires Android notification access; unavailable access is different from zero. |
| Weather | – | Current conditions and temperature from Open-Meteo. Hold the icon for 3 seconds, slide left into temperature, hold for 3 seconds, then slide left into the returning icon. Celsius or Fahrenheit. Requires opt-in and approximate location. |
| Dice | ✅ | D4, D6, D8, D12 or D20. Press or shake to roll. |
| Coin Flip | ✅ | Press or shake to flip. Two looks: H/T letters, or a monarch's profile and a euro-style "1". |
| Dino Run | ✅ | An endless runner. Press to start, press to jump. Clip a cactus and your score blinks; one more press starts again. |
| Spin the Bottle | ✅ | Press and the bottle spins about the centre for ~3 s, four or five turns, then ratchets to a stop and bursts. |
| Counter | ✅ | Press for +1, wrapping at 999. Shake to reset. |
| Breathing | ✅ | Press to start or stop a guided breathing pulse. |
| Timer | ✅ | Press to start. The matrix is an hourglass with no hourglass in it: grains fall and the sand rises until every LED is lit, then it chimes. Survives toy switches and process death. |
| Compass | – | A sensor-fused needle with a cardinal ring. |
| Level | – | A bubble that centres in a target ring when the phone is flat and rolls toward the low edge when it isn't. The ring lights up within a few degrees of level. |
| Music Visualizer | – | An FFT spectrum with log-spaced bands, three themes, an adjustable response speed, and a noise floor while audio plays. |
| Custom Design | ✅ | Plays a design you drew in the Create tab. Its cog picks which one; a press plays, pauses or replays it depending on the design's key mode. |

Every toy can be switched off, reordered and configured in the app. Drag order is the cycle order.

## Ambient backgrounds

Background toggles are independent of the main Glyph Toys list. They cycle in the order shown in
Ambient settings. Upgrading retains your previous background as the only selected background;
automatic changing starts off. One selected background stays fixed, and none leaves a blank
background while music and charging can still appear.

A press advances the selected background even if music or charging currently covers it. Automatic
cycling pauses while an overlay, night setting, or shake setting hides the background. It gives the
background a fresh 15 seconds when it returns, and a manual press also restarts that interval.
Music has priority over charging. Phone (3)'s Glyph Button uses the same background action.

Configure appearance in each individual toy's settings: Ambient shares the Notifications style,
Weather units, clock preferences, and visualizer settings. Its charging indicator and Battery gauge
background use the Battery toy's gauge/wattage setting. Ambient's former separate charging-style
preference is discarded on upgrade; the Battery toy's preference is preserved. The charging
indicator can still be switched on or off in Ambient.

## Notifications

Android exposes outstanding notifications rather than a dependable unread-message count across
apps. GlyphWorks counts dismissible notification entries, excluding ongoing services and media
controls. Group summaries are counted only when no child notifications represent that group.
One notification containing several messages still counts as one; opening the notification shade
alone does not clear it.

Grant **notification access** from the toy's settings. This is separate from the Timer's permission
to post notifications. GlyphWorks keeps only counting metadata in memory, never reads titles or
message text, and never dismisses notifications or marks them read. The same source and display
renderer serve the standalone toy and Ambient.

The Notifications settings offer four display styles. Envelope and “txt” place a compact count
under a label; the notification-dot style gives the count most of the panel. The bell marquee
holds a full-size bell for 3 seconds, slides left into the full-size count over 1 second, holds it
for 3 seconds, then slides left into the returning bell. It restarts at the bell when revealed or
when switching styles. Counts continue updating during the animation. The default remains “txt”.

## Weather

Enable Weather in its settings and grant approximate location. To follow location changes while
locked or on AOD, also choose **Allow all the time** in Android's location settings. With only
foreground access, location updates while the app is visible; weather can continue refreshing for
that last location while locked.

Weather normally refreshes about every 15 minutes while in use, and after a meaningful location
change. It keeps a local observation for offline use for up to two hours, then shows an unavailable
state. Android may defer background updates in deep idle. Turning Weather off or revoking
approximate-location access clears its cache; disabling device location preserves the last weather
as stale until it expires.

The weather icon and temperature each hold for 3 seconds, separated by a roughly 1-second slide
from right to left. The temperature sits above its °C or °F label. Returning to Weather restarts
at the icon. Conditions include clear day/night,
partly cloudy, overcast, fog, rain, snow and thunderstorms. Artwork is drawn separately for the
Phone (4a) Pro's 13×13 panel and Phone (3)'s 25×25 panel.

Weather data by [Open-Meteo](https://open-meteo.com/), licensed under
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). See the
[privacy policy](privacy.md) for what a weather request sends.

## Brightness

The Glyph brightness setting **scales** each frame instead of normalising it. A 50 % grey stays half
as bright as white at every level, rather than being stretched back up to full white. So grey is a
shade you can actually draw with.
