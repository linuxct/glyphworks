# Pipeline SDK integration

The execution engine is separate from GlyphWorks. `pipeline-core` is a Kotlin/JVM
JAR containing the portable model, codec, catalogs, interpreter, scenes, sprites
and host contracts. `pipeline-android` is an Android AAR providing a Handler-owned
session and Android clock. Neither module depends on GlyphWorks, Compose or the
Nothing SDK. The project license also applies to these modules.

The Android adapter requires API 26 or newer. Build the modules with JDK 17 and
the repository's Gradle wrapper. The AAR is not a fat AAR: its Maven metadata
declares the core JAR and that artifact declares Kotlin serialization. Distribute
the repository artifacts and metadata together instead of copying only the AAR.

## Build and publication

```sh
./gradlew :pipeline-core:jar :pipeline-core:sourcesJar :pipeline-android:assembleRelease
./gradlew :pipeline-core:publishAllPublicationsToLocalSdkRepository :pipeline-android:publishAllPublicationsToLocalSdkRepository
```

These commands build locally and write a Maven repository to
`build/pipeline-sdk/maven`; they do not publish to a remote service.
The coordinates are `space.linuxct.pipeline:pipeline-core:1.0.0` and
`space.linuxct.pipeline:pipeline-android:1.0.0`. Add a local Maven repository to a
consumer's dependency repositories and depend on the Android artifact for
Android scheduling, or the core artifact for a pure JVM host. Use the SDK artifact
version independently of an application's generated version name.

The repository includes `pipeline-consumer`, a plain JVM consumer test, and
`pipeline-android-consumer`, a minimal Android consumer with a minified release
build. They contain no GlyphWorks imports. The core source artifact supplies the
complete API; the following is the smallest useful host pattern.

After local publication, verify consumption of the packaged artifacts instead of
Gradle project dependencies with:

```sh
./gradlew -PpipelineSdkArtifacts=true :pipeline-consumer:clean :pipeline-android-consumer:clean :pipeline-consumer:test :pipeline-android-consumer:assembleRelease
```

This resolves the AAR, core JAR and their dependency metadata from the local Maven
repository; the Android fixture also exercises release shrinking.

## Host the interpreter

```kotlin
import space.linuxct.pipeline.*

val decoded = PipelineCodec.decode(jsonInputStream)
if (decoded !is PipelineCodec.Result.Ok) return

val host = object : PipelineHost {
    override val size = 13
    override val clock = myClock // monotonic elapsedMillis + calendar wallMillis
    override val random = myRandom // seed deterministically for repeatable tests
    override fun inputs(): Map<String, Value> = cachedSnapshot
    override fun createNative(type: String, context: NativeContext): NativeBehavior? =
        myNativeFactory(type, context)
    override fun output(frame: IntArray) = myDisplay.write(frame)
    override fun supports(requirement: Requirement): Boolean =
        supportedVersions[requirement.capability] == requirement.version
    override fun diagnostic(diagnostic: Diagnostic) = myDiagnostics.add(diagnostic)
}

val runtime = PipelineRuntime(decoded.document, host)
runtime.start()
// On one owner thread: advance periodically, and deliver events as they arrive.
runtime.advance()
runtime.dispatch(PipelineEvent("key.action", consumable = true))
val snapshot = runtime.debugSnapshot()
runtime.close()
```

`decode` performs strict structural and semantic validation; `decodeDraft` permits
incomplete authoring inputs while retaining structural and allocation limits.
Neither grants permissions or registers sensors. Inspect Invalid diagnostics
instead of executing a rejected document. `start()` checks the host's declared
capability support. A host should explicitly enumerate its supported capability
versions; a missing native factory result is not an extension-loading mechanism.

Run `start`, `advance`, `dispatch`, inspection and `close` on one serialized owner.
The core creates no thread and owns no Android component. Advance using the same
monotonic clock used by timers. Wall time supplies calendar inputs and can change
without redefining elapsed time. Supply cached input snapshots; avoid disk, network
or sensor registration inside a rendering callback. Output is a size × size
integer frame, with brightness from 0 to 4095. Transport to a physical device is
the host's responsibility.

`externallyCovered(true)` lets the host suspend visible output when another
display owner takes over. `close()` releases the runtime's presentations, scoped
tasks and native instances. Keep an independent host-owned way to stop a faulty
or unwanted project. `RuntimeLimits` bounds work, timers, fibers, sprites, event
queues and traces; changing those defaults requires reconsidering host budgets.

## Android scheduling

```kotlin
import android.os.Handler
import space.linuxct.pipeline.android.AndroidPipelineSession

val session = AndroidPipelineSession(document, host, Handler(ownerLooper))
session.start()
session.dispatch(PipelineEvent("key.action", consumable = true))
session.inspect { trace, values -> /* callback runs on the owner looper */ }
session.covered(true)
session.close()
```

`AndroidPipelineClock` is available for a real Android host. Session methods
marshal execution onto the supplied Handler; callbacks and native behavior run
there. The adapter schedules ticks only while running and cancels them on close.
It does not create a service, request permissions, subscribe to sensors, acquire
the Glyph SDK, or decide when an Activity should keep a project running. Tie its
lifetime to the host's actual execution policy, not an incidental screen redraw.

## Native behaviors and application data

`createNative` receives a scoped `NativeContext`: parameters, artwork bindings,
embedded designs, cached inputs, clock, random source, cancellable scheduler,
frame/event emission and bounded host action/state hooks. A native behavior uses
these ports and never acquires a display session directly. Implement start,
suspend/resume and close; scheduled callbacks must not outlive their instance.
Commands, named values and events allow a block program to interact with it.

The application owns permissions and data acquisition. Use `demand(capabilities)`
to manage shared subscriptions and represent an unavailable reading with
`Value.Unavailable`, not a plausible zero. Namespace persistent state using the
program and variable IDs supplied to `readState`/`writeState`. It is separate from
portable project JSON. Allowlist host actions; do not interpret arbitrary strings
as Android intents, file paths, executable code or network addresses.

For document structure, reference rewriting, artwork requirements and import
limits, see [Glyph pipeline format](glyph-pipeline-format.md). The Compose editor,
project store, tutorials and GlyphWorks native behavior library remain in the app
module; another SDK consumer supplies its own UI and integrations.

`DrawingRoutineRenderer` can support native visual slots with authored drawing
routines. It caches a private interpreter per routine, uses the canonical scene
and expression logic, and shares predecoded assets. Pass the caller's current
variable snapshot and parameter values. It returns only pixels, forwards no host
side effects, and closes all private interpreters on disposal. Use
`DrawingRoutineRenderer.validate` in the authoring UI to explain why a routine
contains an operation that cannot execute inside a drawing slot.
