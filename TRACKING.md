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

## Latest Verified Work

### 2026-07-17: Server family foundation (stage 2)

- Added `families`, `family_members`, `family_devices`, and `family_permissions` while preserving `device_links` as the compatibility source.
- Added a deterministic, transactional, idempotent bootstrap from active legacy links; repeated WebSocket registration does not rerun the full migration, explicit permission denials are preserved, and merged family history is retained as inactive records.
- Added authenticated family/member/device read endpoints and a closed-by-default permission service with cross-family denial.
- Added neutral `deviceSockets: Map<deviceId, Set<socketId>>` plus exact-device emission with no fallback to another connected phone. Existing audio routing remains unchanged.
- Verification passed: 9 server suites/39 tests, shared-core tests, both 7.2 debug APK builds, `node --check`, `git diff --check`, and non-strict `utf8Guard` with only the previously documented warnings.
- The stage has not been deployed to the VDS and has not touched the production database; real-server migration and token checks remain deployment gates.
- Full report: `docs/modernization/STAGE_2_SERVER_FAMILY.md`.

### 2026-07-17: ActiveContext foundation (stage 1)

- Added a shared `ActiveContext` model and resolver used by both Android applications.
- Added canonical stores, effective-context providers, idempotent legacy migrations, masked diagnostics, and stable local family/member IDs.
- Context resolution now follows `canonical -> active session -> secure/current settings -> legacy profile/prefs`; blank values cannot erase valid lower-priority values and self cannot become target.
- ParentMonitor and ChildDevice chat/map navigation now consume the same selected target; local chat/map preference keys are context-namespaced.
- Legacy keys remain mirrored for compatibility. Audio and remote-photo internals were deliberately kept outside stage 1.
- Verification passed: 11 shared-core tests, both Android Kotlin compilations, 6 server suites/26 tests, and non-strict `utf8Guard` with only the previously documented warnings.
- Full report: `docs/modernization/STAGE_1_ACTIVE_CONTEXT.md`.

### 2026-07-17: Baseline 7.2 protection

- Created `chore/baseline-7-2-protection` from `main` at `6c67319f45811196da862ab02845da5696d81016`; no product behavior or UI was changed.
- Added the approved modernization material package under `docs/` and recorded the reproducible baseline in `docs/modernization/BASELINE_7_2.md`.
- Server verification passed: 6 Jest suites and 26 tests.
- ParentMonitor and ChildDevice debug APKs built successfully with version base 7.2.
- Default `utf8Guard` completed with warnings; strict mode failed on one damaged comment in `app/MainActivity.kt` and false positives under `server/node_modules`.
- `printVersionInfo` is currently broken, although Android output metadata confirms the correct 7.2 version.
- No phones were connected during this baseline run, so real-device smoke scenarios were documented but not marked as newly passed.

### 2026-07-17: Real-device recovery pass

- Both applications and the server were rebuilt and tested after a real VPS interruption and phone reconnect.
- ChildDevice now has stronger restart, reconnect, audio-capture, and WebSocket recovery paths; ParentMonitor has matching chat/listening status handling.
- Real-device checks confirmed chat, device status, location updates, and listening can recover after the child app is opened following a reboot.
- Camera2 resource cleanup, stale callback protection, short retry backoff, and automatic audio resumption after a photo were added.
- Android 11 on the tested Moto G 5 Plus still blocks remote camera access when ChildDevice is backgrounded. Camera works with ChildDevice open; the parent now receives an explicit restricted-background diagnostic instead of a generic failure.
- Release baseline is now `7.2`; the full scope and known limitation are recorded in `CHANGELOG-v7.2.0.md`.

### 2026-07-14: Server Authentication Baseline

