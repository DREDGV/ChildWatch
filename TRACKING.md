# ChildWatch Tracking

## Source Of Truth

This file is the current status of the project for active work.

If old README sections, archived docs, changelogs, or feature notes disagree with the code, trust in this order:

1. current code in `main`
2. this `TRACKING.md`
3. feature-specific docs only when they clearly match the code

## Module Mapping

- `app/` = `ParentMonitor`
- `parentwatch/` = `ChildDevice`
- `server/` = backend used by both Android apps

The repository naming is historical and does not match the real product roles.

## Current State

The project is active and partially stabilized after several waves of refactoring.

The key practical rule for anyone entering the repo now:

- documentation quality is uneven
- some docs describe past architecture
- current behavior must be checked against code and recent device testing

## What Is Already Improved

### Chat

- delivery path is more stable than before
- duplicate UI and service handling was reduced
- chat UI was modernized
- emoji flow was expanded
- notification settings became more complete

### Remote Photo

- remote photo works noticeably better than before
- parent preview crash path was reduced
- child-side capture flow was hardened
- gallery and history still need more real-world verification

### Listening

- parent-side UI was refreshed
- gain buttons `x1-x5` were fixed visually
- start path was hardened on both parent and child sides
- live child battery during listening was improved
- listening works better than before, but still requires repeated real-device testing

### Map

- map screens were refactored several times
- stale-data handling is better than before
- pair-based logic exists in code
- route and device-id compatibility were improved

Map is still one of the least trustworthy parts of the project and must be validated against the real server and real devices.

### Battery

- child-side power usage was reduced in code
- location became more adaptive
- command polling became less aggressive
- unnecessary background work was reduced

This area still needs real device measurement.

## Known Problem Areas

### High Priority

- map can still show missing, stale, or mismatched location data depending on server and device-id state
- listening can regress under background, reconnect, or device-sleep conditions
- remote photo history and gallery still need more validation than the live preview path
- long-term data retention is only partially improved and remains ongoing work

### Medium Priority

- some screens still contain old wording or legacy layout choices
- UI polish is uneven because fixes were done in urgent passes
- server and client protocol compatibility must always be checked for map, listening, and photo flows

### Documentation

- old docs in `docs/`, `archive/`, and older changelogs may describe obsolete behavior
- `README.md` is useful for orientation, but not every implementation detail there is current
- this file should be updated whenever priorities or architecture change materially

## Current Working Assumptions

- `main` should represent the latest sharable project state
- a new engineer or AI opening the GitHub repo by default should read `README.md` and then this file
- local folders like `.android`, `.idea`, and `.codex-logs` are not part of the product state and should not be treated as documentation

## Recommended Workflow For New Contributors

1. Read `README.md`.
2. Read this `TRACKING.md`.
3. Confirm module mapping: `app = ParentMonitor`, `parentwatch = ChildDevice`.
4. Inspect the specific feature code before trusting older docs.
5. Treat maps, listening, and remote photo as integration-heavy features that depend on both apps and the server.

## Immediate Priorities

1. Continue real-device stabilization of maps, listening, remote photo, and chat.
2. Keep removing mojibake and prevent new encoding regressions.
3. Avoid drift between server behavior, Android code, and documentation.
4. Prefer updating this file over creating another roadmap document unless a separate spec is truly needed.
