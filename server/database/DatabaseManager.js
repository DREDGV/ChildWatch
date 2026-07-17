const sqlite3 = require("sqlite3").verbose();
const path = require("path");
const fs = require("fs");
const crypto = require("crypto");

const DEFAULT_PARENT_TO_CHILD_FEATURES = Object.freeze([
  "CHAT",
  "LOCATION",
  "LOCATION_HISTORY",
  "AUDIO_LISTENING",
  "REMOTE_PHOTO",
  "APP_USAGE",
  "SEND_ATTENTION_SIGNAL",
]);

const DEFAULT_CHILD_TO_PARENT_FEATURES = Object.freeze([
  "CHAT",
  "LOCATION",
  "SEND_ATTENTION_SIGNAL",
  "RECEIVE_ATTENTION_SIGNAL",
]);

/**
 * Database Manager for ChildWatch Server
 *
 * Manages SQLite database operations for:
 * - Device registration and authentication
 * - Location history
 * - Audio/photo file metadata
 * - Chat messages
 * - Activity logs
 */
class DatabaseManager {
  /**
   * Mark single chat message as read
   */
  async markMessageAsRead(messageId) {
    const numericId = Number.parseInt(messageId, 10);
    const sql = `
            UPDATE chat_messages
            SET is_read = 1,
                read_at = COALESCE(read_at, strftime('%s','now'))
            WHERE client_message_id = ? OR id = ?
        `;
    return this.run(sql, [messageId, Number.isNaN(numericId) ? -1 : numericId]);
  }
  constructor(
    dbPath = process.env.CW_DB_PATH ||
      path.join(__dirname, "..", "childwatch.db")
  ) {
    this.dbPath = dbPath;
    this.db = null;
    this.isInitialized = false;
    this.familyBootstrapQueue = Promise.resolve();
  }

  /**
   * Initialize database connection and create tables
   */
  async initialize() {
    return new Promise((resolve, reject) => {
      this.db = new sqlite3.Database(this.dbPath, (err) => {
        if (err) {
          console.error("Error opening database:", err);
          reject(err);
          return;
        }

        console.log("Connected to SQLite database");
        this.createTables()
          .then(() => {
            this.isInitialized = true;
            resolve();
          })
          .catch(reject);
      });
    });
  }

