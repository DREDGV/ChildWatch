# 7.4.289 device update — 07.10.2026

Both connected working phones were updated over 7.4.288, without clearing data:
Samsung R5CX71CKKJW / ru.example.childwatch / 7.4.289;
Moto ZY227XB2QD / ru.example.parentwatch.debug / 7.4.289-debug.
Both installed version codes: 2000000289. Child FamilyAssistantService remains
selected. Family identities survived installation and launch.

The standard build-and-install script ran BuildOnly with timeout900, without
an explicit version override. Packaging reserved one code for both apps; build
passed in 107 seconds. stage-release -UseDebugBuilds wrote releases/7.4.289.
APK output metadata, hashes and signatures were checked before InstallOnly.
Push + pm install -r succeeded (parent5s, child17s installation).

Permanent certificate, equal to staged288:
4ca0ad1687dfff330ef81aedb2f226989f7936820ee4749300358172fef7982d

Parent SHA256: 4f0f4c5e696313c5a0588819d005f167f462ecdcc13de3b755c08ea868969322

Child SHA256: 513d74287a41b3d217893a9f6372869eaa9353f90d6c9ec0a6a5577def45e971

Logs: .runtime/device-update-2026-10-07-{build,stage,install}.log.
Rendered evidence: .runtime/release289-smoke/parent-settled.png, child.png,
layers.png/layers.xml, selected.png/selected.xml, card-closed.xml.

Parent home shows ten-cell green battery, 99%, charging bolt, capture date,
Details action and filled action rows. Child screenshot also reports99%.
Map layers confirm speed and smooth marker motion enabled. Selecting the child
shows the speed label beneath the avatar. Current location accuracy is100m, so
the app correctly reports unknown speed; no actual numeric speed or finite
movement transition was verified. The information card's X hides it.

The crash buffer is not empty: it includes dsh.Injector InputManager failures
from another helper before/during the operation, and older device-system entries.
No ChildWatch package crash was found in the inspected post-install buffer;
both app processes were live. Logs were retained without clearing the buffer.

Battery color boundaries, stale/unknown states, large text, landscape, actual
numeric motion and animation/system switches remain open in TODO. Map charge
currently says no data while home has charge; this discrepancy is recorded as
CW-06/BATTERY-CONSISTENCY07.10 for diagnosis.

Tracked issued floor was advanced to289 after staging, so even a copy without
local APK metadata starts above this issued version. Next candidate is290.
No server/OTA publication or Git commit/push was performed in this stage.
