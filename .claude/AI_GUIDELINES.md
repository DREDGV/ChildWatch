# AI Agent Guidelines for ChildWatch

## 1. Changelog Management
- **Never overwrite older entries.** Always add new releases to the top and keep the entire history.
- Main changelog lives in `CHANGELOG.md`.
- Optional detailed notes can be saved in `CHANGELOG-vX.Y.Z.md` if needed.
- Each entry must include both app versions (ChildWatch + ParentWatch), the release date, and subsections such as *Added*, *Improved*, *Fixed*, *Technical*, *Documentation*, *Breaking Changes* (only when applicable).

## 2. Version Numbering
- Follow Semantic Versioning (MAJOR.MINOR.PATCH).
- Update both modules when changes affect both. If a change is limited to a single module, bump only that module.
- Files to touch when bumping a release:
  1. `app/build.gradle` – update `versionCode` and `versionName`.
  2. `parentwatch/build.gradle` – update `versionCode` and `versionName`.
  3. `CHANGELOG.md` – add the new entry at the top.

## 3. Commit Messages
Use the conventional format:
```
<type>(<scope>): <subject>

<body>
```
Examples of `<type>`: `feat`, `fix`, `refactor`, `perf`, `docs`, `style`, `chore`.
Always make sure the message reflects the actual change.

## 4. Pre-commit Checklist
- [ ] The project builds (`./gradlew assembleDebug` for both modules).
- [ ] Version numbers are consistent across modules and changelog.
- [ ] `CHANGELOG.md` is updated and previous entries are preserved.
- [ ] No secrets or local environment data are included.
- [ ] Only necessary files are staged (IDE caches such as `.idea/` or `.vs/` must be ignored).

## 5. Build & Release Workflow
```
./gradlew clean
./gradlew :app:assembleDebug :parentwatch:assembleDebug
```
The resulting APKs are located in:
- `app/build/outputs/apk/`
- `parentwatch/build/outputs/apk/`

## 6. Documentation
- Significant user-facing changes should be reflected in `README.md`.
- Keep this guideline file up to date whenever the workflow changes.

**Remember:** project history is valuable—never discard it.
