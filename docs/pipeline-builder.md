# Pipeline Builder

Pipeline Builder connects events to Glyph displays. A project can be an
interactive toy, an Ambient background, or a controller with its own Glyph menu.
The same editor and blocks work in all three modes.

## Start with a working example

Open **Create → Pipelines**. **Starters** contains small examples, including a
custom menu and a game made with drawing, variables and collision blocks.
**Originals** contains the built-in toys. Originals are read-only: make a copy to
change their behavior or artwork. Copies belong to you and are not replaced by
updates to an original.

Choose **New pipeline** for a blank project. Give it a name and choose its role.
Open the interactive tutorial from the library, the editor menu, or Tutorials.
The eight chapters demonstrate the actual editor. Back and Next replay the
example; Skip, Done and the system Back button leave it. Tutorial edits use a
temporary sandbox and do not change your projects or send frames to the phone.

## Work on the canvas

- Drag empty space to pan. Pinch to zoom. **Fit all**, **Center selection** and
  **Reset zoom** help you find your blocks again.
- Add an event stack with **+ Event**. Its trigger chooses when its blocks run.
- Open **Blocks** and drag a block to an insertion marker. Tap an insertion marker
  to add a block at that position without dragging.
- Hold and drag an existing block to move it. Nested markers let you place actions
  inside a repeat, condition or other control block. Moving one block does not
  silently move all its following siblings; the block menu has a separate stack
  operation.
- Tap a block to edit its inputs. An input can be a literal value, a sensor or
  device reading, a variable, a parameter, or a nested expression.
- Use the block menu to duplicate, disable, remove, annotate, or extract reusable
  behavior. Undo and Redo restore editing changes.

The editor keeps a draft separately from the running revision. **Apply** validates
and publishes a complete revision within the app. An unfinished draft does not
replace the last working version. If another edit changed the project, resolve
the conflict instead of overwriting it. Library revision history can restore an
older applied version as a new revision.

## Events, conditions and state

An event is a change or action: a key press, a notification arriving, charging
changing, or a timer finishing. A condition describes a value now: music is
playing, the device is face down, or light is below a threshold. A conditional
trigger can react when that condition becomes true, becomes false, or stays true
for a chosen duration. Availability checks let a project handle a missing sensor
or permission deliberately.

A calendar event selects a local hour, minute and days of the week. It runs once
per local date if the pipeline is active during that minute. Missed schedules
are not replayed later. Use an overnight time-range condition for a continuous
window that crosses midnight.

Repeated events can restart a stack, be ignored while it is running, queue, or
run in parallel. Select the policy intentionally. A restartable named timer is a
simple way to implement “return five seconds after the last key press.”

Variables store state such as a score or selected menu item. Parameters are the
project's configurable settings. A routine groups reusable blocks; its parameters
and local variables belong to the call. It can return a value. Routine names are
labels, so renaming one does not break existing calls.

Durations use milliseconds internally. Sensor values preserve their units. A
gyroscope reports angular velocity, not absolute tilt. Use the orientation inputs
for face-up, face-down, pitch and roll. An unavailable reading is distinct from
zero and false.

## Build an Ambient background

Open Ambient's toy settings, then **Open Pipeline Builder**. The initial pipeline
preserves the existing background selection and behavior. The **Choose one in
order** block contains the background display blocks. Drag those nested blocks
to change their order; add or remove a display to change the cycle. Its selected
index chooses an option, and **Number of options** follows the enabled blocks
automatically. The cycle interval remains an editable quick setting.

Presentations use named slots and priorities. A music presentation can cover the
background without deleting it. Releasing that slot reveals the background
again. To let a key press interrupt music, release the music slot, restart a
return timer, and show the music presentation when that timer finishes if music
is still playing. Keep charging and other interruptions in their own slots.

Mark suitable parameters as quick settings to expose them in Ambient settings.
Appearance normally follows each individual toy's settings; a behavior block
can carry an explicit override.

## Create or customize a toy

An applied interactive-toy project becomes available in the Glyph Toys selector.
It can use an existing native behavior, your own frames or animations, drawing
and sprite blocks, or a combination of these.

Use **Artwork** to create or edit embedded designs with the existing Custom Design
Editor. Keep separate 13 × 13 and 25 × 25 artwork when the project supports both
panels. Referenced artwork must exist for each declared panel; it is not silently
stretched between phones. The project settings let you choose an artwork frame
for its carousel card and set the sample time and initial press used by the live
preview. These choices do not change how the actual toy runs.

A native behavior may expose visual slots. For example, a copied Dino behavior
can use your animation for its jump slot while retaining its movement and game
rules. Bind the artwork to that slot and configure its crop, anchor, hitbox and
playback fit. Alternatively, choose a drawing routine as a visual slot's source.
It receives the native state and phase timing, draws an immediate frame, and
returns it through the same crop and anchor. Drawing routines can calculate and
draw, but cannot wait, start toys, schedule events or cause device actions. Use
normal event stacks for behavior that must continue over time.

You can instead build the game yourself from sprites, variables,
key events and collision checks; the authored runner starter demonstrates that
route without a native Dino block.

Individual toy settings also offer **Activation rules**. Assign an applied
pipeline to react to events while a Glyph session is active. These rules are
suspended when custom controls are enabled. This feature uses the existing app
and sensor lifecycle; it does not promise a new always-running background sensor
service.

## Design custom controls and menus

A controller project decides what a key press means, what the Glyph menu looks
like, which item is selected and when a display is shown. Build the menu as part
of the project; there is no automatic standard menu inside a custom controller.
Start from the Custom menu template to see a selected index, confirmation,
return navigation and timeout working together.

After applying the controller, open **Settings → Custom controls and menus**,
choose it and enable the mode. Enabling it is only possible in Settings. The Toys
selector is unavailable while the controller owns navigation; Create, Settings
and Tutorials remain available. **Stop** pauses the controller. **Resume** starts
it again, and **Return to Standard** restores the normal toy navigation. Pocket
protection is configurable here; Android and Nothing OS restrictions still apply.

## Simulate before applying

Open **Simulate** in the editor. The preview uses the same interpreter as the
phone, with temporary sample data. Choose either panel, pause, step, advance time
or restart. Inputs lets you send events and set sample device readings, including
unavailable values. Event fields let you supply typed sample data, such as a
notification package or a named timer. Project-defined signals and timer events
appear alongside standard events. Time controls change the simulator's clock. Trace shows
executed operations and current variables; canvas highlights identify active and
waiting blocks. No simulated event changes device settings or reaches the Glyph.

## Share a complete project

From the library, share or export an applied project as `.glyph.pipeline.json`.
The file includes the required programs, routines, settings, artwork and bindings.
It does not include live sensor readings, location, notification contents,
permissions, activation flags or the execution trace.

Import reviews the file and creates a new local copy with fresh identities. An
import never replaces a matching project or enables custom controls. The format
and validation limits are documented in [Glyph pipeline format](glyph-pipeline-format.md).