  /**
   * Create all necessary tables
   */
  async createTables() {
    const tables = [
      // Devices table
      `CREATE TABLE IF NOT EXISTS devices (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT UNIQUE NOT NULL,
                device_name TEXT NOT NULL,
                device_type TEXT NOT NULL,
                app_version TEXT NOT NULL,
                auth_token TEXT,
                refresh_token TEXT,
                token_expires_at INTEGER,
                created_at INTEGER DEFAULT (strftime('%s', 'now')),
                updated_at INTEGER DEFAULT (strftime('%s', 'now')),
                is_active INTEGER DEFAULT 1,
                permissions TEXT DEFAULT 'user'
            )`,

      // Location history
      `CREATE TABLE IF NOT EXISTS locations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                accuracy REAL NOT NULL,
                timestamp INTEGER NOT NULL,
                created_at INTEGER DEFAULT (strftime('%s', 'now')),
                FOREIGN KEY (device_id) REFERENCES devices (device_id)
            )`,

      // Audio files metadata
      `CREATE TABLE IF NOT EXISTS audio_files (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                filename TEXT NOT NULL,
                file_path TEXT NOT NULL,
                file_size INTEGER NOT NULL,
                mime_type TEXT NOT NULL,
                duration INTEGER,
                timestamp INTEGER NOT NULL,
                created_at INTEGER DEFAULT (strftime('%s', 'now')),
                FOREIGN KEY (device_id) REFERENCES devices (device_id)
            )`,

      // Photo files metadata
      `CREATE TABLE IF NOT EXISTS photo_files (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                filename TEXT NOT NULL,
                file_path TEXT NOT NULL,
                file_size INTEGER NOT NULL,
                mime_type TEXT NOT NULL,
                width INTEGER,
                height INTEGER,
                timestamp INTEGER NOT NULL,
                created_at INTEGER DEFAULT (strftime('%s', 'now')),
                FOREIGN KEY (device_id) REFERENCES devices (device_id)
            )`,

      // Chat messages
      `CREATE TABLE IF NOT EXISTS chat_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                sender TEXT NOT NULL CHECK (sender IN ('parent', 'child')),
                sender_device_id TEXT,
                sender_display_name TEXT,
                message TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                is_read INTEGER DEFAULT 0,
                client_message_id TEXT UNIQUE,
                created_at INTEGER DEFAULT (strftime('%s', 'now')),
                FOREIGN KEY (device_id) REFERENCES devices (device_id)
            )`,

      // Device status snapshots (battery, device info, etc.)
      `CREATE TABLE IF NOT EXISTS device_status (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                battery_level INTEGER,
                is_charging INTEGER,
                charging_type TEXT,
                temperature REAL,
                voltage REAL,
                health TEXT,
                manufacturer TEXT,
                model TEXT,
                android_version TEXT,
                sdk_version INTEGER,
                status_json TEXT,
                timestamp INTEGER NOT NULL,
                created_at INTEGER DEFAULT (strftime('%s', 'now')),
                FOREIGN KEY (device_id) REFERENCES devices (device_id)
            )`,

      // Parent-child links groundwork for future multi-parent / family model.
      `CREATE TABLE IF NOT EXISTS device_links (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                parent_device_id TEXT NOT NULL,
                child_device_id TEXT NOT NULL,
                relation_role TEXT DEFAULT 'guardian',
                display_name TEXT,
                parent_display_name TEXT,
                child_display_name TEXT,
                parent_marker_icon_id INTEGER,
                child_marker_icon_id INTEGER,
                created_by TEXT,
                is_active INTEGER DEFAULT 1,
                created_at INTEGER DEFAULT (strftime('%s', 'now')),
                updated_at INTEGER DEFAULT (strftime('%s', 'now')),
                UNIQUE(parent_device_id, child_device_id)
            )`,

      // Family model. Legacy device_links remains the compatibility source
      // while installed clients migrate to member- and device-aware APIs.
      `CREATE TABLE IF NOT EXISTS families (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                is_active INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER DEFAULT (strftime('%s', 'now')),
                updated_at INTEGER DEFAULT (strftime('%s', 'now'))
            )`,

      `CREATE TABLE IF NOT EXISTS family_members (
                id TEXT PRIMARY KEY,
                family_id TEXT NOT NULL,
                display_name TEXT NOT NULL,
                role TEXT NOT NULL CHECK (role IN ('PARENT', 'CHILD', 'GUARDIAN')),
                avatar_key TEXT,
                is_active INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER DEFAULT (strftime('%s', 'now')),
                updated_at INTEGER DEFAULT (strftime('%s', 'now')),
                FOREIGN KEY (family_id) REFERENCES families (id)
            )`,

      `CREATE TABLE IF NOT EXISTS family_devices (
                id TEXT PRIMARY KEY,
                family_id TEXT NOT NULL,
                member_id TEXT NOT NULL,
                device_id TEXT NOT NULL,
                display_name TEXT NOT NULL,
                platform TEXT,
                last_seen_at INTEGER,
                is_active INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER DEFAULT (strftime('%s', 'now')),
                updated_at INTEGER DEFAULT (strftime('%s', 'now')),
                UNIQUE(family_id, device_id),
                FOREIGN KEY (family_id) REFERENCES families (id),
                FOREIGN KEY (member_id) REFERENCES family_members (id)
            )`,

      `CREATE TABLE IF NOT EXISTS family_permissions (
                id TEXT PRIMARY KEY,
                family_id TEXT NOT NULL,
                actor_member_id TEXT NOT NULL,
                target_member_id TEXT NOT NULL,
                feature TEXT NOT NULL,
                allowed INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER DEFAULT (strftime('%s', 'now')),
                updated_at INTEGER DEFAULT (strftime('%s', 'now')),
                UNIQUE(family_id, actor_member_id, target_member_id, feature),
                FOREIGN KEY (family_id) REFERENCES families (id),
                FOREIGN KEY (actor_member_id) REFERENCES family_members (id),
                FOREIGN KEY (target_member_id) REFERENCES family_members (id)
            )`,

      `CREATE TABLE IF NOT EXISTS attention_signals (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                request_id TEXT NOT NULL UNIQUE,
                family_id TEXT,
                requester_member_id TEXT,
                requester_device_id TEXT NOT NULL,
                requester_display_name TEXT,
                target_member_id TEXT,
                target_device_id TEXT NOT NULL,
                tone TEXT NOT NULL,
                duration_ms INTEGER NOT NULL,
                volume_percent INTEGER NOT NULL,
                vibrate INTEGER NOT NULL DEFAULT 1,
                vibration_pattern TEXT NOT NULL,
                status TEXT NOT NULL,
                reason TEXT,
                error_code TEXT,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                delivered_at INTEGER,
                started_at INTEGER,
                completed_at INTEGER,
                stopped_at INTEGER,
                updated_at INTEGER NOT NULL
            )`,

      // Activity logs
      `CREATE TABLE IF NOT EXISTS activity_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                activity_type TEXT NOT NULL,
                activity_data TEXT,
                timestamp INTEGER NOT NULL,
                created_at INTEGER DEFAULT (strftime('%s', 'now')),
                FOREIGN KEY (device_id) REFERENCES devices (device_id)
            )`,

      // Geofences (safe zones)
      `CREATE TABLE IF NOT EXISTS critical_alerts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                event_type TEXT NOT NULL,
                severity TEXT NOT NULL,
                message TEXT NOT NULL,
                metadata TEXT,
                created_at INTEGER DEFAULT (strftime('%s', 'now')),
                delivered INTEGER DEFAULT 0,
                acknowledged INTEGER DEFAULT 0,
                acknowledged_at INTEGER,
                FOREIGN KEY (device_id) REFERENCES devices (device_id)
            )`,
      `CREATE TABLE IF NOT EXISTS geofences (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                name TEXT NOT NULL,
                center_lat REAL NOT NULL,
                center_lng REAL NOT NULL,
                radius REAL NOT NULL,
                is_active INTEGER DEFAULT 1,
                created_at INTEGER DEFAULT (strftime('%s', 'now')),
                FOREIGN KEY (device_id) REFERENCES devices (device_id)
            )`,
    ];

    for (const sql of tables) {
      await this.run(sql);
    }

    await this.addColumnIfNotExists(
      "device_links",
      "parent_display_name",
      "TEXT"
    );
    await this.addColumnIfNotExists(
      "device_links",
      "child_display_name",
      "TEXT"
    );
    await this.addColumnIfNotExists(
      "device_links",
      "parent_marker_icon_id",
      "INTEGER"
    );
    await this.addColumnIfNotExists(
      "device_links",
      "child_marker_icon_id",
      "INTEGER"
    );
    await this.addColumnIfNotExists(
      "chat_messages",
      "client_message_id",
      "TEXT"
    );
    await this.addColumnIfNotExists(
      "chat_messages",
      "delivered",
      "INTEGER DEFAULT 0"
    );
    await this.addColumnIfNotExists(
      "chat_messages",
      "delivered_at",
      "INTEGER"
    );
    await this.addColumnIfNotExists(
      "chat_messages",
      "read_at",
      "INTEGER"
    );
    await this.addColumnIfNotExists(
      "device_links",
      "parent_display_name",
      "TEXT"
    );
    await this.addColumnIfNotExists(
      "device_links",
      "child_display_name",
      "TEXT"
    );
    await this.addColumnIfNotExists(
      "chat_messages",
      "sender_device_id",
      "TEXT"
    );
    await this.addColumnIfNotExists(
      "chat_messages",
      "sender_display_name",
      "TEXT"
    );
    await this.run(
      "CREATE UNIQUE INDEX IF NOT EXISTS idx_chat_client_id ON chat_messages (client_message_id)"
    );

    // Create indexes for better performance
    const indexes = [
      "CREATE INDEX IF NOT EXISTS idx_locations_device_timestamp ON locations (device_id, timestamp)",
      "CREATE INDEX IF NOT EXISTS idx_audio_device_timestamp ON audio_files (device_id, timestamp)",
      "CREATE INDEX IF NOT EXISTS idx_photo_device_timestamp ON photo_files (device_id, timestamp)",
      "CREATE INDEX IF NOT EXISTS idx_chat_device_timestamp ON chat_messages (device_id, timestamp)",
      "CREATE INDEX IF NOT EXISTS idx_device_status_device_timestamp ON device_status (device_id, timestamp)",
      "CREATE INDEX IF NOT EXISTS idx_device_links_parent ON device_links (parent_device_id)",
      "CREATE INDEX IF NOT EXISTS idx_device_links_child ON device_links (child_device_id)",
      "CREATE INDEX IF NOT EXISTS idx_family_members_family ON family_members (family_id)",
      "CREATE INDEX IF NOT EXISTS idx_family_devices_family ON family_devices (family_id)",
      "CREATE INDEX IF NOT EXISTS idx_family_devices_device ON family_devices (device_id)",
      "CREATE INDEX IF NOT EXISTS idx_family_permissions_lookup ON family_permissions (family_id, actor_member_id, target_member_id, feature)",
      "CREATE INDEX IF NOT EXISTS idx_attention_target_created ON attention_signals (target_device_id, created_at DESC)",
      "CREATE INDEX IF NOT EXISTS idx_attention_requester_created ON attention_signals (requester_device_id, created_at DESC)",
      "CREATE INDEX IF NOT EXISTS idx_attention_family_created ON attention_signals (family_id, created_at DESC)",
      "CREATE INDEX IF NOT EXISTS idx_activity_device_timestamp ON activity_logs (device_id, timestamp)",
      "CREATE INDEX IF NOT EXISTS idx_alerts_device_created ON critical_alerts (device_id, created_at)",
      "CREATE INDEX IF NOT EXISTS idx_devices_auth_token ON devices (auth_token)",
    ];

    for (const sql of indexes) {
      await this.run(sql);
    }

    // Run migrations
    await this.runMigrations();

    // Safe to run on every start: deterministic IDs and UPSERTs make this
    // bootstrap idempotent and preserve the legacy device_links table.
    await this.bootstrapFamiliesFromDeviceLinks();

    console.log("✅ Database tables created successfully");
  }

