# ChildWatch7.4.291 — 07.10.2026

Both working phones upgraded over290, without clearing data. Parent Samsung
R5CX71CKKJW (ru.example.childwatch) and child Moto ZY227XB2QD
(ru.example.parentwatch.debug) report code2000000291. Child assistant selection
remains FamilyAssistantService; WiFi and mobile data remained enabled.

The normal build-and-install BuildOnly reserved one new code automatically,
passed in110 seconds and used the permanent project certificate. Staging
releases/7.4.291 checked metadata/package/certificate/raw versus staged hashes.
InstallOnly push+pm install-r succeeded: parent3-second install, child16-second
install. Logs: .runtime/release291-{build,stage,install}-2026-10-07.log.

Native screenshots .runtime/release291-smoke/{parent,child}-home.png were
inspected. Both app processes were alive; selected Leva/family profiles survived;
parent100% battery gauge and child protected restart button are visible.
The child received8 real server pong responses over the90-second log sample;
no fallback deadline restart and no audio start marker occurred while listening
was OFF. Evidence: child-watchdog-healthy.txt. This proves normal control-path
liveness on the existing server, not recovery from a server blackhole, recorder
stall, delayed sound, every STOP/photo race, or long field use.

New reserve watchdog is included in291; current101 shared-core tests and child
compile passed before packaging. Version290 network samples demonstrated new
START in WiFi/cellular and active route handover; cellular underruns remain
recorded in TODO. User hearing proof for this new version is pending.

OTA request: owner says other phones see277. SSH22 preflight timed out even with
network access allowed (.runtime/ota291-preflight-2026-10-07.log). Owner chose to
have another agent publish without VPN. This agent did not switch VPN or publish.
Prepared releases/childwatch-ota-7.4.291.zip contains both checked signed APKs,
manifest, SHA256SUMS, README-OTA and saved standard publisher. Archive APK bytes
match the staged manifest. Publish APKs first and manifest atomically last;
verify both publicly served hashes. Do not rebuild just to upload prepared291.

Automatic transport recovery requires no server code update (existing ping/pong
was verified live). Family-map battery needs two optional server files, packaged
separately in releases/server-map-battery-2026-10-07.zip. Its README requires
preserving any other agent's changes to location.js, a backup, syntax checks,
normal service restart and authorized family/device checks. No schema changes,
new dependencies, account changes or database replacement are needed.

Issued floor is291. Next automatic candidate is292; OTA publication remains
open until the other agent verifies public manifest and both APK downloads.
APK binaries and local phone logs stay outside Git; source changes are grouped
into versioning, map telemetry/motion, home presentation and connection recovery.

parent SHA256: 45c171dfc842b2af7a6edc16d38d521a87e178853ff36177d66b8fb6e771bb48

child SHA256: a86be324c5eb1c357f0c2bb6b2172e639e3176f6bbdb8dbc0da56007eb9009f0
