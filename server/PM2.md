# PM2 setup for ChildWatch server

Use this when deploying the server on a VPS.

The Hoster control panel observed on 2026-07-14 shows the live process as
`node /var/www/childwatch/index.js` and PM2 running under `adminuser`. Run PM2
inspection/restart commands as that same user so they address the existing
process list instead of creating a second PM2 instance under `root`.

## Safe update of an existing VPS

Do not copy the whole local repository when its worktree contains unrelated
unfinished changes. Confirm the actual PM2 working directory first with
`pm2 describe childwatch`, then update only the reviewed runtime files:

- `index.js`
- `auth/AuthManager.js`
- `database/DatabaseManager.js`
- `managers/WebSocketManager.js`
- `middleware/AuthMiddleware.js`
- `middleware/SocketAuthMiddleware.js`
- `ecosystem.config.cjs`

Before replacing them, copy the current versions and `childwatch.db` to a
timestamped backup directory inside the ChildWatch server directory. Do not
replace or delete the live SQLite database. Run `node --check` on the uploaded
JavaScript files before restarting PM2.

## Start

```bash
cd /var/www/childwatch
pm2 start ecosystem.config.cjs --env production
pm2 save
```

## Make it survive reboot

Run this once on the server:

```bash
pm2 startup systemd
```

PM2 prints one command. Run that printed command as root, then:

```bash
pm2 save
```

## Prevent log growth

The server now keeps verbose websocket/audio logs off by default. For debugging only:

```bash
CW_VERBOSE_WS_LOGS=1 CW_VERBOSE_AUDIO_LOGS=1 pm2 start ecosystem.config.cjs --env debug
```

Install PM2 log rotation on the VPS so stdout logs cannot fill the disk again:

```bash
pm2 install pm2-logrotate
pm2 set pm2-logrotate:max_size 10M
pm2 set pm2-logrotate:retain 7
pm2 set pm2-logrotate:compress true
```

## Socket.IO authentication rollout

The default deployment keeps `CW_REQUIRE_WS_AUTH=0`. In this compatibility
mode, updated Android clients send and validate their token while older clients
can still connect during rollout.

After both Android applications have been updated and have re-registered after
the latest server restart:

1. set `CW_REQUIRE_WS_AUTH` to `"1"` in `ecosystem.config.cjs`
2. restart with `pm2 restart ecosystem.config.cjs --env production --update-env`
3. verify chat, listening, remote photo, and location on both real devices

Authentication sessions are persisted as one-way token hashes in
`./data/auth-sessions.json`. Raw bearer and refresh tokens are not written to
disk. The first deployment of this change has no existing session file, so keep
compatibility mode enabled until both devices have connected and registered at
least once. Later normal PM2 restarts retain those sessions.

`CW_DB_PATH` explicitly selects the SQLite database. The production config uses
`./childwatch.db` together with `cwd: __dirname`.

`CW_AUTH_SESSION_PATH` selects the authentication session cache. Keep this file
private and writable by the account running PM2. Losing it is recoverable in
compatibility mode because devices can register again.

## Useful checks

```bash
pm2 list
pm2 logs childwatch --lines 100
ss -ltnp | grep ':3000'
curl -i http://127.0.0.1:3000/api/health
```