  /**
   * Run database migrations
   */
  async runMigrations() {
    // Check if current_app_name column exists
    const tableInfo = await this.all(`PRAGMA table_info(device_status)`);
    const hasCurrentAppName = tableInfo.some(
      (col) => col.name === "current_app_name"
    );

    if (!hasCurrentAppName) {
      console.log(
        "Running migration: Adding current_app columns to device_status table..."
      );
      await this.run(
        `ALTER TABLE device_status ADD COLUMN current_app_name TEXT`
      );
      await this.run(
        `ALTER TABLE device_status ADD COLUMN current_app_package TEXT`
      );
      console.log("✅ Migration completed: current_app columns added");
    }

    await this.addColumnIfNotExists(
      "chat_messages",
      "sender_device_id",
      "TEXT"
    );
    await this.addColumnIfNotExists(
      "chat_messages",
      "sender_display_name",
      "TEXT"
    );
  }

  createStableFamilyId(parts) {
    const normalized = Array.from(new Set(parts.map((part) => String(part).trim())))
      .filter(Boolean)
      .sort();
    const digest = crypto
      .createHash("sha256")
      .update(normalized.join("\u0000"))
      .digest("hex")
      .slice(0, 24);
    return `family_${digest}`;
  }

  createStableScopedId(prefix, ...parts) {
    const digest = crypto
      .createHash("sha256")
      .update(parts.map((part) => String(part).trim()).join("\u0000"))
      .digest("hex")
      .slice(0, 24);
    return `${prefix}_${digest}`;
  }

  async withTransaction(work) {
    await this.run("BEGIN IMMEDIATE TRANSACTION");
    try {
      const result = await work();
      await this.run("COMMIT");
      return result;
    } catch (error) {
      try {
        await this.run("ROLLBACK");
      } catch (rollbackError) {
        console.error("Family migration rollback failed:", rollbackError);
      }
      throw error;
    }
  }

  buildLegacyLinkComponents(links) {
    const adjacency = new Map();
    const ensureDevice = (deviceId) => {
      if (!adjacency.has(deviceId)) adjacency.set(deviceId, new Set());
    };

    for (const link of links) {
      const parentDeviceId = String(link.parent_device_id || "").trim();
      const childDeviceId = String(link.child_device_id || "").trim();
      if (!parentDeviceId || !childDeviceId) continue;
      ensureDevice(parentDeviceId);
      ensureDevice(childDeviceId);
      adjacency.get(parentDeviceId).add(childDeviceId);
      adjacency.get(childDeviceId).add(parentDeviceId);
    }

    const visited = new Set();
    const components = [];
    for (const deviceId of Array.from(adjacency.keys()).sort()) {
      if (visited.has(deviceId)) continue;
      const stack = [deviceId];
      const devices = [];
      visited.add(deviceId);
      while (stack.length > 0) {
        const current = stack.pop();
        devices.push(current);
        for (const neighbour of adjacency.get(current) || []) {
          if (visited.has(neighbour)) continue;
          visited.add(neighbour);
          stack.push(neighbour);
        }
      }
      const deviceSet = new Set(devices);
      components.push({
        devices: devices.sort(),
        links: links.filter(
          (link) =>
            deviceSet.has(String(link.parent_device_id || "").trim()) &&
            deviceSet.has(String(link.child_device_id || "").trim())
        ),
      });
    }
    return components;
  }

  async resolveFamilyIdForDevices(deviceIds) {
    if (!deviceIds.length) {
      return { familyId: "", supersededFamilyIds: [] };
    }
    const placeholders = deviceIds.map(() => "?").join(",");
    const existing = await this.all(
      `SELECT family_id AS familyId, COUNT(*) AS overlapCount
       FROM family_devices
       WHERE is_active = 1 AND device_id IN (${placeholders})
       GROUP BY family_id
       ORDER BY overlapCount DESC, family_id ASC`,
      deviceIds
    );
    const familyId = existing[0]?.familyId || this.createStableFamilyId(deviceIds);
    return {
      familyId,
      supersededFamilyIds: existing
        .slice(1)
        .map((row) => row.familyId)
        .filter(Boolean),
    };
  }

  resolveLegacyDeviceRole(deviceId, links) {
    const isParent = links.some(
      (link) => String(link.parent_device_id || "").trim() === deviceId
    );
    const isChild = links.some(
      (link) => String(link.child_device_id || "").trim() === deviceId
    );
    if (isParent && isChild) return "GUARDIAN";
    return isParent ? "PARENT" : "CHILD";
  }

  resolveLegacyDisplayName(deviceId, role, links, device) {
    for (const link of links) {
      if (role !== "CHILD" && link.parent_device_id === deviceId) {
        const value = String(link.parent_display_name || "").trim();
        if (value) return value;
      }
      if (role !== "PARENT" && link.child_device_id === deviceId) {
        const value = String(link.child_display_name || link.display_name || "").trim();
        if (value) return value;
      }
    }
    const deviceName = String(device?.device_name || "").trim();
    if (deviceName) return deviceName;
    return role === "CHILD" ? "Ребенок" : "Родитель";
  }

