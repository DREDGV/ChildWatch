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

1. Rebuild the profile system foundation using the roadmap above.
2. Continue real-device stabilization of maps, listening, remote photo, and chat without bypassing the new profile model.
3. Keep removing mojibake and prevent new encoding regressions.
4. Avoid drift between server behavior, Android code, and documentation.
5. Prefer updating this file over creating another roadmap document unless a separate spec is truly needed.
