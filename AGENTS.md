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
- **A build that exceeds its expected time is a problem, not patience.** At
  roughly twice the expected duration, stop waiting, read the output, and report
  what it is stuck on.
- **If it has not worked within about a minute past the expected time, say so**
  and switch to diagnosing instead of continuing to wait.
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