  async upsertFamilyPermission({
    familyId,
    actorMemberId,
    targetMemberId,
    feature,
    allowed = true,
    preserveExisting = false,
  }) {
    const normalizedFeature = String(feature || "").trim().toUpperCase();
    const id = this.createStableScopedId(
      "permission",
      familyId,
      actorMemberId,
      targetMemberId,
      normalizedFeature
    );
    const conflictAction = preserveExisting
      ? "DO NOTHING"
      : "DO UPDATE SET allowed = excluded.allowed, updated_at = strftime('%s', 'now')";
    return this.run(
      `INSERT INTO family_permissions (
         id, family_id, actor_member_id, target_member_id, feature, allowed
       ) VALUES (?, ?, ?, ?, ?, ?)
       ON CONFLICT(family_id, actor_member_id, target_member_id, feature)
       ${conflictAction}`,
      [
        id,
        familyId,
        actorMemberId,
        targetMemberId,
        normalizedFeature,
        allowed ? 1 : 0,
      ]
    );
  }

  bootstrapFamiliesFromDeviceLinks() {
    const execute = () => this.performFamilyBootstrap();
    const queued = this.familyBootstrapQueue.then(execute, execute);
    this.familyBootstrapQueue = queued.catch(() => undefined);
    return queued;
  }

  async performFamilyBootstrap() {
    const links = await this.all(
      `SELECT * FROM device_links
       WHERE is_active = 1
       ORDER BY parent_device_id, child_device_id`
    );
    if (!links.length) return { families: 0, devices: 0 };

    return this.withTransaction(async () => {
      const components = this.buildLegacyLinkComponents(links);
      let deviceCount = 0;

      for (const component of components) {
        const { familyId, supersededFamilyIds } =
          await this.resolveFamilyIdForDevices(component.devices);
        if (supersededFamilyIds.length > 0) {
          const placeholders = supersededFamilyIds.map(() => "?").join(",");
          await this.run(
            `UPDATE families
             SET is_active = 0, updated_at = strftime('%s', 'now')
             WHERE id IN (${placeholders})`,
            supersededFamilyIds
          );
          await this.run(
            `UPDATE family_members
             SET is_active = 0, updated_at = strftime('%s', 'now')
             WHERE family_id IN (${placeholders})`,
            supersededFamilyIds
          );
          await this.run(
            `UPDATE family_devices
             SET is_active = 0, updated_at = strftime('%s', 'now')
             WHERE family_id IN (${placeholders})`,
            supersededFamilyIds
          );
        }
        await this.run(
          `INSERT INTO families (id, name, is_active)
           VALUES (?, ?, 1)
           ON CONFLICT(id) DO UPDATE SET is_active = 1, updated_at = strftime('%s', 'now')`,
          [familyId, "Семья"]
        );

        const memberByDevice = new Map();
        for (const deviceId of component.devices) {
          const role = this.resolveLegacyDeviceRole(deviceId, component.links);
          const device = await this.get(
            "SELECT device_name, device_type FROM devices WHERE device_id = ?",
            [deviceId]
          );
          const displayName = this.resolveLegacyDisplayName(
            deviceId,
            role,
            component.links,
            device
          );
          const memberId = this.createStableScopedId("member", familyId, deviceId);
          const familyDeviceId = this.createStableScopedId(
            "family_device",
            familyId,
            deviceId
          );
          memberByDevice.set(deviceId, memberId);

          await this.run(
            `INSERT INTO family_members (
               id, family_id, display_name, role, is_active
             ) VALUES (?, ?, ?, ?, 1)
             ON CONFLICT(id) DO UPDATE SET
               display_name = excluded.display_name,
               role = excluded.role,
               is_active = 1,
               updated_at = strftime('%s', 'now')`,
            [memberId, familyId, displayName, role]
          );
          await this.run(
            `INSERT INTO family_devices (
               id, family_id, member_id, device_id, display_name, platform, is_active
             ) VALUES (?, ?, ?, ?, ?, ?, 1)
             ON CONFLICT(family_id, device_id) DO UPDATE SET
               member_id = excluded.member_id,
               display_name = excluded.display_name,
               platform = COALESCE(excluded.platform, family_devices.platform),
               is_active = 1,
               updated_at = strftime('%s', 'now')`,
            [
              familyDeviceId,
              familyId,
              memberId,
              deviceId,
              displayName,
              device?.device_type || null,
            ]
          );
          deviceCount += 1;
        }

        for (const link of component.links) {
          const parentMemberId = memberByDevice.get(link.parent_device_id);
          const childMemberId = memberByDevice.get(link.child_device_id);
          if (!parentMemberId || !childMemberId) continue;
          for (const feature of DEFAULT_PARENT_TO_CHILD_FEATURES) {
            await this.upsertFamilyPermission({
              familyId,
              actorMemberId: parentMemberId,
              targetMemberId: childMemberId,
              feature,
              allowed: true,
              preserveExisting: true,
            });
          }
          for (const feature of DEFAULT_CHILD_TO_PARENT_FEATURES) {
            await this.upsertFamilyPermission({
              familyId,
              actorMemberId: childMemberId,
              targetMemberId: parentMemberId,
              feature,
              allowed: true,
              preserveExisting: true,
            });
          }
        }
      }

      return { families: components.length, devices: deviceCount };
    });
  }

  async getFamiliesForDevice(deviceId) {
    return this.all(
      `SELECT DISTINCT
         f.id,
         f.name,
         f.is_active AS isActive,
         f.created_at AS createdAt,
         f.updated_at AS updatedAt
       FROM families f
       JOIN family_devices fd ON fd.family_id = f.id
       WHERE fd.device_id = ?
         AND fd.is_active = 1
         AND f.is_active = 1
       ORDER BY f.created_at, f.id`,
      [deviceId]
    );
  }

  async getFamilyById(familyId) {
    return this.get(
      `SELECT
         id,
         name,
         is_active AS isActive,
         created_at AS createdAt,
         updated_at AS updatedAt
       FROM families
       WHERE id = ? AND is_active = 1`,
      [familyId]
    );
  }

  async getFamilyMembers(familyId) {
    return this.all(
      `SELECT
         id,
         family_id AS familyId,
         display_name AS displayName,
         role,
         avatar_key AS avatarKey,
         is_active AS isActive,
         created_at AS createdAt,
         updated_at AS updatedAt
       FROM family_members
       WHERE family_id = ? AND is_active = 1
       ORDER BY CASE role WHEN 'PARENT' THEN 0 WHEN 'GUARDIAN' THEN 1 ELSE 2 END,
                display_name,
                id`,
      [familyId]
    );
  }

  async getFamilyDevices(familyId) {
    return this.all(
      `SELECT
         fd.id,
         fd.family_id AS familyId,
         fd.member_id AS memberId,
         fd.device_id AS deviceId,
         fd.display_name AS displayName,
         fd.platform,
         fd.last_seen_at AS lastSeenAt,
         fd.is_active AS isActive,
         fd.created_at AS createdAt,
         fd.updated_at AS updatedAt
       FROM family_devices fd
       WHERE fd.family_id = ? AND fd.is_active = 1
       ORDER BY fd.display_name, fd.device_id`,
      [familyId]
    );
  }

