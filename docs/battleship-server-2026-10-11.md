# Battleship server contract — 2026-10-11

Implemented source; not deployed. Existing `/api/family-games` authorization (JWT device → canonical family member, explicit familyId/actorMemberId) stays unchanged. Every get/list/action/replayed response is projected for the authenticated participant. Third-party family members cannot inspect games.

## Wire

Capabilities protocolVersion remains 1 (additive), games now includes `BATTLESHIP` and `TIC_TAC_TOE`.
Create: `{gameId:UUID,type:"BATTLESHIP",targetMemberId}`. Invitation is `INVITED`; only guest may `ACCEPT`, moving to `SETUP`. Invitation expires after 15 minutes; accepted setup/play expire 24 hours after acceptance.

Every action includes `{actionId:UUID,version:number,action:string}`. Retrying identical actionId/payload returns current participant-filtered state with `replayed:true`, even after newer actions. Mutations are transactional and version checked. After ambiguous transport failure retain actionId; after `GAME_VERSION_CONFLICT` fetch and choose a new actionId for a genuinely new attempt.

- `PLACE_FLEET` adds `ships:[[0,1,2,3],...]`: exactly 10 straight contiguous ships, lengths 4,3,3,2,2,2,1,1,1,1. Row-major cells 0–99. Ships may not touch, even diagonally. Only once per participant during SETUP; both ready starts PLAYING, host first. Canonical stored ship order is descending length then starting cell.
- `FIRE` adds `cell:0..99`. Only current player, only previously unshot cell. HIT/SUNK retains turn; MISS changes turn; all 20 cells hit ends WON.
- `RESIGN` allowed during SETUP or PLAYING. Existing CANCEL/DECLINE invitation rules unchanged.

Common response fields unchanged; Battleship `board:null`, `winningLine:null`, `myMark:X|O`, `nextMark:X|O|null`. `battleship` object:

```
{size:10,myShips:[[...]]|null,opponentShips:[[...]]|null,
 myReady:boolean,opponentReady:boolean,
 myShots:[{cell:number,result:"MISS"|"HIT"|"SUNK"}],
 opponentShots:[{cell:number,result:"MISS"|"HIT"|"SUNK"}],
 mySunkShips:[[...]],opponentSunkShips:[[...]],nextMemberId:string|null}
```

Opponent ships remain null in INVITED/SETUP/PLAYING (including list and retries); only wholly sunk ships disclosed through opponentSunkShips. Terminal states may reveal remaining fleet. Earlier HIT shot entries remain HIT after sinking; use sunk-ship arrays for rendering whole sunk vessels. Errors contain only existing generic codes and never board/ships. Invalid fleets use GAME_INVALID_ACTION. Repeated shots use GAME_CELL_OCCUPIED; wrong turn GAME_NOT_YOUR_TURN.

## Storage and validation

`ensure()` adds `family_games.type TEXT NOT NULL DEFAULT 'TIC_TAC_TOE'` if absent. Existing rows, boards, versions and tables are preserved. Battleship private fleets/shots stored in existing board JSON, never returned raw. Existing action journal/version transaction covers both games. No Android Room changes.

`node --test server/__tests__/battleship.node.cjs server/__tests__/family-games.node.cjs`: 15 PASS, 0 failed, 0 skipped (2026-10-11, 590 ms), `.runtime/battleship-server-tests.log`. Includes fleet validity, hidden views, setup/restart/expiry, idempotent fire, turn/hit/sunk/win, old-schema migration, rollback and concurrency, inherited authorization/revocation and HTTP regressions. Run localhost HTTP tests outside restricted network sandbox; no production endpoint used.

Next verification: Android two-member UI against this server on isolated/test family, then separately approved server deployment and physical-device play. Shipping clients must gate online mode on capabilities; old deployed servers may lack BATTLESHIP.

## Deployment delta (not published)

Runtime files: `server/services/FamilyGameService.js`, `server/services/BattleshipRules.js`, `server/routes/family-games.js`. The existing `server/index.js` must include the `createFamilyGameRoutes` require and authenticated `/api/family-games` mount (source lines29/276). If the deployed baseline lacks those two lines, apply this mount to its actual index without overwriting unrelated server updates. Dependencies are the existing `services/DeviceAccessService.js`, database manager, express and sqlite3; no new npm dependency, Android Room migration or transcription worker.

Order for a separately authorized release: back up these runtime files and SQLite database; stop writes; deploy the delta and enable the mount if absent; `node --check` each changed JS; restart by the server's existing process manager; confirm unauthenticated endpoint401 and authenticated capabilities include BATTLESHIP. Verify existing family data counts and sqlite integrity. Then upgrade clients preserving signatures/data; make an invitation from each app role and test accept/setup/fire/retry on two unlocked phones. Roll back code only with a database backup available: the additive type column is harmless to old code, but an old service must not operate on new Battleship rows. Do not restore an old database over new family data automatically.

No production invitation, server deployment or OTA publication was performed in this stage. A versioned APK/server bundle must be prepared only after the owner selects the release window.
