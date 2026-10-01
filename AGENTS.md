# ChildWatch project rules

## Task list is the source of truth (read this first)

`TODO.md` in the repository root holds every open request, bug and improvement,
with a status and the evidence for it. It survives session restarts, so it — not
the conversation — is what says whether something is still outstanding.

- **Read `TODO.md` before starting work** and continue from its open items
  instead of re-discovering them.
- **The moment the user asks for anything** to be checked, fixed, added or
  improved, add it to `TODO.md` — before or while doing it, never "later".
  Small items and half-understood items belong there too.
- **Mark an item `[x]` only with proof**: a file, a commit, a device check or a
  command output. "Should work" is not proof; leave it `[ ]`.
- When the user confirms something works, mark it `[x]` and note that the user
  confirmed it, with the date.
- If an item cannot be finished now, leave it `[ ]` or `[~]` and record the
  blocking reason and what is needed to continue.
- Never delete an item: mark it `[-]` with the reason so decisions stay visible.
- When replying about unfinished work, mention what was recorded in `TODO.md`.

## Efficient task continuation and skills

- Read the short TODO once at the start of a work stage; read only the chosen ID's
  section in `docs/tasks/legacy-index.md` when historical details are needed.
- `[v]` means source changes exist but the required runtime verification remains.
  `[~]` means work is happening now, not every task that once had an implementation.
- Keep current state in TODO. Historical snapshots are evidence, not a second
  current task list. Merge duplicate requests under one ID with source references.
- For ChildWatch development use `.agents/skills/childwatch-development/SKILL.md`;
  for requested build/install/release work use
  `.agents/skills/childwatch-release/SKILL.md`. If not in the session skill catalog,
  read the file directly. Skill selection guidance: `docs/agent-workflow.md`.
- Batch independent reads; search the relevant module first. Recheck live devices,
  server and manifest only before actions depending on them or after relevant changes.
- User restrictions on tests, builds and publication take precedence. Do permitted
  work and leave unmet verification explicit; do not request the same approval again.

## UI and design preparation

- Before UI/UX/frontend work read `DESIGN.md`, the relevant section of
  `DESIGN_REFERENCES.md` and `docs/design-bootstrap.md`. Reuse saved references.
- Use `.agents/skills/frontend-design/SKILL.md` as the one primary design skill.
  Add only the relevant specialized skill (design-system, accessibility-review,
  design-critique or user-research). Do not combine competing frontend skills.
- For a substantial new screen research 5-15 real examples, select 2-4, explain
  useful patterns briefly, then implement. User screenshots and existing product
  language take priority. A small fix does not require repeating the whole research.
- Inspect the actual rendered result: Android on device/emulator, web in browser.
  Source or compile success alone does not close a visual task. Record any blocked
  visual verification in TODO. Available tools and user restrictions take priority.

## Recommend the next useful work

- After completing a task or a coherent work stage, give one concrete recommended
  next project task, optionally one alternative. Base it on fresh findings and TODO;
  state the expected user-visible result and why it follows this work in one sentence.
- Record newly found defects in TODO immediately. Record speculative improvements
  as proposals awaiting selection, never as already approved implementation scope.
- Do not create a long generic roadmap or recommend more testing/building when the
  owner has asked to prioritize source development. Existing unfinished work comes first.
- A suggestion is not permission to start new scope, publish, message people or alter
  external state. If the owner already authorized autonomous continuation of a queue,
  follow that authorization. Honor explicit pauses/stops without proposing more work.

## Room schema changes: always bump the version

Adding a column to a Room entity without raising the database version crashes the
application on the next launch with
`Room cannot verify the data integrity. Looks like you've changed schema but
forgot to update the version number.`

This happens because the installed build already carries that version number with
the old schema, and Room refuses to open a mismatched database. The failure is not
limited to the feature being changed: every screen that touches the database dies,
so a chat change took down chat, listening and the map at once.

Rules that follow:

- **A new column needs a new version and its own migration.** Never add statements
  to a migration that has already shipped — devices at that version will never run
  it again.
- Bump the version in **both** applications: they keep separate databases with
  separate version numbers, and a change to shared entities affects both.
- Register the new migration in the database builder, not just in the migration
  file.
- Verify by installing over the previous build, not by a clean install: a clean
  install creates the schema fresh and hides the mistake.

## Building and installing: one script, and never go silent

Use `scripts/build-and-install.ps1` instead of typing Gradle, push and install
commands step by step. It prints a timestamped line at every stage.

```powershell
# build both apps and install both, in one call
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-and-install.ps1

powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-and-install.ps1 -InstallOnly
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-and-install.ps1 -Target child
```

Timings measured on this workstation (2026-09-17). A deviation means something
is wrong, not that more patience is needed:

| Step | Expected |
|---|---|
| `assembleDebug`, no source changes | ~25 s |
| `assembleDebug`, after Kotlin edits | 2-3 min |
| `pm install` per device, after a push | 5-20 s |
| `adb install` streamed, on the Nokia | **fails after ~5 min — never use it** |

Rules that follow from those numbers:

- **Report progress while a long command runs.** Never start a multi-minute
  build and stay silent: state what is running and how long it should take. Run
  it as a background job and read its output rather than waiting with no output.
- **Inspect output when timing diverges; do not wait silently.** The estimates
  above describe older warm builds. Do not terminate normal Android compilation
  before the 900-second allowance in the safe-execution section.
- For `build-and-install.ps1`, pass `-BuildTimeoutSeconds 900` or greater for
  Android compilation; its current default is shorter than that allowance.
- Run independent steps in the same call — for example assembling and checking
  devices together — so wall-clock time is not spent sequentially.
- Install with push + `pm install`; never the streamed path on the Nokia.
- This ADB setup is fragile: the daemon dies between tool calls, taking WiFi
  sessions with it. Connect and install in the same call, or use USB.

## Safe Gradle execution on Windows

- Do not invoke `gradlew.bat` directly for normal checks or builds.
- Run Gradle through `scripts/run-gradle-safe.ps1`.
- The wrapper keeps Gradle and Android caches inside the ChildWatch workspace,
  prevents parallel ChildWatch builds, uses plain console output, and disables
  the persistent Gradle daemon.
- Give Android compilation at least 900 seconds before treating it as stuck.
  A clean multi-module compile on this Windows workspace can take ten minutes.
- Never create timestamped Gradle cache directories and never delete a cache
  merely because a build was interrupted.
- If a build process was interrupted, first rerun the same safe command. Use
  `scripts/run-gradle-safe.ps1 --stop` only when a Gradle daemon is actually
  left behind.
- Do not stop unrelated Java, Android Studio, or VS Code processes.

Typical verification command:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-gradle-safe.ps1 :shared-core:test :app:compileDebugKotlin :parentwatch:compileDebugKotlin
```