  async getFamilyDeviceMembership(familyId, deviceId) {
    return this.get(
      `SELECT
         fd.family_id AS familyId,
         fd.member_id AS memberId,
         fd.device_id AS deviceId,
         fm.role AS memberRole
       FROM family_devices fd
       JOIN family_members fm ON fm.id = fd.member_id
       JOIN families f ON f.id = fd.family_id
       WHERE fd.family_id = ?
         AND fd.device_id = ?
         AND fd.is_active = 1
         AND fm.is_active = 1
         AND f.is_active = 1
       LIMIT 1`,
      [familyId, deviceId]
    );
  }

  async getSharedFamilyMembership(actorDeviceId, targetDeviceId) {
    return this.get(
      `SELECT
         actor.family_id AS familyId,
         actor.member_id AS actorMemberId,
         target.member_id AS targetMemberId,
         actor_member.display_name AS actorDisplayName,
         target_member.display_name AS targetDisplayName
       FROM family_devices actor
       JOIN family_devices target ON target.family_id = actor.family_id
       JOIN families f ON f.id = actor.family_id
       JOIN family_members actor_member ON actor_member.id = actor.member_id
       JOIN family_members target_member ON target_member.id = target.member_id
       WHERE actor.device_id = ?
         AND target.device_id = ?
         AND actor.is_active = 1
         AND target.is_active = 1
         AND actor_member.is_active = 1
         AND target_member.is_active = 1
         AND f.is_active = 1
       ORDER BY actor.family_id
       LIMIT 1`,
      [actorDeviceId, targetDeviceId]
    );
  }

  async getFamilyPermission({
    familyId,
    actorMemberId,
    targetMemberId,
    feature,
  }) {
    return this.get(
      `SELECT allowed
       FROM family_permissions
       WHERE family_id = ?
         AND actor_member_id = ?
         AND target_member_id = ?
         AND feature = ?
       LIMIT 1`,
      [
        familyId,
        actorMemberId,
        targetMemberId,
        String(feature || "").trim().toUpperCase(),
      ]
    );
  }

  async saveAttentionSignalEvent(signal) {
    const now = Date.now();
    return this.run(
      `INSERT INTO attention_signals (
         request_id,
         family_id,
         requester_member_id,
         requester_device_id,
         requester_display_name,
         target_member_id,
         target_device_id,
         tone,
         duration_ms,
         volume_percent,
         vibrate,
         vibration_pattern,
         status,
         reason,
         error_code,
         created_at,
         expires_at,
         updated_at
       ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(request_id) DO NOTHING`,
      [
        signal.requestId,
        signal.familyId || null,
        signal.requesterMemberId || null,
        signal.requesterDeviceId,
        signal.requesterDisplayName || null,
        signal.targetMemberId || null,
        signal.targetDeviceId,
        signal.tone,
        signal.durationMs,
        signal.volumePercent,
        signal.vibrate ? 1 : 0,
        signal.vibrationPattern,
        signal.status,
        signal.reason || null,
        signal.errorCode || null,
        signal.createdAt,
        signal.expiresAt,
        now,
      ]
    );
  }

  async updateAttentionSignalStatus({
    requestId,
    status,
    reason = null,
    errorCode = null,
    timestamp = Date.now(),
  }) {
    const timestampColumn = {
      DELIVERED: "delivered_at",
      STARTED: "started_at",
      COMPLETED: "completed_at",
      STOPPED: "stopped_at",
    }[status];
    const timestampAssignment = timestampColumn
      ? `, ${timestampColumn} = COALESCE(${timestampColumn}, ?)`
      : "";
    const params = [status, reason, errorCode, timestamp];
    if (timestampColumn) params.push(timestamp);
    params.push(requestId);

    return this.run(
      `UPDATE attention_signals
       SET status = ?,
           reason = ?,
           error_code = ?,
           updated_at = ?
           ${timestampAssignment}
       WHERE request_id = ?`,
      params
    );
  }

  async getAttentionSignalByRequestId(requestId) {
    return this.get(
      `SELECT
         request_id AS requestId,
         family_id AS familyId,
         requester_device_id AS requesterDeviceId,
         target_device_id AS targetDeviceId,
         status,
         expires_at AS expiresAt,
         updated_at AS updatedAt
       FROM attention_signals
       WHERE request_id = ?
       LIMIT 1`,
      [requestId]
    );
  }

  async saveCriticalAlert({
    deviceId,
    eventType,
    severity,
    message,
    metadata,
  }) {
    const result = await this.run(
      `
            INSERT INTO critical_alerts (device_id, event_type, severity, message, metadata)
            VALUES (?, ?, ?, ?, ?)
        `,
      [
        deviceId,
        eventType,
        severity,
        message,
        metadata ? JSON.stringify(metadata) : null,
      ]
    );

    return {
      id: result.id,
      delivered: 0,
    };
  }

  async markAlertDelivered(alertId) {
    await this.run(
      `
            UPDATE critical_alerts
            SET delivered = 1
            WHERE id = ?
        `,
      [alertId]
    );
  }

  async getPendingCriticalAlerts(deviceId, limit = 20) {
    const rows = await this.all(
      `
            SELECT id, device_id AS deviceId, event_type AS eventType, severity, message, metadata, created_at AS createdAt
            FROM critical_alerts
            WHERE device_id = ? AND acknowledged = 0
            ORDER BY created_at DESC
            LIMIT ?
        `,
      [deviceId, limit]
    );

    return rows.map((row) => ({
      ...row,
      metadata: row.metadata ? JSON.parse(row.metadata) : null,
    }));
  }

  async acknowledgeCriticalAlerts(deviceId, alertIds) {
    if (!alertIds || alertIds.length === 0) {
      return;
    }

    const placeholders = alertIds.map(() => "?").join(",");
    const params = [deviceId, ...alertIds];

    await this.run(
      `
            UPDATE critical_alerts
            SET acknowledged = 1,
                acknowledged_at = strftime('%s', 'now')
            WHERE device_id = ? AND id IN (${placeholders})
        `,
      params
    );
  }
  /**
   * Execute SQL query
   */
  run(sql, params = []) {
    return new Promise((resolve, reject) => {
      this.db.run(sql, params, function (err) {
        if (err) {
          console.error("❌ Database run error:", err);
          reject(err);
        } else {
          resolve({ id: this.lastID, changes: this.changes });
        }
      });
    });
  }

