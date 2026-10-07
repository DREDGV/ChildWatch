# Client version numbering

Both Android applications use one root version. `release-version.json` records
the minimum issued code (currently `2000000288`). Keep this file and staged
release manifests in source control when recording a release. A new clone must
know the issued floor even when APKs and Git history are absent.

Automatic candidate = max(issued floor, releases/updates manifests, APK output
metadata for our packages, last local reservation, 2,000,000,000 + Git commit
count) + 1. Without Git there is no date-based fallback: the current clean-copy
candidate is `2000000289` / `7.4.289`. Malformed version evidence stops the build
instead of guessing. The maximum allowed versionCode is 2,100,000,000.

Gradle configuration, sync, compile, tests and `printVersionInfo` read this
state without writing it. `scripts/run-gradle-safe.ps1` reserves a packaging
invocation's number under its existing project mutex and passes that exact
number to Gradle. Both APKs assembled in that invocation share it. Local state
is `.runtime/version-reservation.json`, written atomically; one previous state
is retained as `.bak`. This state has no credentials and is ignored by Git.

An explicit `-VersionCode` / `-PcwVersionCode` must exceed known codes. To repair
a failed/partial build, the latest reservation may be reused explicitly when
it has not been staged/issued and no higher APK metadata exists. Automatic
retry selects a new number; a skipped number is harmless. Once staged, the code
cannot be used to build different APK bytes. Compile/inspection may explicitly
select an old valid code without packaging or reserving it.

Normal build/install still uses `scripts/build-and-install.ps1`; signed release
uses `build-release.ps1`; both reach the safe runner. `release-updates.ps1`
combines local state with the freshly read server manifest before choosing a
number and still verifies the live manifest immediately before publication.
There is no device/network access during ordinary Gradle configuration.

Changing/deleting local evidence, or a machine that lacks evidence of an
unpublished manual build from another machine, cannot guarantee a global
counter. Carry its manifest or advance the tracked floor before building.
There is no background server allocator and no server/device state mutation
in this source change.

Verification: `powershell -NoProfile -ExecutionPolicy Bypass -File
scripts/test-version-policy.ps1` passes 15 shared PowerShell/Groovy vectors plus
file discovery, stable read-only resolution, atomic replacement, corrupt
manifest/reservation rejection and inspection-code checks. A fixture-only
`gradlew.bat` echoes arguments to verify safe-runner compile does not reserve,
both APK tasks share one number, explicit repair works and a staged-number
retry stops before invoking the wrapper. It runs Groovy with
cached Gradle jars directly, without starting Gradle or touching real release
state. Current workspace read-only state: issued/built 2000000288, reserved 0,
Git count 285, next candidate 2000000289. Root verification also passed:
safe runner `printVersionInfo :design-system:testDebugUnitTest
:app:compileDebugKotlin :parentwatch:compileDebugKotlin`, 1m30s / 72 tasks,
reported 7.4.289 / 2000000289 without creating a real reservation. Evidence:
`.runtime/battery-speed-version-2026-10-06.log`; root fixture rerun:
`.runtime/version-policy-2026-10-06.log`. No APKs have been built, installed or
published for this change. Packaging integration has fixture proof; the next
real release must still check both output APK codes.
