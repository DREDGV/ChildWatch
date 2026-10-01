# Family Map Interaction Implementation Plan

**Goal:** Make every family member discoverable on the map, with a useful top panel, readable accuracy, distance, tracks and place events.

**Architecture:** Keep geographic coordinates true. Resolve marker collisions in screen space so every avatar remains visible, drawing a leader to its true point, and provide a persistent person strip above the map. Reuse the existing family latest endpoint for current position; add family history and server place events in later independent slices.

**Tech Stack:** Android Kotlin, osmdroid, Node.js/SQLite. `TODO.md` section 10 is the living requirement and status record.

## Constraints

- Preserve the dirty first-run and release work owned by the other agent.
- Keep the parent and child maps behaviorally consistent.
- Use `scripts/build-and-install.ps1` for device installation and `scripts/run-gradle-safe.ps1` for direct Gradle commands.
- Verify map interaction on installed builds before closing the TODO items.

## Slice 1: Marker visibility and selection

- Add a small screen-space marker placement helper with stable member IDs, measured marker bounds and a non-overlap constraint. It returns display anchor pixels without changing stored coordinates.
- In `app/.../DualLocationMapActivity.kt` and `parentwatch/.../DualLocationMapActivity.kt`, render each person separately at the resolved screen position. A leader line points to the true GPS position; tapping the avatar opens that person's card.
- Add a horizontally scrollable avatar strip below each map toolbar. Show name and point age; tapping centers on the true point and opens its card. Mark stale positions visibly.
- Reduce accuracy circle fill alpha while retaining the real radius and an accessible accuracy value in the person card.
- Build and install over existing apps on connected family phones, then inspect grouped and separate positions in both maps.

## Slice 2: Distance and tracks

- Compute distance only when both latest points have usable coordinates; display the approximate value and point ages for the selected member. Surface the active listened-to person's distance on the main screen.
- Add an access-controlled family history API by member and time range. Show a selectable member route and a short rolling live tail, with breaks for gaps or implausible jumps.
- Install and inspect an actual moving phone route before marking the feature done.

## Slice 3: Saved places and events

- Define a family place with center, radius, member targets and notification preferences. Persist it on the server.
- Compute arrived/present/left transitions with GPS accuracy, dwell time and hysteresis to avoid noisy repeats. Deliver a notification and show recent place events in the map.
- Check the transitions on a real trip, including poor GPS and lost connectivity.
