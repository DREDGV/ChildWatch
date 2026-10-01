---
name: childwatch-release
description: Build, install, stage or publish a ChildWatch Android release when requested; preserve signatures, Room upgrade safety and separate build, device and server evidence. Do not invoke for source-only development with builds deferred.
---

# ChildWatch release

- Read TODO.md for current authorization and release state, AGENTS.md for commands. Do not infer installed versions or connected phones from history.
- Discover requested script parameters in the existing file before invocation. Normal build/install uses scripts/build-and-install.ps1; Gradle uses scripts/run-gradle-safe.ps1 only. Pass -BuildTimeoutSeconds 900 or greater for Android builds: the installer script's current default can be shorter than the project allowance.
- Name the target and confirm live device identity before installation. Use push plus pm install through the script; no streamed Nokia install. Connection and installation belong in one call when WiFi ADB is fragile.
- Keep progress visible. Inspect logs if timing diverges, but do not terminate normal compilation merely at the old 2-3 minute estimate; allow at least 900 seconds. Never remove caches or stop unrelated Java processes.
- Stage both intended applications with the existing release scripts. Preserve the established signing certificate; compare signature and versionCode against the prior intended release. Never expose keys/passwords in output or change signing to bypass an install failure.
- A Room change requires both databases' new versions, new migrations and builder registration. Validate upgrade over the prior installed version when device checks are authorized; clean installation is insufficient.
- Distinguish four facts: built, installed, user flow checked, server published. Capture artifact path/version and appropriate evidence for each. Do not close a runtime task with only BUILD SUCCESSFUL.
- Publish only through the authorized method; if the owner publishes manually, leave publication pending with exact artifacts and manifest. Do not overwrite the deployed manifest before APKs are available.
- Stop optional verification once required gates and concrete risks are covered. Update TODO and give one next useful project task; a suggestion does not authorize new external actions.