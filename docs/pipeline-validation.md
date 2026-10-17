# Pipeline Builder local validation

Recorded on 2026-10-06 against the approved
[implementation plan](PLAN_PIPELINE_BUILDER.md). No device, emulator, commit or
remote publication was used. The app version remains 3.4.4 / code 28; the separate
Dev builds use `space.linuxct.glyphworks.dev` and version `3.4.4-dev`.

## Test results

| Check | Result |
| --- | --- |
| Core runtime, codec and drawing routines | 58 passed |
| Independent JVM consumer of the published core artifact | 1 passed |
| Pipeline integration, native behavior, editor, tutorial and related focused checks | 132 passed |
| Refreshed editor/authoring checks after the final Boolean-label adjustment | 16 passed |
| Full GitHub unit suite | 804 passed; 7 pre-existing failures |
| Full Play unit suite | 612 passed; the same 7 pre-existing failures |

Focused checks overlap the full suites; these counts are not additive. The full
suites preceded the final label/resource-only polish and additional Ambient PNG
capture. The corresponding authoring/editor checks were then rerun successfully.

The seven failures were recorded before Pipeline Builder implementation in
`ToyDeckOrderTest` and `ToyDeckStateTest`. They concern pause/release velocity,
strong-flick page limits, counting finger travel, overscroll boundaries, reversing
direction, minimum fast-flick movement, and reanchoring after a new touch. The
carousel gesture implementation and these expectations were left unchanged. Full
test tasks therefore return a failure status; they are not presented as green.

Coverage includes both panel sizes, all 20 immutable toy templates, the native
renderers' original initial frames, Ambient migration and repeated-key music
override, the independently authored runner, custom menu navigation, independent
timers/state, cancellation, sensor demand release, input consumption, preview
suspension, startup recovery, bounded malformed data and runaway programs.

Real Compose interactions exercise nested block drag/drop, cancelled drags,
pan/pinch, undo/redo, typed fields, routines, artwork binding, applying a project,
SAF export/import, invalid import rejection, revision conflicts, and recovery.
Tutorial checks cover the shared Design Editor shell, replay/navigation, touch
interception, artwork editor and typed simulator events.

## Build and SDK checks

GitHub and Play Debug, Dev and Release APKs build. The Play Release AAB builds.
Both Release builds run R8. Release lint passes for both distributions with zero
errors; existing/nonfatal warnings remain in the reports.

The SDK publishes only to `build/pipeline-sdk/maven`. Its AAR and JVM JAR include
source artifacts and dependency metadata. The independent consumer was cleaned
and rebuilt with `-PpipelineSdkArtifacts=true`, resolving
`space.linuxct.pipeline:pipeline-android:1.0.0` and `pipeline-core:1.0.0` from that
repository. Its Android Release build also passes R8. The consumer dependency
graph contains no GlyphWorks, Compose or Nothing SDK dependency.

Both Dev APK signatures verify. Package metadata confirms the `.dev` identifier
and generated version name. Inspection of the Dev DEX files confirms GitHub's
assistant/update packages remain absent from Play. Manifest changes add only the
two non-exported pipeline activities; no new service or permission is introduced.
The backup allowlist adds project files, not runtime preferences or grants.

## Review artifacts

The workspace delivery is in `temp/pipeline-builder/` outside the repository:

- `GlyphWorks-PipelineBuilder-github-dev.apk`
- `GlyphWorks-PipelineBuilder-play-dev.apk`
- `GlyphWorks-Pipeline-SDK-1.0.0.zip`
- `PNG-INDEX.md` linking 21 actual UI renders
- `verification/` with full-suite XML, focused XML, baseline and build logs
- `SHA256SUMS`

PNG coverage includes Lucent/legacy, compact dark, 1.5× fonts, routines, Dino
artwork controls, library/import/recovery, the shipped Ambient settings, both
panel simulations, advanced settings/locked Toys, and the shared tutorial.

## Practical limits

Local verification establishes execution semantics, rendering, authoring flows,
storage/migration and packaging. Physical LED timing and Nothing OS delivery while
sleeping were not tested. Sensor observation follows the existing Glyph session
lifecycle; this feature does not establish a new always-running background service.

Embedded pipeline artwork is predecoded before use. The legacy selected Custom
Design path retains its existing single file load on activation. Runtime state is
local and separate from portable exports; explicitly persistent variables remain
the author's choice.

For usage and integration, see [Pipeline Builder](pipeline-builder.md),
[the portable format](glyph-pipeline-format.md), and [the SDK guide](pipeline-sdk.md).