  /**
   * Get all rows from query
   */
  all(sql, params = []) {
    return new Promise((resolve, reject) => {
      this.db.all(sql, params, (err, rows) => {
        if (err) {
          console.error("❌ Database query error:", err);
          reject(err);
        } else {
          resolve(rows);
        }
      });
    });
  }

  /**
   * Get single row from query
   */
  get(sql, params = []) {
    return new Promise((resolve, reject) => {
      this.db.get(sql, params, (err, row) => {
        if (err) {
          console.error("❌ Database query error:", err);
          reject(err);
        } else {
          resolve(row);
        }
      });
    });
  }

  /**
   * Register or update device
   */
  async registerDevice(deviceId, deviceData) {
    const {
      device_name = "Unknown Device",
      device_type = "android",
      app_version = "1.0.0",
    } = deviceData;

    const sql = `
            INSERT INTO devices (device_id, device_name, device_type, app_version)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(device_id) DO UPDATE SET
                device_name = excluded.device_name,
                device_type = excluded.device_type,
                app_version = excluded.app_version,
                updated_at = strftime('%s', 'now')
        `;

    return this.run(sql, [deviceId, device_name, device_type, app_version]);
  }

  /**
   * Get device by ID
   */
  async getDevice(deviceId) {
    const sql = "SELECT * FROM devices WHERE device_id = ?";
    return this.get(sql, [deviceId]);
  }

  /**
   * Save location
   */
  async saveLocation(deviceId, locationData) {
    const { latitude, longitude, accuracy, timestamp } = locationData;

    const sql = `
            INSERT INTO locations (device_id, latitude, longitude, accuracy, timestamp)
            VALUES (?, ?, ?, ?, ?)
        `;

    return this.run(sql, [deviceId, latitude, longitude, accuracy, timestamp]);
  }

  /**
   * Get location history
   */
  async getLocationHistory(
    deviceId,
    limit = 100,
    offset = 0,
    fromTimestamp = null,
    toTimestamp = null
  ) {
    let sql = `
            SELECT * FROM locations
            WHERE device_id = ?
        `;
    const params = [deviceId];

    if (typeof fromTimestamp === "number" && !Number.isNaN(fromTimestamp)) {
      sql += ` AND timestamp >= ?`;
      params.push(fromTimestamp);
    }

    if (typeof toTimestamp === "number" && !Number.isNaN(toTimestamp)) {
      sql += ` AND timestamp <= ?`;
      params.push(toTimestamp);
    }

    sql += `
            ORDER BY timestamp DESC
            LIMIT ? OFFSET ?
        `;
    params.push(limit, offset);

    return this.all(sql, params);
  }

  /**
   * Get latest location
   */
  async getLatestLocation(deviceId) {
    const sql = `
            SELECT * FROM locations
            WHERE device_id = ?
            ORDER BY timestamp DESC
            LIMIT 1
        `;

    return this.get(sql, [deviceId]);
  }

  /**
   * Save audio file metadata
   */
  async addColumnIfNotExists(tableName, columnName, columnType) {
    const columns = await this.all(`PRAGMA table_info(${tableName})`);
    const exists = Array.isArray(columns) && columns.some((col) => col.name === columnName);
    if (!exists) {
      await this.run(`ALTER TABLE ${tableName} ADD COLUMN ${columnName} ${columnType}`);
      console.log(`Added column ${columnName} to ${tableName}`);
    }
  }

  async saveAudioFile(deviceId, fileData) {
    const { filename, file_path, file_size, mime_type, duration, timestamp } =
      fileData;

    const sql = `
            INSERT INTO audio_files (device_id, filename, file_path, file_size, mime_type, duration, timestamp)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        `;

    return this.run(sql, [
      deviceId,
      filename,
      file_path,
      file_size,
      mime_type,
      duration,
      timestamp,
    ]);
  }

  /**
   * Get audio files
   */
  async getAudioFiles(deviceId, limit = 50) {
    const sql = `
            SELECT * FROM audio_files
            WHERE device_id = ?
            ORDER BY timestamp DESC
            LIMIT ?
        `;

    return this.all(sql, [deviceId, limit]);
  }

  /**
   * Save photo file metadata
   */
  async savePhotoFile(deviceId, fileData) {
    const {
      filename,
      file_path,
      file_size,
      mime_type,
      width,
      height,
      timestamp,
    } = fileData;

    const sql = `
            INSERT INTO photo_files (device_id, filename, file_path, file_size, mime_type, width, height, timestamp)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        `;

    return this.run(sql, [
      deviceId,
      filename,
      file_path,
      file_size,
      mime_type,
      width,
      height,
      timestamp,
    ]);
  }

  /**
   * Get photo files
   */
  async getPhotoFiles(deviceId, limit = 50) {
    const sql = `
            SELECT * FROM photo_files
            WHERE device_id = ?
            ORDER BY timestamp DESC
            LIMIT ?
        `;

    return this.all(sql, [deviceId, limit]);
  }

  /**
   * Save device status snapshot
   */
  async saveDeviceStatus(deviceId, status) {
    const {
      batteryLevel = null,
      isCharging = null,
      chargingType = null,
      temperature = null,
      voltage = null,
      health = null,
      manufacturer = null,
      model = null,
      androidVersion = null,
      sdkVersion = null,
      currentAppName = null,
      currentAppPackage = null,
      timestamp = Date.now(),
      raw = null,
    } = status || {};

    const sql = `
            INSERT INTO device_status (
                device_id,
                battery_level,
                is_charging,
                charging_type,
                temperature,
                voltage,
                health,
                manufacturer,
                model,
                android_version,
                sdk_version,
                current_app_name,
                current_app_package,
                status_json,
                timestamp
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        `;

    const statusJson = raw ? JSON.stringify(raw) : null;

    return this.run(sql, [
      deviceId,
      batteryLevel,
      isCharging === null ? null : isCharging ? 1 : 0,
      chargingType,
      temperature,
      voltage,
      health,
      manufacturer,
      model,
      androidVersion,
      sdkVersion,
      currentAppName,
      currentAppPackage,
      statusJson,
      timestamp,
    ]);
  }

  /**
   * Get latest device status
   */
  async getLatestDeviceStatus(deviceId) {
    const sql = `
            SELECT *
            FROM device_status
            WHERE device_id = ?
            ORDER BY timestamp DESC
            LIMIT 1
        `;

    const row = await this.get(sql, [deviceId]);
    if (!row) {
      return null;
    }

    let raw = null;
    if (row.status_json) {
      try {
        raw = JSON.parse(row.status_json);
      } catch (error) {
        console.warn("Failed to parse device status JSON:", error.message);
      }
    }

    return {
      batteryLevel: row.battery_level,
      isCharging: row.is_charging === 1,
      chargingType: row.charging_type,
      temperature: row.temperature,
      voltage: row.voltage,
      health: row.health,
      manufacturer: row.manufacturer,
      model: row.model,
      androidVersion: row.android_version,
      sdkVersion: row.sdk_version,
      currentAppName: row.current_app_name,
      currentAppPackage: row.current_app_package,
      timestamp: row.timestamp,
      raw,
    };
  }

