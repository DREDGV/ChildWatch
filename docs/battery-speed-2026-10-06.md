# Battery gauge and visible map speed — 06.10.2026

Current tasks: CW-29/BATTERY-GAUGE06.10 and CW-06/MOTION-SPEED06.10 in TODO.md.

The selected child's home card now contains a shared BatteryGaugeView. Ten cells
represent ten percent each, with exact partial fill of the last cell. The adjacent
text preserves the actual percentage and charging state; capture time stays below
the row. Fresh charge is red through 15%, amber through 35%, then green. Stale,
missing-time and future-time snapshots use neutral fill. Unknown charge has a
question mark, distinct from an empty red 0% battery. The charging bolt is outside
the cells. Changing the selected device clears the previous gauge immediately.
The decorative view does not duplicate the adjacent accessible text.

Source boundary review: 0 has no filled cells and red outline; 1 has one tenth of
the first cell; 10 one full cell; 20 two; 50 five; 100 ten. Invalid/null charge
uses unknown. This is source review, not proof of Android rendering.

MapMemberStrip previously suppressed every compact speed label. It now shows
the selected person's existing speed beneath their avatar, leaving other compact
tiles unchanged. The speed and family-row settings still control visibility.
Both map activities distinguish coordinates older than 45 seconds with a stale
speed label. Existing measured speed, estimated speed prefixed with approximately,
and unavailable speed remain distinct; missing speed does not become zero.
The finite marker animation from MOTION-EFFECTS06.10 remains in source; it is
not included in the previously installed 7.4.288.

Validation: safe wrapper printVersionInfo, design-system:testDebugUnitTest and
both Android compileDebugKotlin tasks passed in 1m30s, 72 tasks. The six marker
motion policy tests report zero failures/errors/skips. Log:
`.runtime/battery-speed-version-2026-10-06.log`. Resolved version is 7.4.289;
compilation did not create a real version reservation. Version-policy fixtures
also passed separately in `.runtime/version-policy-2026-10-06.log`.

No APK packaging, installation or publication occurred. No phones are available
for rendered verification. Next native check: a new build above 288, portrait and
landscape with large text, battery boundaries/charging/stale/unknown and child
switching; selected compact map speed, measured/estimated/stale data, fresh precise
movement, history, animation menu and system animation switch.
