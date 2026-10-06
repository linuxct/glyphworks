# Privacy Policy — GlyphWorks for Nothing

Applies to **GlyphWorks for Nothing** (`space.linuxct.glyphworks`) as distributed
on Google Play and GitHub. Last updated: 2026-10-06.

GlyphWorks is an independent, noncommercial, open-source app under AGPL-3.0. It has no advertising,
analytics, crash-report uploads, or GlyphWorks accounts. The optional Weather toy uses the network
in both builds. The GitHub build also includes the features described under [GitHub builds](#github-builds).

## Weather and location

The **Compass** uses approximate location to correct magnetic north to true north. **Solar Path**
uses it to calculate sunrise and sunset on the phone. These two features read an existing Android
location and do not send it anywhere. Without access, Compass uses magnetic north and Solar Path
uses a nominal 06:00/18:00 day.

**Weather** is off until you enable it. When enabled and in use, it obtains approximate location and
sends latitude and longitude, rounded to two decimal places, to **Open-Meteo** over HTTPS to request
current weather. Open-Meteo also receives your IP address as part of that connection. Requests do
not include a name, account, advertising identifier, notification data, or device identifier.

Open-Meteo's free API privacy terms say that it may retain IP addresses for technical purposes and
that its troubleshooting logs may include coordinates. It says those logs are deleted after
90 days. This retention is controlled by Open-Meteo; clearing GlyphWorks' local cache does not
delete provider logs. See [Open-Meteo's terms and privacy policy](https://open-meteo.com/en/terms).

GlyphWorks requests **approximate location**, not precise location. With the separate optional
**Allow all the time** grant, Weather can follow location changes while the phone is locked or
showing AOD. With foreground-only access, location updates while the app is visible, and Weather
can refresh for that last location while locked. Android may delay background updates in deep idle.

While needed, weather refreshes about every 15 minutes and after a meaningful location change.
The most recent rounded coordinates and weather observation are cached in app-private,
credential-protected storage, unavailable before the first unlock and excluded from Android backup.
Weather observations expire after two hours. The app stops unnecessary requests and location
updates when Weather is no longer in use. A manual refresh in settings can request an update.

Turning Weather off or revoking approximate-location access clears its cached location and weather
when the app observes the change. Switching device location off stops location-based requests and
leaves the previous weather marked stale until expiry. You can change permissions in Android's
app settings or remove all local app data through Android's storage settings.

Weather data by [Open-Meteo](https://open-meteo.com/), under
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).

## Notification access

The **Notifications** toy, its Ambient background, and notification-event pipeline blocks require Android's notification-listener
access. This is separate from the permission to post the Timer's own notifications.

The listener receives notification events from Android. GlyphWorks extracts only the opaque
notification key, originating app package name, grouping information, and metadata needed to
identify dismissible, ongoing, foreground-service, and media entries. It keeps that metadata in
memory to calculate the number shown on the Glyph Matrix and deliver received, updated, and
removed events to active user-authored pipelines. Those events expose the app package name and
current count. It does not read titles, message text, or notification actions, transmit this
information, or log notification payloads. A pipeline can explicitly retain a value in a persistent
local variable, as described below; the listener itself does not write notification metadata to disk.

Android provides outstanding notification entries, not a reliable per-app unread-message count.
GlyphWorks excludes ongoing services and media controls, and avoids counting a group summary
alongside its children. The display shows 0–9, then 9+. It never dismisses, snoozes, or marks
notifications read. Disconnecting or revoking notification access clears the in-memory state and
shows an unavailable state instead of zero. Reconnection rebuilds the count from active notifications.

## Microphone

The Music Visualizer and music-reactive Ambient layer analyse the phone's **output mix** using
Android's `android.media.audiofx.Visualizer`. Frequency magnitudes become bar heights on the matrix.
Audio is processed locally and is not recorded to a file or transmitted. The capture engine
releases itself after five seconds without a poll. Denying permission leaves the idle pattern.

Android requires `RECORD_AUDIO` for `Visualizer` and `MODIFY_AUDIO_SETTINGS` to attach it to the
output mix. GlyphWorks does not change audio settings. Android accounts for this through the
microphone app operation, so the privacy indicator can appear and background restrictions apply.
The input is audio already playing on the phone, not sound from the room; GlyphWorks does not use
`AudioRecord` or `MediaRecorder`. The Timer uses `RingtoneManager` for its chime.

## The Essential Key and accessibility service

The accessibility service receives hardware key events so it can recognise Essential Key presses,
including while locked or on AOD. It consumes recognised presses to control toys. Its
`canRetrieveWindowContent` setting is false, so it cannot retrieve your screen contents.

It also receives window-state events scoped to Essential Space and Essential Recorder
(`com.nothing.ntessentialspace`, `com.nothing.ntessentialrecorder`). If one opens within three seconds
of a captured press, the service closes it once using Back when unlocked or Home when locked.
This is disabled when key capture is off. The service is declared `isAccessibilityTool="false"`.

Press counts are held in memory to choose toy actions or invoke your pipeline's key rules.
When Custom controls and menus is enabled, the controller you selected determines those actions
instead of the standard toy menu. Local Android diagnostic logs include
hardware key codes, click counts, toy actions, and service events; they are not uploaded by
GlyphWorks. The service does not use accessibility information for advertising or profiling, or
send it with weather requests. You can disable it in Android's Accessibility settings.

## Pipelines and device inputs

The optional Pipeline Builder processes device state locally: charging, screen/lock state,
notification events, audio playback/output magnitudes, approximate location when permitted, and
available motion, orientation, compass, light and proximity sensors. Inputs are observed while the
relevant Glyph session and pipeline are active. The builder does not add an event-history database,
usage analytics, screen-content access, or a new network destination.

Pipelines, their artwork and reusable routines are saved as `glyph.pipeline` JSON projects in
app-private device-protected storage. Applied revisions and unfinished drafts remain separate.
Designs and pipeline projects can be included in Android backup and device transfer. Activation
settings and runtime state are not included in those project backups.

Variables are temporary unless the author explicitly marks them persistent. Persistent variables
and timer deadlines are kept in local device-protected preferences. If an author stores a sensor
reading or notification-event value in a persistent variable, that value stays there until the
pipeline changes it or local app data is cleared. Runtime variable values and event traces are not
included in exported pipeline files. Sharing or exporting is an explicit action and includes the
project definition, its initial values, artwork and referenced routines, not a history of execution.
The simulator uses sample inputs without reading live sensors or acquiring the Glyph display.

## Designs, settings and alarms

Designs in the Create tab are `glyph.design` JSON files in app-private device-protected storage.
They contain the artwork, design name, and creator name you provide. Sharing a design gives the
single selected file to the app you choose in Android's share sheet. Copies prepared for sharing
are cleaned from the cache after a day. The GitHub assistant can send design inputs when you use
that feature, as described below.

Preferences, including enabled toys, Ambient backgrounds, brightness and creator name, are stored
in device-protected `SharedPreferences`. Android's app storage controls let you remove local data.

The Timer schedules an exact alarm as a backstop and posts a notification when it finishes.
Timer data stays on the device.

## Permissions in both builds

| Permission or access | Why |
|---|---|
| `com.nothing.ketchum.permission.ENABLE` | Draw on the Glyph Matrix through Nothing's SDK. |
| Accessibility service (`BIND_ACCESSIBILITY_SERVICE`) | Recognise Essential Key presses and dismiss the related Essential pop-up. Cannot retrieve screen content. |
| Notification access (`BIND_NOTIFICATION_LISTENER_SERVICE`) | Count outstanding dismissible notifications and deliver local pipeline events. Optional. |
| `RECORD_AUDIO` | On-device output-mix analysis for the visualizer. Optional. |
| `MODIFY_AUDIO_SETTINGS` | Required by Android to analyse the output mix; no audio settings are changed. |
| `ACCESS_COARSE_LOCATION` | Compass declination, Solar Path and optional Weather. |
| `ACCESS_BACKGROUND_LOCATION` | Follow approximate-location changes for Weather while locked or on AOD. Optional and granted separately. |
| `INTERNET` | Request Weather from Open-Meteo in both builds; also used by the GitHub-only features below. |
| `ACCESS_NETWORK_STATE` | Read Wi-Fi/cellular connection state for the connection-status background and network availability. |
| `POST_NOTIFICATIONS` | Post Timer notifications; the GitHub build can also post update notices. Optional. |
| `SCHEDULE_EXACT_ALARM` | The Timer's backstop alarm. Optional. |
| `VIBRATE` | Haptic feedback for recognised key presses. |

Weather needs internet permission in the Play build from 3.3.0 onward. Statements about older
releases having no network access do not describe this version. The manifest and source for each
release are available in the repository.

## GitHub builds

The GitHub build includes two additional network features whose code is absent from the Play build:

- The **design assistant** sends your prompts and the design inputs you provide to OpenAI over
  HTTPS using your account. Review the feature's setup information and
  [OpenAI's privacy policy](https://openai.com/policies/privacy-policy/) before using it.
- The **update checker** requests release information from GitHub's Releases API, normally once a
  day, and can download a release you choose. GitHub receives the request and your IP address;
  see [GitHub's privacy statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement).

These requests are separate from local notification counting and optional Weather.

## Changes and contact

Material changes are published here with an updated date. The history of this policy is public.

- Email: **glyphworks@linuxct.space**
- Source code, AGPL-3.0: **https://github.com/linuxct/glyphworks**

GlyphWorks is an independent project. It is not affiliated with, endorsed by, or connected to
Nothing Technology Limited.