  /**
   * Get device status history (newest first).
   */
  async getDeviceStatusHistory(deviceId, limit = 60) {
    const sql = `
            SELECT *
            FROM device_status
            WHERE device_id = ?
            ORDER BY timestamp DESC
            LIMIT ?
        `;

    const rows = await this.all(sql, [deviceId, limit]);
    return rows.map((row) => {
      let raw = null;
      if (row.status_json) {
        try {
          raw = JSON.parse(row.status_json);
        } catch (error) {
          console.warn("Failed to parse device status history JSON:", error.message);
        }
      }

      return {
        batteryLevel: row.battery_level,
        isCharging: row.is_charging === 1,
        chargingType: row.charging_type,
        temperature: row.temperature,
        voltage: row.voltage,
        health: row.health,
        manufacturer: row.manufacturer,
        model: row.model,
        androidVersion: row.android_version,
        sdkVersion: row.sdk_version,
        currentAppName: row.current_app_name,
        currentAppPackage: row.current_app_package,
        timestamp: row.timestamp,
        raw,
      };
    });
  }

  async upsertDeviceLink({
    parentDeviceId,
    childDeviceId,
    relationRole = "guardian",
    displayName = null,
    parentDisplayName = null,
    childDisplayName = null,
    parentMarkerIconId = null,
    childMarkerIconId = null,
    createdBy = null,
    isActive = true,
  }) {
    const existingLink = await this.get(
      `SELECT is_active AS isActive
       FROM device_links
       WHERE parent_device_id = ? AND child_device_id = ?
       LIMIT 1`,
      [parentDeviceId, childDeviceId]
    );
    const sql = `
            INSERT INTO device_links (
                parent_device_id,
                child_device_id,
                relation_role,
                display_name,
                parent_display_name,
                child_display_name,
                parent_marker_icon_id,
                child_marker_icon_id,
                created_by,
                is_active
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(parent_device_id, child_device_id) DO UPDATE SET
                relation_role = excluded.relation_role,
                display_name = COALESCE(excluded.display_name, device_links.display_name),
                parent_display_name = COALESCE(excluded.parent_display_name, device_links.parent_display_name),
                child_display_name = COALESCE(excluded.child_display_name, device_links.child_display_name),
                parent_marker_icon_id = COALESCE(excluded.parent_marker_icon_id, device_links.parent_marker_icon_id),
                child_marker_icon_id = COALESCE(excluded.child_marker_icon_id, device_links.child_marker_icon_id),
                created_by = COALESCE(device_links.created_by, excluded.created_by),
                is_active = excluded.is_active,
                updated_at = strftime('%s', 'now')
        `;

    const result = await this.run(sql, [
      parentDeviceId,
      childDeviceId,
      relationRole,
      displayName,
      parentDisplayName,
      childDisplayName,
      parentMarkerIconId,
      childMarkerIconId,
      createdBy,
      isActive ? 1 : 0,
    ]);

    // WebSocket clients may repeat registration frequently. Rebuild the
    // compatibility family only for a new or reactivated legacy relation.
    if (!existingLink || (existingLink.isActive !== 1 && isActive)) {
      await this.bootstrapFamiliesFromDeviceLinks();
    }
    return result;
  }

  async getLinkedChildren(parentDeviceId) {
    const sql = `
            SELECT
                dl.parent_device_id AS parentDeviceId,
                dl.child_device_id AS childDeviceId,
                dl.relation_role AS relationRole,
                COALESCE(dl.child_display_name, dl.display_name) AS displayName,
                dl.parent_display_name AS parentDisplayName,
                dl.child_display_name AS childDisplayName,
                dl.parent_marker_icon_id AS parentMarkerIconId,
                dl.child_marker_icon_id AS childMarkerIconId,
                dl.created_by AS createdBy,
                dl.is_active AS isActive,
                dl.created_at AS createdAt,
                dl.updated_at AS updatedAt,
                d.device_name AS childDeviceName,
                d.device_type AS childDeviceType,
                d.app_version AS childAppVersion
            FROM device_links dl
            LEFT JOIN devices d ON d.device_id = dl.child_device_id
            WHERE dl.parent_device_id = ?
              AND dl.is_active = 1
            ORDER BY dl.updated_at DESC, dl.created_at DESC
        `;

    return this.all(sql, [parentDeviceId]);
  }

  async getLinkedParents(childDeviceId) {
    const sql = `
            SELECT
                dl.parent_device_id AS parentDeviceId,
                dl.child_device_id AS childDeviceId,
                dl.relation_role AS relationRole,
                COALESCE(dl.parent_display_name, dl.display_name) AS displayName,
                dl.parent_display_name AS parentDisplayName,
                dl.child_display_name AS childDisplayName,
                dl.parent_marker_icon_id AS parentMarkerIconId,
                dl.child_marker_icon_id AS childMarkerIconId,
                dl.created_by AS createdBy,
                dl.is_active AS isActive,
                dl.created_at AS createdAt,
                dl.updated_at AS updatedAt,
                d.device_name AS parentDeviceName,
                d.device_type AS parentDeviceType,
                d.app_version AS parentAppVersion
            FROM device_links dl
            LEFT JOIN devices d ON d.device_id = dl.parent_device_id
            WHERE dl.child_device_id = ?
              AND dl.is_active = 1
            ORDER BY dl.updated_at DESC, dl.created_at DESC
        `;

    return this.all(sql, [childDeviceId]);
  }

  async hasActiveDeviceLink(parentDeviceId, childDeviceId) {
    const sql = `
            SELECT 1 AS linked
            FROM device_links
            WHERE parent_device_id = ?
              AND child_device_id = ?
              AND is_active = 1
            LIMIT 1
        `;

    const row = await this.get(sql, [parentDeviceId, childDeviceId]);
    return Boolean(row);
  }

  async deactivateDeviceLink(parentDeviceId, childDeviceId) {
    const sql = `
            UPDATE device_links
            SET is_active = 0,
                updated_at = strftime('%s', 'now')
            WHERE parent_device_id = ?
              AND child_device_id = ?
        `;

    return this.run(sql, [parentDeviceId, childDeviceId]);
  }

