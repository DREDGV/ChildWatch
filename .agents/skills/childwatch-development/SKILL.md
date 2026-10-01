---
name: childwatch-development
description: Develop or fix ChildWatch Android and Node family features while preserving selected-person identity, permissions, current work, and concise TODO evidence. Use for ChildWatch product development, not for standalone Codex setup or release operations.
---

# ChildWatch development

1. Read root TODO.md and AGENTS.md once per working stage. Choose one ID and one coherent user-visible result. Current user restrictions override this skill.
2. Read CONTEXT.md when touching family identity. app is ParentMonitor; parentwatch is ChildDevice. Module names do not identify the user's role.
3. Search the relevant module with rg. Read only the task's legacy-index section or evidence when needed; do not repeat the full audit.
4. Trace the touched path from selected member to actual device, request, server permission, response and display. Async responses must remain bound to their original device. Never substitute a different child for an explicit recipient.
5. Preserve canonical family identity and role. Device IDs, preferences and cached names are not proof of role or access. The server must enforce permissions; hiding a card is insufficient.
6. Treat missing, stale, denied and empty data as different states. Display capture time rather than upload time for measured data; do not invent routes, usage totals or connection status.
7. Change a complete useful slice. Reuse design-system and existing layouts; include loading/error/empty/offline states and large text where relevant. Do not create unrelated architecture or a full redesign for a small fix.
8. Inspect the affected diff and validate only the remaining concrete risk using permitted tools. No tests/builds when the user has deferred them. Never present source edits as device proof. Room changes and foreground services require AGENTS rules.
9. Update the task: result, evidence level and one next step. Keep existing unrelated dirty work untouched. Do not commit/push unless requested or covered by existing authorization.
10. End with what changed, verification/limitations, and one recommended next project task (optional one alternative). Use fresh findings and TODO, explain the user-visible result briefly. Record new ideas as proposals pending selection; do not start them solely because you proposed them. Honor a request to pause or stop.

For release work read ../childwatch-release/SKILL.md only when requested. For additional workflow rationale read [agent-workflow](../../../docs/agent-workflow.md).