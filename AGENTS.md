# ChildWatch project rules

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
