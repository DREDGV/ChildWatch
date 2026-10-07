# Network recovery and protected child shortcut — 07.10.2026

Current tasks are CW-01/NETWORK-RECOVERY07.10 and RECOVERY-SHORTCUT in TODO.md.

The user reported delayed first sound and suspected a break while switching
networks, resolved by restarting child monitoring. That field cause remains
unconfirmed. Only the working Moto was connected during this source stage;
the bounded child log capture contains no events of the reported earlier failure.

## Bounded transport correction

The child previously relied on Socket.IO reconnection and socket-ready flags.
There was no explicit Android default-network supervision in this path. A route
can change while those flags still describe an earlier connection; accepting an
audio emit locally also does not prove receipt at the server or parent.

DefaultNetworkRecoveryObserver now watches the active default route, waits for
INTERNET and VALIDATED capabilities, and rebuilds the shared socket after a500ms
debounce. It preserves the client and command-listener registrations. Initial
ready route and repeated capabilities do not churn transport. Loss of an old
route after a new route has appeared does not tear down the new route. Recovery
after offline also triggers a reconnect. No SSID or audio content is logged.

Disconnect and cleanup close the observer and invalidate pending recovery;
the callback checks the expected client and whether connection is still requested.
Manager lifecycle operations are synchronized. Recovery changes transport only:
it does not start the microphone, bypass Android permissions, unpause a photo
capture or undo STOP. Existing desired capture can continue using the registered
shared client after transport returns. Socket.IO reconnect remains the fallback
if Android observer registration fails. Server changes are not required here.

## Protected manual entry

ChildDevice home has a separate restart button within its connection card.
It requires the existing settings PIN. The new entry cannot create a PIN;
no PIN, wrong PIN and cancellation never invoke runtime refresh. Desired monitoring
and configured child context are checked before the existing coordinator runs.
The button is disabled while the operation runs. The completion message confirms
a restart request, not an established server connection. Existing first-time PIN
setup through ordinary settings remains unchanged and is not reworked here.

## Evidence and remaining proof

An initial policy baseline lacking explicit route recovery failed four of five
guard tests in23s: `.runtime/network-recovery-red-2026-10-07.log`. This was a
deterministic policy seam demonstrating required transitions, not replay of the
user's real-device failure. Five policy tests now pass (route switch, return of
same route after loss, late loss, offline start, duplicate/initial callbacks).
All95 shared-core tests pass with zero failures/errors. Both Android Kotlin
compile tasks passed in1m36s/68tasks:
`.runtime/network-recovery-green-2026-10-07.log`. XML/diff checks passed.

No new APK was packaged/installed, no server deployed and no working-phone network
was changed. Installed289 does not contain this stage. Android callback ordering,
debounce/cleanup races, numerical first-packet latency, PIN flow rendering and
capture behavior across actual WiFi/cellular changes still require device proof.

Next bounded run: new client above289; record child command acceptance/capture/
socket registration and parent packet/playback times in the same session. Exercise
WiFi→cellular, offline→return, rapid route changes and STOP/photo pause during the
handover. Check protected restart cancellation/wrong PIN first; enter correct PIN
on the phone only. Do not infer microphone failure from silence or delivery from
a local emit-success log. If delay remains, correlate command, capture, server
relay and playback before adding further recovery behavior or changing timeouts.


## Device evidence, release290 and reserve recovery, 07 October

The earlier source-only status above is historical. Both signed clients290 were
built, staged and installed over289. Evidence: `.runtime/release290-smoke` and
`.runtime/release290-{build,stage,install}-2026-10-07.log`.
WiFi111→cellular112→WiFi113 continued receiving, with one playback underrun.
OFF→cellular114→START received516480 bytes with underruns; OFF→WiFi→START
received604800 bytes with zero underruns in the15-second sample. Parent stop
and child stop OK were observed. Human hearing/first-packet latency and all
photo/STOP race permutations are not established by these packet measurements.
A USB disconnect prevented the first finally from restoring WiFi; after USB
returned, explicit restoration was checked: wifi_on=1, mobile_data=1.

A subsequent source stage adds TransportRecoveryPolicy and a child-manager
watchdog. Every5 seconds it checks registration and real server pong, sends a
ping every25 seconds on usable validated routes, and rebuilds transport after
30 seconds of failed readiness or60 seconds without server liveness. Retries
back off up to120 seconds; successful registered pong resets the series.
Offline and explicitly disconnected clients do not trigger fallback. Cleanup
removes the runnable and client identity fences late callbacks. Microphone,
photo pause, desired monitoring and permission choices are untouched. This is
transport recovery, not evidence that a stalled recorder or audio relay is healthy.
The existing server pong handler needs no deployment. Release290 does NOT
contain this later watchdog; on-device validation requires a new code>=291.

Reserve recovery validation:101 shared-core tests passed (6 new policy cases),
child Kotlin compile passed in1m23s/50tasks. Evidence:
`.runtime/auto-fallback-green-2026-10-07.log`. The two initial sandbox attempts
failed at AAPT2 process startup, then the permitted retry completed. Native
server blackhole/capture-only stall testing remains open in TODO.