  /**
   * Save chat message
   */
  async saveChatMessage(deviceId, messageData) {
    const {
      sender,
      senderDeviceId = null,
      senderDisplayName = null,
      message,
      timestamp,
      id,
      delivered = 0,
      deliveredAt = null,
      readAt = null,
    } = messageData;
    const clientMessageId = id || `${deviceId}_${Date.now()}`;

    const sql = `
            INSERT INTO chat_messages (
                device_id,
                sender,
                sender_device_id,
                sender_display_name,
                message,
                timestamp,
                client_message_id,
                delivered,
                delivered_at,
                read_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(client_message_id) DO UPDATE SET
                sender = excluded.sender,
                sender_device_id = COALESCE(excluded.sender_device_id, chat_messages.sender_device_id),
                sender_display_name = COALESCE(excluded.sender_display_name, chat_messages.sender_display_name),
                message = excluded.message,
                timestamp = excluded.timestamp,
                delivered = excluded.delivered,
                delivered_at = COALESCE(chat_messages.delivered_at, excluded.delivered_at),
                read_at = COALESCE(chat_messages.read_at, excluded.read_at)
        `;

    return this.run(sql, [
      deviceId,
      sender,
      senderDeviceId,
      senderDisplayName,
      message,
      timestamp || Date.now(),
      clientMessageId,
      delivered ? 1 : 0,
      deliveredAt,
      readAt,
    ]);
  }

  async getUndeliveredMessages(deviceId, targetRole, targetParentDeviceId = null) {
    const isParentTarget =
      String(targetRole || "").trim().toLowerCase() === "parent";
    const normalizedTargetParentId = String(targetParentDeviceId || "").trim();
    const sql = isParentTarget
      ? `
            SELECT *, COALESCE(client_message_id, id) AS client_id
            FROM chat_messages
            WHERE device_id = ?
              AND (
                (sender = ? AND delivered = 0)
                OR (
                  sender = ?
                  AND (
                    sender_device_id IS NULL
                    OR sender_device_id = ''
                    OR sender_device_id <> ?
                  )
                )
              )
            ORDER BY timestamp ASC
            LIMIT 200
        `
      : `
            SELECT *, COALESCE(client_message_id, id) AS client_id
            FROM chat_messages
            WHERE device_id = ?
              AND sender <> ?
              AND delivered = 0
            ORDER BY timestamp ASC
            LIMIT 200
        `;

    return isParentTarget
      ? this.all(sql, [deviceId, "child", "parent", normalizedTargetParentId])
      : this.all(sql, [deviceId, "child"]);
  }

  async markMessagesDeliveredByClientIds(clientIds = []) {
    if (!clientIds.length) {
      return { changes: 0 };
    }

    const placeholders = clientIds.map(() => "?").join(",");
    const sql = `
            UPDATE chat_messages
            SET delivered = 1,
                delivered_at = COALESCE(delivered_at, strftime('%s','now'))
            WHERE client_message_id IN (${placeholders})
        `;

    return this.run(sql, clientIds);
  }

  async markMessageDelivered(clientMessageId) {
    if (!clientMessageId) {
      return { changes: 0 };
    }

    const numericId = Number.parseInt(clientMessageId, 10);
    const sql = `
            UPDATE chat_messages
            SET delivered = 1,
                delivered_at = COALESCE(delivered_at, strftime('%s','now'))
            WHERE client_message_id = ? OR id = ?
        `;

    return this.run(sql, [
      clientMessageId,
      Number.isNaN(numericId) ? -1 : numericId,
    ]);
  }

  async markMessageAsReadByClientId(clientMessageId) {
    if (!clientMessageId) {
      return { changes: 0 };
    }

    const numericId = Number.parseInt(clientMessageId, 10);
    const sql = `
            UPDATE chat_messages
            SET is_read = 1,
                read_at = COALESCE(read_at, strftime('%s','now'))
            WHERE client_message_id = ? OR id = ?
        `;

    return this.run(sql, [
      clientMessageId,
      Number.isNaN(numericId) ? -1 : numericId,
    ]);
  }

  /**
   * Get chat messages
   */
  async getChatMessages(deviceId, limit = 100, offset = 0) {
    const sql = `
            SELECT *, COALESCE(client_message_id, id) AS client_id
            FROM chat_messages
            WHERE device_id = ?
            ORDER BY timestamp ASC
            LIMIT ? OFFSET ?
        `;

    return this.all(sql, [deviceId, limit, offset]);
  }

  async getChatMessagesSince(deviceId, sinceTimestamp = 0, limit = 100) {
    const sql = `
            SELECT *, COALESCE(client_message_id, id) AS client_id
            FROM chat_messages
            WHERE device_id = ?
              AND timestamp > ?
            ORDER BY timestamp ASC
            LIMIT ?
        `;

    return this.all(sql, [deviceId, sinceTimestamp, limit]);
  }

  async getLatestParentLocation(parentId) {
    const sql = `
            SELECT *
            FROM parent_locations
            WHERE parent_id = ?
            ORDER BY timestamp DESC
            LIMIT 1
        `;

    return this.get(sql, [parentId]);
  }

  /**
   * Mark chat messages as read
   */
  async markMessagesAsRead(deviceId) {
    const sql = `
            UPDATE chat_messages
            SET is_read = 1,
                read_at = COALESCE(read_at, strftime('%s','now'))
            WHERE device_id = ? AND is_read = 0
        `;

    return this.run(sql, [deviceId]);
  }

  /**
   * Save activity log
   */
  async saveActivityLog(deviceId, activityData) {
    const { activity_type, activity_data, timestamp } = activityData;

    const sql = `
            INSERT INTO activity_logs (device_id, activity_type, activity_data, timestamp)
            VALUES (?, ?, ?, ?)
        `;

    const dataJson =
      typeof activity_data === "object"
        ? JSON.stringify(activity_data)
        : activity_data;
    return this.run(sql, [deviceId, activity_type, dataJson, timestamp]);
  }

  /**
   * Get activity logs
   */
  async getActivityLogs(deviceId, limit = 100) {
    const sql = `
            SELECT * FROM activity_logs
            WHERE device_id = ?
            ORDER BY timestamp DESC
            LIMIT ?
        `;

    return this.all(sql, [deviceId, limit]);
  }

  /**
   * Log activity (alias for saveActivityLog for compatibility)
   */
  async logActivity(deviceId, activityData) {
    return this.saveActivityLog(deviceId, activityData);
  }

  /**
   * Close database connection
   */
  close() {
    return new Promise((resolve, reject) => {
      if (this.db) {
        this.db.close((err) => {
          if (err) {
            console.error("❌ Error closing database:", err);
            reject(err);
          } else {
            console.log("✅ Database connection closed");
            this.isInitialized = false;
            resolve();
          }
        });
      } else {
        resolve();
      }
    });
  }
}

module.exports = DatabaseManager;