- removed the duplicate token store from `server/index.js`
- `/api/auth/validate` and `/api/alerts` now use the same `AuthManager`-backed middleware as device registration
- authorization parsing now requires one well-formed `Bearer` credential
- added staged Socket.IO handshake authentication through `SocketAuthMiddleware`
- both Android applications now attach their saved token to the Socket.IO handshake
- authenticated sockets reject parent/child registration identity mismatches
- `CW_REQUIRE_WS_AUTH=0` is the compatibility default; set it to `1` only after updated devices have re-registered
- made the SQLite path independent of the process working directory and configurable with `CW_DB_PATH`
- authentication sessions now survive normal server restarts through `CW_AUTH_SESSION_PATH`; only SHA-256 token hashes and device metadata are persisted
- expired access tokens retain refresh recovery for up to seven days, so a phone returning after a long offline period can recover without manual intervention
- both boot receivers now react to application replacement, and ChildDevice boot recovery isolates foreground-service start failures so LocationService can continue self-healing
- ChildDevice HTTP location/auth traffic and Socket.IO now share one token store; existing `parentwatch_prefs` tokens are migrated without deleting legacy state
- both apps rebuild their Socket.IO connection after successful HTTP registration so a connection started before registration cannot keep retrying with a missing or stale token
- added Jest coverage for HTTP authentication, Socket.IO rollout modes, and database path configuration
- verified with `npm test -- --runInBand` (22 tests passing), `node --check`, Android Kotlin compilation, and an integration test that registers a phone, stops the server process, restarts it, and validates the original token

Deployment status on 2026-07-14:

- the Hoster control panel shows the VPS as running and shows PM2-owned `node /var/www/childwatch/index.js` under `adminuser`
- direct checks from the development environment still time out before SSH authentication and receive no HTTP response on ports 80 or 3000; no remote files were changed and no remote process was restarted
- debug APKs were assembled successfully as `ParentMonitor-v7.1.26195.011553-debug.apk` and `ChildDevice-v7.1.26195.011553-debug.apk`; no Android device was connected, so neither APK was installed
- a WinSCP/PuTTY runtime-only deployment archive was prepared as `server/childwatch-server-reconnect-update-20260714.zip` (SHA-256 `0565D1225C25D993D73FB677D97F164156CD2B621D7B2801DE48A320657E6AA8`); it contains no database, uploads, sessions, logs, or dependencies

Deliberate next-step boundary:

- modular chat, location, media, and streaming routes are not yet globally protected
- Socket.IO authentication is not mandatory until `CW_REQUIRE_WS_AUTH=1` is enabled after rollout
- modular HTTP paths must be secured together with parent-child relationship authorization and Android client compatibility tests, rather than by adding middleware blindly

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
- the profile system is still structurally weak and can desynchronize map, chat, photo, listening, and notifications

### Medium Priority

- some screens still contain old wording or legacy layout choices
- UI polish is uneven because fixes were done in urgent passes
- server and client protocol compatibility must always be checked for map, listening, and photo flows

### Documentation

- old docs in `docs/`, `archive/`, and older changelogs may describe obsolete behavior
- `README.md` is useful for orientation, but not every implementation detail there is current
- this file should be updated whenever priorities or architecture change materially

## Profile System Roadmap

This is the active roadmap for the profile system. It replaces any older assumptions that a profile is just one parent paired with one child.

### Why This Is A Core Problem

The current implementation is still centered around direct writes into global prefs such as:

- `device_id`
- `child_device_id`
- `parent_device_id`
- `selected_device_id`
- `linked_parent_device_id`

This makes the effective runtime context too fragile. Different features can end up reading different IDs and acting on different targets.

The current pair-shaped profile managers:

- `app/src/main/java/ru/example/childwatch/utils/ParentMonitorProfileManager.kt`
- `parentwatch/src/main/java/ru/example/parentwatch/utils/ChildDeviceProfileManager.kt`

are useful as a temporary UI layer, but they are not a sufficient foundation for family scenarios or reliable switching across all features.

### Product Rules To Keep Fixed

Before implementation, the following rules are treated as deliberate product decisions:

1. Do not introduce special `test IDs` into the production domain model.
2. Testing should be handled by separate test builds and/or separate workspaces, not fake identities.
3. Profiles must work across all major features: map, chat, remote photo, listening, notifications, and media/history.
4. The system must support more than a simple `one parent <-> one child` relationship.
5. The active runtime context must be explicit and inspectable.

### Target Domain Model

The profile system should be rebuilt around these entities:

1. `Family`
2. `Member`
3. `Device`
4. `Role`
5. `PermissionSet`
6. `ActiveContext`

Practical meaning:

- `Family` is the shared workspace
- `Member` is a person inside the family
- `Device` is a concrete phone/tablet
- `Role` defines base access
- `PermissionSet` defines feature-level access
- `ActiveContext` defines what this phone is currently acting as and which child is in focus

### Required Runtime Semantics

The effective context on a device must be split into:

1. active family/workspace
2. active member identity on this phone
3. active child focus for feature actions
4. active conversation or feature target where relevant

This is intentionally different from the current model where a saved profile often equals one pair of IDs.

### Cross-Feature Behavior Rules

#### Listening

- One child device should have only one active listening session at a time in the first stable family-capable version.
- If a second parent tries to start listening, the system should show who already owns the session.
- Later fan-out or shared listening can be considered as a separate project, not part of the first stabilization wave.

#### Chat

Chat should support separate conversation types, not one overloaded thread:

1. direct parent-to-child chat
2. family chat
3. optional future parent-only/admin chat

Each conversation type must have its own identity, unread state, and history.

#### Map

The map should operate in a family scope, but with a focused child and member filters:

- parent default: selected child + self, optional other family members
- child default: self + guardians, not every family member by default

The map must not depend on ambiguous fallback IDs to decide who is being shown.

#### Remote Photo

- remote photo must always target a concrete child device
- resulting photos should belong to the family archive with requester metadata
- feature access must be permission-based, not inferred only from device IDs

### Implementation Strategy

The system should be implemented in two major waves.

#### Wave 1: Stable Multi-Profile Core

Goal: make switching reliable without requiring the full server-side family model first.

Work items:

1. introduce a shared profile core with `ProfileStore`, `ActiveSessionManager`, `EffectiveContextResolver`, and `LegacyMigration`
2. stop letting feature code read raw profile prefs directly
3. route map, chat, photo, listening, and notifications through one effective context source
4. namespace local caches, DB data, and media by effective context so profiles do not bleed into each other
5. add a visible diagnostics block showing the effective runtime context

Expected result:

- quick switching between real devices becomes reliable
- test and working contexts stop fighting over the same raw prefs
- all features act on the same selected child

#### Wave 2: Family-Capable Model

Goal: support larger real-world family structures cleanly.

Work items:

1. add explicit family/member/device/link entities on the server
2. move permissions to server-backed membership instead of local inference
3. support several parents and several children inside one family
4. make feature access role-aware and child-aware
5. separate family-wide data from child-specific data

Expected result:

- multiple parents can work in one family context
- multiple children can exist without profile hacks
- permissions become explicit and auditable

### Safe Rollout Order

To reduce regressions, the implementation order should be:

1. finalize the domain model and diagnostics
2. build the new local profile/session core
3. migrate legacy profile data into the new model
4. move map and chat to the new resolver first
5. move remote photo and listening after that
6. only then expand server-side family support
7. remove legacy pair-based assumptions after the new path is stable

### Explicit Non-Goals For The First Pass

The first pass should not try to do all of these at once:

- no fake `test IDs`
- no simultaneous multi-parent listening fan-out
- no giant all-in-one family super-chat replacing direct chats
- no big visual redesign before the context model is correct

### Acceptance Criteria

The profile system can be considered successfully rebuilt when these conditions are true:

1. switching profile changes the same target across map, chat, remote photo, listening, and notifications
2. the app can show the effective active family, member, and focused child at any time
3. local caches and media do not mix data from different families or contexts
4. there is no longer a situation where the active profile points to one child while feature code silently uses another
5. larger family scenarios can be modeled without inventing fake IDs or abusing legacy prefs

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

1. Implement the server-backed family/member/device/membership model while preserving current pair-based compatibility.
2. Continue real-device stabilization of maps, listening, remote photo, and chat without bypassing the new ActiveContext.
3. Keep removing mojibake and prevent new encoding regressions.
4. Avoid drift between server behavior, Android code, and documentation.
5. Prefer updating this file over creating another roadmap document unless a separate spec is truly needed.
