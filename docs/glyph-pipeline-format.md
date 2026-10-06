# Glyph pipeline format, version 1

`glyph.pipeline` is GlyphWorks’ portable JSON interchange format. Files use
`.glyph.pipeline.json` and `application/json`. It extends the design-library
workflow, not the `glyph.design` document itself. It is not a Scratch project or
an external standards body’s format.

The [JSON Schema](glyph-pipeline-v1.schema.json) describes the document structure.
The core `PipelineCodec` and host capability validation are authoritative for
executable semantics, types, supported block versions, reference closure, asset
compatibility and resource limits. Passing JSON Schema alone does not make a
program safe to execute.

## Contents

| Field | Meaning |
|---|---|
| `format`, `formatVersion` | Exactly `glyph.pipeline` and `1`. |
| `id`, `name`, `author` | Project identity and human-readable provenance. |
| `createdAt`, `modifiedAt`, `createdWith` | ISO timestamps and the originating app/build. GlyphWorks fills its build version from generated build properties. |
| `entryPoint` | ID of the starting program in `programs`. |
| `programs` | Immutable definitions with `TOY`, `AMBIENT`, `CONTROLLER` or reusable-program role. |
| `routines` | Named, explicitly versioned routines with parameters, local variables, a return type and nested blocks. |
| `designs` | Deduplicated complete `glyph.design` v1 objects, indexed by asset ID. Their existing palettes, encoded cells, timings and panel variants remain unchanged. |
| `bindings` | Named identities for artwork placement, phase playback and collision geometry. A block’s `binding:jump` argument, for example, selects a binding identity. |
| `requires` | Native host capabilities and versions needed by the document. It cannot register new capabilities or replace a native implementation. |
| `panels` | Supported matrix sizes: 13 and/or 25. Missing artwork is not implicitly stretched. |
| `editor` | Canvas positions, folds, selection and viewport. These do not change execution meaning. |
| `preview` | Optional thumbnail asset/frame, sample elapsed time (0–60 seconds) and initial sample key action. These affect the app preview, not runtime behavior. |

Programs contain typed parameters, current parameter values, declared variables
and event scripts. Script triggers have an event, optional condition, edge policy,
initial-level behavior and a stable duration. Re-entry is explicitly `RESTART`,
`IGNORE`, `QUEUE` or `PARALLEL`. A `calendar.daily` trigger carries a `calendar`
object with hour (0–23), minute (0–59) and unique weekdays (Monday = 1, Sunday = 7).
Calendar schedules use event edges. They fire once per local date during the
scheduled minute while active; they do not replay missed time or a nonexistent
local time during a daylight-saving transition.

A block has a stable `id`, a registered `op`, typed `arguments`, a `body`, an
`otherwise` body where supported, an optional comment and enabled state. Routines,
programs, assets and variable references use literal IDs. Names are presentation
labels, so renaming does not break references. Call arguments use
`arg:<parameterId>`. Native artwork slots use `binding:<slotName>`. The `flow.select` container executes
one enabled child at its zero-based index, optionally wrapping. Its child order is
the visible canvas order. `block.count` references a container block's stable ID
in its expression `key` and returns its enabled child count; sharing retains that
block's owning program or routine, and import remaps the reference.

An expression uses an `op`, optional `key`, literal `value` and nested `args`.
Values carry a `type` discriminator (`number`, `boolean`, `text`, `vector`, `list`,
`record`, or `unavailable`). Numeric durations have unit `MILLISECONDS`; angular
velocity is `RADIANS_PER_SECOND`, never an absolute heading. The `record`
expression takes alternating literal field names and value expressions, with
unique, nonempty keys and at most 128 fields; it supports dynamic signal and
native-command data without embedding executable source. `unavailable` remains
distinct from zero or false. The supported vocabulary lives in the core’s block,
expression, input and event catalogs, shared by validation and the editor.

Asset bindings select `NATURAL`, `STRETCH_TO_PHASE`, `LOOP` or `HOLD_LAST` playback,
zero transparency and per-panel `SpriteGeometry`. Geometry separates crop,
anchor and hitbox, so replacing Dino’s jump artwork need not change its collision
behavior. A binding selects exactly one `assetId` or `routineId`. A drawing
routine receives native output fields, `slot`, `elapsedMs`, `phaseDurationMs`
and `progress`, also grouped under the event's `state` record. Matching routine
parameter IDs or names receive compatible output values; other parameters use
their defaults. Drawing uses the same interpreter in a private, immediately
executed scene. Local state resets per call and caller globals are copied.

Drawing routines may use scenes, asset-backed sprites, variables, lists,
conditions, finite repeats and other drawing routines through ordinary calls.
They cannot wait, run native behaviors, launch programs, schedule events, persist
state or invoke host actions. A rendering budget terminates excessive work.
Prepared interpreters and artwork are reused between frames. Native slot geometry
still controls crop, anchor and hitbox; a drawing source cannot be used recursively
as a sprite's design.

## Portability and privacy

Export includes the dependency closure: called programs, routines, referenced
artwork, binding metadata and effective settings. It excludes activation flags,
running countdowns, transient game variables, live sensor readings, notification
metadata, location/cache, permissions, credentials and debug history. No script
source, arbitrary intents, reflection, executable URLs or plugin binaries are
accepted.

Import creates fresh local identities and rewrites structural references as one
operation. Free-form text, event names and native opcode names are not rewritten.
A matching source ID never overwrites another local project. Imported controller
projects remain inactive; only App settings can enable custom controls. Imported
data never claims trusted built-in status.

## Persistence and recovery

`PipelineStore` keeps complete, self-contained documents under device-protected
`files/pipelines/<safe-local-id>/`. A project has separate draft and applied
pointers. Payloads are written and fsynced before an atomic pointer replacement.
A failed write leaves the previous whole document readable. Backups recover an
interrupted pointer replacement.

Draft saves allow incomplete executable inputs but still enforce structural
bounds. Apply performs strict validation. The last 12 applied revisions are kept;
restoring one creates a new revision rather than overwriting history. Generation
checks detect an editor draft racing a quick-setting update. Old drafts cannot
replace a newer applied revision after process death.

Runtime persistence is separate from project storage and is not part of this
format. Each snapshot embeds the specific routine revisions it needs; changing a
library routine does not silently alter already-applied programs. A project may
explicitly update its references and apply the resulting revision.

## Limits and validation

The codec bounds input before full parsing (8 MiB), nesting, block and expression
counts, decoded artwork, lists and strings. Before parsing it rejects duplicate
JSON keys, more than 112 JSON nesting levels and more than 262,144 JSON nodes.
A document allows at most 4,096 blocks, 65,536 expressions, 128 programs,
128 routines, 128 embedded designs and 2,000,000 decoded artwork pixels in total.
Each design retains the existing 240-frame limit per panel. Referenced artwork
must contain frames for every panel declared by the project. A selected thumbnail
frame must exist on each declared panel. Thumbnail artwork is included in sharing
and its identity is remapped on import.

Validation rejects unsupported versions,
unknown executable operations, invalid/non-finite values, unresolved references,
recursive call cycles and incompatible typed arguments. Android’s adapter also
runs embedded artwork through the existing `DesignCodec`; there is no second
image decoder with a different trust policy.

Storage IDs use a stricter filename-safe alphabet than graph IDs. Imported graph
identities are remapped before storage. Exported artwork retains its per-panel
variants. Local file paths and provider URIs never appear in project data.
