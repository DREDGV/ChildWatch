# Family map battery contract — 07.10.2026

Current status and follow-up remain in TODO.md, CW-06/BATTERY-CONSISTENCY07.10.

## Confirmed cause and correction

Installed289 home displayed the selected child's99% charging telemetry, while
its family map card displayed no charge. The family/latest endpoint only returned
location/motion. Both network DTOs omitted battery; parent converted its family
point to ParentLocationData with null battery, and child converted through
FamilyMarkerCandidate and rendered an unconditional unknown string.

The additive family/latest field is `battery: null` or:

```json
{"deviceId":"selected-device","level":99,"isCharging":true,"timestamp":1791340000000}
```

The timestamp is the device-status capture, independent of GPS time. Read only
after family membership and LOCATION visibility have authorized the chosen point.
Only statuses of that exact chosen phone and its recognized id spellings qualify.
Do not use a sibling phone, another device belonging to the same person, or phone
charge uploaded with some older location. The newest status with unknown charge
stays unknown rather than borrowing an earlier value. 0% is a valid measurement.
Malformed/future capture or invalid charge fails closed. A telemetry query failure
does not remove an otherwise authorized GPS point. No database schema changed.

Both clients carry the optional snapshot unchanged to the card, validate its
deviceId, and show charge/charging with the battery's own capture date. Older than
10 minutes is marked outdated. GPS accuracy and GPS update time retain their own
meaning. Older servers return unknown, never fabricated zero. The legacy paired
location path remains unchanged in this bounded stage.

## Evidence and deployment boundary

Three Node tests passed: real in-memory SQLite checks two id spellings, sibling
isolation and newest-null; endpoint checks401/403/LOCATION denial before telemetry
read, separate capture clocks, GPS retained when battery fails; validation checks
null/fraction/out-of-range/future/stale/unknown charging. Log:
`.runtime/family-battery-2026-10-07.log`.

Existing location access/id spelling suites:14 tests passed,
`.runtime/battery-location-access-2026-10-07.log`.

Both Android compile tasks and design-system unit tests passed in1m35s/71tasks.
Four battery policy tests plus six motion tests have zero failures/errors/skips.
Log: `.runtime/battery-consistency-2026-10-07.log`. Node syntax and diff checks
passed. Compilation does not reserve a release number.

No new APK was built or installed and no server was deployed. Installed289 does
not contain this fix. Required server files: routes/location.js and
services/FamilyBatterySnapshot.js. A new client release must exceed289 (automatic
candidate290). Native date wrapping, stale display and context switching remain
open; source tests do not prove rendered or deployed behavior.

## Proposed next bounded work, awaiting selection

The two family maps currently transform the same point through different models.
That is the broader source of this field-loss class of bug. Share a family-point
contract and mapping first: device/member identity, independent GPS/battery/motion
capture clocks, optional fields, and selected-context transitions. Acceptance
should cover old-server fields, null versus zero, replacing the device, changing
family/person and late replies. Preserve UI and permissions; do not start a full
map rewrite merely because this preparation exists.

The separate unfinished audio recovery task needs session-correlated evidence
across command acceptance, capture, upload, reception and playback. Existing START
retries and the60-second watchdog do not explain the reported20–30-second field
failure. This is a diagnostic gap, not proof that the microphone or server caused
that specific failure; do not shorten a timeout without evidence.
