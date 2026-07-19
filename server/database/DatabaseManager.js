const sqlite3 = require("sqlite3").verbose();
const path = require("path");
const fs = require("fs");
const crypto = require("crypto");
const { AsyncLocalStorage } = require("async_hooks");

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

// Legacy installations can retain thousands of provisional device identities
// after app reinstalls. They remain available for historical reconciliation,
// but only recently registered (or explicitly bound) people participate in
// the live family chat.
const CHAT_LEGACY_MEMBER_ACTIVE_WINDOW_SECONDS = 30 * 24 * 60 * 60;
const MAX_SAFE_LEGACY_RECEIPTS_PER_MESSAGE = 50;

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
    this.databaseQueue = Promise.resolve();
    this.transactionContext = new AsyncLocalStorage();
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
        this.configureConnection()
          .then(() => this.createTables())
          .then(() => {
            this.isInitialized = true;
            resolve();
          })
          .catch(reject);
      });
    });
  }

  async configureConnection() {
    await this.run("PRAGMA foreign_keys = ON");
    const foreignKeys = await this.get("PRAGMA foreign_keys");
    if (Number(foreignKeys?.foreign_keys) !== 1) {
      throw new Error("SQLite foreign key enforcement could not be enabled");
    }
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
                member_binding_source TEXT NOT NULL DEFAULT 'LEGACY_BOOTSTRAP',
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

      // Additive chat v2 model. The legacy chat_messages table intentionally
      // remains untouched so older installed clients can keep working during
      // the staged protocol rollout.
      `CREATE TABLE IF NOT EXISTS chat_conversations (
                id TEXT PRIMARY KEY,
                family_id TEXT,
                type TEXT NOT NULL CHECK (type IN ('FAMILY', 'DIRECT', 'LEGACY')),
                title TEXT,
                direct_pair_key TEXT,
                created_by_member_id TEXT,
                next_sequence INTEGER NOT NULL DEFAULT 0,
                is_active INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY (family_id) REFERENCES families (id),
                FOREIGN KEY (created_by_member_id) REFERENCES family_members (id)
            )`,

      `CREATE TABLE IF NOT EXISTS chat_conversation_members (
                conversation_id TEXT NOT NULL,
                member_id TEXT NOT NULL,
                is_active INTEGER NOT NULL DEFAULT 1,
                joined_at INTEGER NOT NULL,
                left_at INTEGER,
                muted_until INTEGER,
                last_delivered_sequence INTEGER NOT NULL DEFAULT 0,
                last_read_sequence INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (conversation_id, member_id),
                FOREIGN KEY (conversation_id) REFERENCES chat_conversations (id),
                FOREIGN KEY (member_id) REFERENCES family_members (id)
            )`,

      `CREATE TABLE IF NOT EXISTS chat_messages_v2 (
                id TEXT PRIMARY KEY,
                conversation_id TEXT NOT NULL,
                sequence INTEGER NOT NULL,
                sender_member_id TEXT,
                sender_device_id TEXT,
                sender_role_snapshot TEXT,
                sender_display_name_snapshot TEXT NOT NULL,
                client_message_id TEXT NOT NULL,
                text TEXT NOT NULL,
                client_sent_at INTEGER,
                server_created_at INTEGER NOT NULL,
                legacy_message_id INTEGER UNIQUE,
                legacy_delivered INTEGER,
                legacy_read INTEGER,
                created_at INTEGER NOT NULL,
                UNIQUE (conversation_id, sequence),
                UNIQUE (sender_member_id, client_message_id),
                FOREIGN KEY (conversation_id) REFERENCES chat_conversations (id),
                FOREIGN KEY (sender_member_id) REFERENCES family_members (id)
            )`,

      `CREATE TABLE IF NOT EXISTS chat_message_receipts (
                message_id TEXT NOT NULL,
                recipient_member_id TEXT NOT NULL,
                delivered_at INTEGER,
                read_at INTEGER,
                delivered_by_device_id TEXT,
                read_by_device_id TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (message_id, recipient_member_id),
                FOREIGN KEY (message_id) REFERENCES chat_messages_v2 (id),
                FOREIGN KEY (recipient_member_id) REFERENCES family_members (id)
            )`,

      `CREATE TABLE IF NOT EXISTS chat_legacy_threads (
                legacy_device_id TEXT PRIMARY KEY,
                conversation_id TEXT NOT NULL,
                family_id TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY (conversation_id) REFERENCES chat_conversations (id),
                FOREIGN KEY (family_id) REFERENCES families (id)
            )`,

      `CREATE TABLE IF NOT EXISTS schema_migrations (
                name TEXT PRIMARY KEY,
                applied_at INTEGER NOT NULL,
                last_run_at INTEGER NOT NULL,
                details_json TEXT
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
    await this.addColumnIfNotExists(
      "family_devices",
      "member_binding_source",
      "TEXT NOT NULL DEFAULT 'LEGACY_BOOTSTRAP'"
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
      "CREATE UNIQUE INDEX IF NOT EXISTS idx_chat_family_conversation ON chat_conversations (family_id) WHERE type = 'FAMILY' AND is_active = 1",
      "CREATE UNIQUE INDEX IF NOT EXISTS idx_chat_direct_conversation ON chat_conversations (family_id, direct_pair_key) WHERE type = 'DIRECT' AND is_active = 1",
      "CREATE INDEX IF NOT EXISTS idx_chat_conversation_members_member ON chat_conversation_members (member_id, is_active, conversation_id)",
      "CREATE INDEX IF NOT EXISTS idx_chat_messages_v2_page ON chat_messages_v2 (conversation_id, sequence DESC)",
      "CREATE INDEX IF NOT EXISTS idx_chat_messages_v2_sender_client ON chat_messages_v2 (sender_member_id, client_message_id)",
      "CREATE INDEX IF NOT EXISTS idx_chat_receipts_recipient ON chat_message_receipts (recipient_member_id, read_at, delivered_at)",
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

    // Safe to run repeatedly: deterministic identifiers and UNIQUE constraints
    // import only legacy rows that are not present in chat_messages_v2 yet.
    await this.migrateLegacyChatToConversations();

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
    if (typeof work !== "function") {
      throw new Error("Transaction work must be a function");
    }
    if (this.isInsideTransaction()) {
      return work();
    }

    const execute = async () => {
      const context = { manager: this, active: true };
      await this.rawRun("BEGIN IMMEDIATE TRANSACTION");
      try {
        const result = await this.transactionContext.run(context, work);
        context.active = false;
        await this.rawRun("COMMIT");
        return result;
      } catch (error) {
        context.active = false;
        try {
          await this.rawRun("ROLLBACK");
        } catch (rollbackError) {
          console.error("Database transaction rollback failed:", rollbackError);
        }
        throw error;
      }
    };

    return this.enqueueDatabaseOperation(execute);
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

  async syncFamilyMemberDisplayName(deviceId, displayName) {
    const normalizedDeviceId = String(deviceId || "").trim();
    const normalizedDisplayName = String(displayName || "").trim().slice(0, 100);
    if (!normalizedDeviceId || !normalizedDisplayName) {
      return { memberChanges: 0, deviceChanges: 0 };
    }

    const memberResult = await this.run(
      `UPDATE family_members
       SET display_name = ?, updated_at = strftime('%s', 'now')
       WHERE display_name <> ?
         AND id IN (
           SELECT member_id
           FROM family_devices
           WHERE device_id = ? AND is_active = 1
         )`,
      [normalizedDisplayName, normalizedDisplayName, normalizedDeviceId]
    );
    const deviceResult = await this.run(
      `UPDATE family_devices
       SET display_name = ?, updated_at = strftime('%s', 'now')
       WHERE device_id = ?
         AND is_active = 1
         AND display_name <> ?`,
      [normalizedDisplayName, normalizedDeviceId, normalizedDisplayName]
    );
    const messageResult = await this.run(
      `UPDATE chat_messages_v2
       SET sender_display_name_snapshot = ?
       WHERE sender_display_name_snapshot <> ?
         AND sender_member_id IN (
           SELECT member_id
           FROM family_devices
           WHERE device_id = ? AND is_active = 1
         )`,
      [normalizedDisplayName, normalizedDisplayName, normalizedDeviceId]
    );

    return {
      memberChanges: memberResult?.changes || 0,
      deviceChanges: deviceResult?.changes || 0,
      messageChanges: messageResult?.changes || 0,
    };
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

  async getLegacyFamilyProjectionState() {
    const state = await this.get(
      `SELECT
         (SELECT COUNT(*)
          FROM device_links
          WHERE is_active = 1) AS activeLinks,
         (SELECT COUNT(*)
          FROM device_links dl
          WHERE dl.is_active = 1
            AND NOT EXISTS (
              SELECT 1
              FROM family_devices parent_fd
              JOIN family_devices child_fd
                ON child_fd.family_id = parent_fd.family_id
               AND child_fd.device_id = dl.child_device_id
               AND child_fd.is_active = 1
              JOIN family_members parent_member
                ON parent_member.id = parent_fd.member_id
               AND parent_member.family_id = parent_fd.family_id
               AND parent_member.is_active = 1
              JOIN family_members child_member
                ON child_member.id = child_fd.member_id
               AND child_member.family_id = child_fd.family_id
               AND child_member.is_active = 1
              JOIN families family
                ON family.id = parent_fd.family_id
               AND family.is_active = 1
              WHERE parent_fd.device_id = dl.parent_device_id
                AND parent_fd.is_active = 1
              LIMIT 1
            )) AS missingLinks`
    );
    const activeLinks = Number(state?.activeLinks) || 0;
    const missingLinks = Number(state?.missingLinks) || 0;
    return {
      activeLinks,
      missingLinks,
      current: activeLinks === 0 || missingLinks === 0,
    };
  }

  bootstrapFamiliesFromDeviceLinks({ force = false } = {}) {
    const execute = async () => {
      if (!force) {
        const projection = await this.getLegacyFamilyProjectionState();
        if (projection.current) {
          if (projection.activeLinks > 0) {
            console.log(
              `[family] Existing projection covers ${projection.activeLinks} active device link(s); full bootstrap skipped`
            );
          }
          return {
            families: 0,
            devices: 0,
            skipped: true,
            activeLinks: projection.activeLinks,
          };
        }
        console.log(
          `[family] Rebuilding projection for ${projection.activeLinks} active link(s); ${projection.missingLinks} link(s) are not represented`
        );
      }
      return this.performFamilyBootstrap();
    };
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
          const explicitBinding = await this.get(
            `SELECT fd.member_id AS memberId
             FROM family_devices fd
             JOIN family_members fm
               ON fm.id = fd.member_id AND fm.family_id = fd.family_id
             WHERE fd.family_id = ?
               AND fd.device_id = ?
               AND fd.member_binding_source = 'EXPLICIT'
               AND fm.is_active = 1
             LIMIT 1`,
            [familyId, deviceId]
          );
          const memberId =
            explicitBinding?.memberId ||
            this.createStableScopedId("member", familyId, deviceId);
          const familyDeviceId = this.createStableScopedId(
            "family_device",
            familyId,
            deviceId
          );
          memberByDevice.set(deviceId, memberId);

          if (!explicitBinding) {
            // Legacy evidence proves a device identity, not that two devices
            // represent the same person. Keep one provisional member per
            // device until attachFamilyDeviceToMember is called explicitly.
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
          }
          await this.run(
            `INSERT INTO family_devices (
               id, family_id, member_id, device_id, display_name, platform,
               member_binding_source, is_active
             ) VALUES (?, ?, ?, ?, ?, ?, 'LEGACY_BOOTSTRAP', 1)
             ON CONFLICT(family_id, device_id) DO UPDATE SET
               member_id = CASE
                 WHEN family_devices.member_binding_source = 'EXPLICIT'
                   THEN family_devices.member_id
                 ELSE excluded.member_id
               END,
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

  async getChatFamilyMembers(familyId) {
    return this.all(
      `SELECT
         fm.id,
         fm.family_id AS familyId,
         fm.display_name AS displayName,
         fm.role,
         fm.avatar_key AS avatarKey,
         fm.is_active AS isActive,
         fm.created_at AS createdAt,
         fm.updated_at AS updatedAt
       FROM family_members fm
       WHERE fm.family_id = ?
         AND fm.is_active = 1
         AND EXISTS (
           SELECT 1
           FROM family_devices fd
           LEFT JOIN devices d ON d.device_id = fd.device_id
           WHERE fd.family_id = fm.family_id
             AND fd.member_id = fm.id
             AND fd.is_active = 1
             AND (
               fd.member_binding_source = 'EXPLICIT'
               OR COALESCE(d.updated_at, d.created_at, 0) >=
                  strftime('%s', 'now') - ?
             )
         )
       ORDER BY CASE fm.role WHEN 'PARENT' THEN 0 WHEN 'GUARDIAN' THEN 1 ELSE 2 END,
                fm.display_name,
                fm.id`,
      [familyId, CHAT_LEGACY_MEMBER_ACTIVE_WINDOW_SECONDS]
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
         fd.member_binding_source AS memberBindingSource,
         fd.is_active AS isActive,
         fd.created_at AS createdAt,
         fd.updated_at AS updatedAt
       FROM family_devices fd
       WHERE fd.family_id = ? AND fd.is_active = 1
       ORDER BY fd.display_name, fd.device_id`,
      [familyId]
    );
  }

  async attachFamilyDeviceToMember({
    familyId,
    memberId,
    deviceId,
    displayName = null,
    platform = null,
    lastSeenAt = null,
  }) {
    const normalizedFamilyId = String(familyId || "").trim();
    const normalizedMemberId = String(memberId || "").trim();
    const normalizedDeviceId = String(deviceId || "").trim();
    if (!normalizedFamilyId || !normalizedMemberId || !normalizedDeviceId) {
      throw new Error("Family, member and device ids are required");
    }

    return this.withTransaction(async () => {
      const verified = await this.get(
        `SELECT
           fm.display_name AS memberDisplayName,
           d.device_name AS deviceName,
           d.device_type AS deviceType
         FROM families f
         JOIN family_members fm
           ON fm.family_id = f.id AND fm.id = ? AND fm.is_active = 1
         JOIN devices d ON d.device_id = ? AND d.is_active = 1
         WHERE f.id = ? AND f.is_active = 1
         LIMIT 1`,
        [normalizedMemberId, normalizedDeviceId, normalizedFamilyId]
      );
      if (!verified) {
        throw new Error("Active family member and registered device are required");
      }

      const existing = await this.get(
        `SELECT
           member_id AS memberId,
           member_binding_source AS memberBindingSource
         FROM family_devices
         WHERE family_id = ? AND device_id = ?
         LIMIT 1`,
        [normalizedFamilyId, normalizedDeviceId]
      );
      if (
        existing?.memberBindingSource === "EXPLICIT" &&
        existing.memberId !== normalizedMemberId
      ) {
        throw new Error("Device is explicitly attached to another family member");
      }

      const now = Date.now();
      const normalizedDisplayName =
        String(displayName || "").trim().slice(0, 100) ||
        verified.deviceName ||
        verified.memberDisplayName;
      const normalizedPlatform =
        String(platform || "").trim().slice(0, 50) ||
        verified.deviceType ||
        null;
      const normalizedLastSeenAt = Number.isFinite(Number(lastSeenAt))
        ? this.normalizeEpochMilliseconds(lastSeenAt, now)
        : null;
      const familyDeviceId = this.createStableScopedId(
        "family_device",
        normalizedFamilyId,
        normalizedDeviceId
      );

      await this.run(
        `INSERT INTO family_devices (
           id,
           family_id,
           member_id,
           device_id,
           display_name,
           platform,
           last_seen_at,
           member_binding_source,
           is_active,
           created_at,
           updated_at
         ) VALUES (?, ?, ?, ?, ?, ?, ?, 'EXPLICIT', 1, ?, ?)
         ON CONFLICT(family_id, device_id) DO UPDATE SET
           member_id = excluded.member_id,
           display_name = excluded.display_name,
           platform = COALESCE(excluded.platform, family_devices.platform),
           last_seen_at = COALESCE(excluded.last_seen_at, family_devices.last_seen_at),
           member_binding_source = 'EXPLICIT',
           is_active = 1,
           updated_at = excluded.updated_at`,
        [
          familyDeviceId,
          normalizedFamilyId,
          normalizedMemberId,
          normalizedDeviceId,
          normalizedDisplayName,
          normalizedPlatform,
          normalizedLastSeenAt,
          now,
          now,
        ]
      );

      const provisionalMemberId = this.createStableScopedId(
        "member",
        normalizedFamilyId,
        normalizedDeviceId
      );
      if (
        existing?.memberId &&
        existing.memberId !== normalizedMemberId &&
        existing.memberId === provisionalMemberId
      ) {
        const remainingDevices = await this.get(
          `SELECT COUNT(*) AS count
           FROM family_devices
           WHERE family_id = ? AND member_id = ? AND is_active = 1`,
          [normalizedFamilyId, existing.memberId]
        );
        if (Number(remainingDevices?.count) === 0) {
          await this.run(
            `UPDATE family_members
             SET is_active = 0, updated_at = ?
             WHERE id = ? AND family_id = ?`,
            [now, existing.memberId, normalizedFamilyId]
          );
          await this.run(
            `UPDATE chat_conversation_members
             SET is_active = 0,
                 left_at = COALESCE(left_at, ?),
                 updated_at = ?
             WHERE member_id = ? AND is_active = 1`,
            [now, now, existing.memberId]
          );
        }
      }

      await this.ensureFamilyConversationRecord(normalizedFamilyId, { now });
      return this.getFamilyDeviceMembership(
        normalizedFamilyId,
        normalizedDeviceId
      );
    });
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

  createFamilyConversationId(familyId) {
    const normalizedFamilyId = String(familyId || "").trim();
    if (!normalizedFamilyId) {
      throw new Error("Family id is required");
    }
    return this.createStableScopedId(
      "chat_conversation",
      normalizedFamilyId,
      "FAMILY"
    );
  }

  createDirectConversationKey(memberIds) {
    const normalized = Array.from(
      new Set((memberIds || []).map((id) => String(id || "").trim()))
    )
      .filter(Boolean)
      .sort();
    if (normalized.length !== 2) {
      throw new Error("A direct conversation requires exactly two members");
    }
    return normalized.join("|");
  }

  createDirectConversationId(familyId, memberIds) {
    const pairKey = this.createDirectConversationKey(memberIds);
    return this.createStableScopedId(
      "chat_conversation",
      String(familyId || "").trim(),
      "DIRECT",
      pairKey
    );
  }

  normalizeEpochMilliseconds(value, fallback = Date.now()) {
    const parsed = Number(value);
    if (!Number.isFinite(parsed) || parsed <= 0) return fallback;
    // Legacy SQLite status columns use Unix seconds while Android chat
    // timestamps use milliseconds.
    return parsed < 10_000_000_000 ? Math.trunc(parsed * 1000) : Math.trunc(parsed);
  }

  normalizeChatText(text) {
    if (typeof text !== "string") {
      throw new Error("Chat message text must be a string");
    }
    if (!text.trim()) {
      throw new Error("Chat message text cannot be empty");
    }
    // A byte limit preserves complete Unicode/ZWJ emoji sequences; unlike
    // String.slice(), it never truncates in the middle of a surrogate pair.
    if (Buffer.byteLength(text, "utf8") > 16 * 1024) {
      throw new Error("Chat message text exceeds 16 KiB");
    }
    return text;
  }

  async ensureConversationMemberRecord(
    conversationId,
    memberId,
    now = Date.now()
  ) {
    return this.withTransaction(async () => {
      const validMembership = await this.get(
        `SELECT 1 AS valid
         FROM chat_conversations c
         JOIN families f ON f.id = c.family_id AND f.is_active = 1
         JOIN family_members fm
           ON fm.id = ? AND fm.family_id = c.family_id AND fm.is_active = 1
         WHERE c.id = ?
           AND c.is_active = 1
           AND c.type IN ('FAMILY', 'DIRECT')
         LIMIT 1`,
        [memberId, conversationId]
      );
      if (!validMembership) {
        throw new Error("Conversation member must belong to the active family");
      }
      return this.run(
        `INSERT INTO chat_conversation_members (
           conversation_id,
           member_id,
           is_active,
           joined_at,
           created_at,
           updated_at
         ) VALUES (?, ?, 1, ?, ?, ?)
         ON CONFLICT(conversation_id, member_id) DO UPDATE SET
           is_active = 1,
           left_at = NULL,
           updated_at = excluded.updated_at
         WHERE chat_conversation_members.is_active <> 1
            OR chat_conversation_members.left_at IS NOT NULL`,
        [conversationId, memberId, now, now, now]
      );
    });
  }

  async ensureFamilyConversationRecord(
    familyId,
    { title = "Семья", now = Date.now() } = {}
  ) {
    const normalizedFamilyId = String(familyId || "").trim();
    if (!normalizedFamilyId) {
      throw new Error("Family id is required");
    }
    const family = await this.get(
      `SELECT 1 AS active
       FROM families
       WHERE id = ? AND is_active = 1
       LIMIT 1`,
      [normalizedFamilyId]
    );
    if (!family) {
      throw new Error("Active family not found");
    }
    const conversationId = this.createFamilyConversationId(normalizedFamilyId);
    await this.run(
      `INSERT INTO chat_conversations (
         id,
         family_id,
         type,
         title,
         next_sequence,
         is_active,
         created_at,
         updated_at
       ) VALUES (?, ?, 'FAMILY', ?, 0, 1, ?, ?)
       ON CONFLICT(id) DO UPDATE SET
         family_id = excluded.family_id,
         type = 'FAMILY',
         title = COALESCE(chat_conversations.title, excluded.title),
         is_active = 1,
         updated_at = excluded.updated_at
       WHERE chat_conversations.family_id IS NOT excluded.family_id
          OR chat_conversations.type <> 'FAMILY'
          OR chat_conversations.is_active <> 1
          OR (chat_conversations.title IS NULL AND excluded.title IS NOT NULL)`,
      [conversationId, normalizedFamilyId, title, now, now]
    );

    await this.run(
      `UPDATE chat_conversation_members
       SET is_active = 0,
           left_at = COALESCE(left_at, ?),
           updated_at = ?
       WHERE conversation_id = ?
         AND is_active = 1
         AND member_id NOT IN (
           SELECT fm.id
           FROM family_members fm
           WHERE fm.family_id = ?
             AND fm.is_active = 1
             AND EXISTS (
               SELECT 1
               FROM family_devices fd
               LEFT JOIN devices d ON d.device_id = fd.device_id
               WHERE fd.family_id = fm.family_id
                 AND fd.member_id = fm.id
                 AND fd.is_active = 1
                 AND (
                   fd.member_binding_source = 'EXPLICIT'
                   OR COALESCE(d.updated_at, d.created_at, 0) >=
                      strftime('%s', 'now') - ?
                 )
             )
         )`,
      [
        now,
        now,
        conversationId,
        normalizedFamilyId,
        CHAT_LEGACY_MEMBER_ACTIVE_WINDOW_SECONDS,
      ]
    );

    // Insert the complete family membership in one SQLite statement. The old
    // per-member loop performed thousands of validation and INSERT queries on
    // long-lived installations with many stale legacy device links, delaying
    // server readiness for minutes after a restart.
    await this.run(
      `INSERT INTO chat_conversation_members (
         conversation_id,
         member_id,
         is_active,
         joined_at,
         created_at,
         updated_at
       )
       SELECT ?, fm.id, 1, ?, ?, ?
       FROM family_members fm
       WHERE fm.family_id = ?
         AND fm.is_active = 1
         AND EXISTS (
           SELECT 1
           FROM family_devices fd
           LEFT JOIN devices d ON d.device_id = fd.device_id
           WHERE fd.family_id = fm.family_id
             AND fd.member_id = fm.id
             AND fd.is_active = 1
             AND (
               fd.member_binding_source = 'EXPLICIT'
               OR COALESCE(d.updated_at, d.created_at, 0) >=
                  strftime('%s', 'now') - ?
             )
         )
       ON CONFLICT(conversation_id, member_id) DO UPDATE SET
         is_active = 1,
         left_at = NULL,
         updated_at = excluded.updated_at
       WHERE chat_conversation_members.is_active <> 1
          OR chat_conversation_members.left_at IS NOT NULL`,
      [
        conversationId,
        now,
        now,
        now,
        normalizedFamilyId,
        CHAT_LEGACY_MEMBER_ACTIVE_WINDOW_SECONDS,
      ]
    );
    return this.getChatConversationById(conversationId);
  }

  async ensureFamilyConversation(familyId, options = {}) {
    return this.withTransaction(() =>
      this.ensureFamilyConversationRecord(familyId, options)
    );
  }

  async createDirectConversation({
    familyId,
    memberIds,
    createdByMemberId = null,
    title = null,
  }) {
    const normalizedFamilyId = String(familyId || "").trim();
    const pairKey = this.createDirectConversationKey(memberIds);
    const normalizedMemberIds = pairKey.split("|");
    const normalizedCreator = String(createdByMemberId || "").trim() || null;
    if (!normalizedFamilyId) {
      throw new Error("Family id is required");
    }
    if (normalizedCreator && !normalizedMemberIds.includes(normalizedCreator)) {
      throw new Error("Conversation creator must be a participant");
    }

    return this.withTransaction(async () => {
      const placeholders = normalizedMemberIds.map(() => "?").join(",");
      const members = await this.all(
        `SELECT id
         FROM family_members
         WHERE family_id = ?
           AND is_active = 1
           AND id IN (${placeholders})`,
        [normalizedFamilyId, ...normalizedMemberIds]
      );
      if (members.length !== 2) {
        throw new Error("Direct conversation members must belong to the family");
      }

      const now = Date.now();
      const conversationId = this.createDirectConversationId(
        normalizedFamilyId,
        normalizedMemberIds
      );
      await this.run(
        `INSERT INTO chat_conversations (
           id,
           family_id,
           type,
           title,
           direct_pair_key,
           created_by_member_id,
           next_sequence,
           is_active,
           created_at,
           updated_at
         ) VALUES (?, ?, 'DIRECT', ?, ?, ?, 0, 1, ?, ?)
         ON CONFLICT(id) DO UPDATE SET
           is_active = 1,
           title = COALESCE(chat_conversations.title, excluded.title),
           updated_at = excluded.updated_at
         WHERE chat_conversations.is_active <> 1
            OR (chat_conversations.title IS NULL AND excluded.title IS NOT NULL)`,
        [
          conversationId,
          normalizedFamilyId,
          title,
          pairKey,
          normalizedCreator,
          now,
          now,
        ]
      );
      await this.run(
        `UPDATE chat_conversation_members
         SET is_active = 0,
             left_at = COALESCE(left_at, ?),
             updated_at = ?
         WHERE conversation_id = ?
           AND is_active = 1
           AND member_id NOT IN (?, ?)`,
        [now, now, conversationId, ...normalizedMemberIds]
      );
      for (const memberId of normalizedMemberIds) {
        await this.ensureConversationMemberRecord(conversationId, memberId, now);
      }
      return this.getChatConversationById(conversationId);
    });
  }

  async getChatConversationById(conversationId) {
    return this.get(
      `SELECT
         id,
         family_id AS familyId,
         type,
         title,
         direct_pair_key AS directPairKey,
         created_by_member_id AS createdByMemberId,
         next_sequence AS nextSequence,
         is_active AS isActive,
         created_at AS createdAt,
         updated_at AS updatedAt
       FROM chat_conversations
       WHERE id = ?`,
      [conversationId]
    );
  }

  async getChatConversationForMember(conversationId, memberId) {
    return this.get(
      `SELECT
         c.id,
         c.family_id AS familyId,
         c.type,
         c.title,
         c.direct_pair_key AS directPairKey,
         c.next_sequence AS nextSequence,
         c.updated_at AS updatedAt,
         cm.last_delivered_sequence AS lastDeliveredSequence,
         cm.last_read_sequence AS lastReadSequence,
         cm.muted_until AS mutedUntil
       FROM chat_conversations c
       JOIN chat_conversation_members cm ON cm.conversation_id = c.id
       JOIN family_members fm
         ON fm.id = cm.member_id AND fm.family_id = c.family_id
       JOIN families f ON f.id = c.family_id
       WHERE c.id = ?
         AND cm.member_id = ?
         AND c.is_active = 1
         AND cm.is_active = 1
         AND fm.is_active = 1
         AND f.is_active = 1
       LIMIT 1`,
      [conversationId, memberId]
    );
  }

  async listChatConversationsForMember(memberId, limit = 100) {
    const normalizedLimit = Math.max(1, Math.min(Number(limit) || 100, 200));
    return this.all(
      `SELECT
         c.id,
         c.family_id AS familyId,
         c.type,
         c.title,
         c.direct_pair_key AS directPairKey,
         c.next_sequence AS nextSequence,
         c.updated_at AS updatedAt,
         cm.last_delivered_sequence AS lastDeliveredSequence,
         cm.last_read_sequence AS lastReadSequence,
         cm.muted_until AS mutedUntil,
         COALESCE(
           (
             SELECT MAX(activity.server_created_at)
             FROM chat_messages_v2 activity
             WHERE activity.conversation_id = c.id
           ),
           c.created_at
         ) AS lastActivityAt,
         (
           SELECT m.text
           FROM chat_messages_v2 m
           WHERE m.conversation_id = c.id
           ORDER BY m.sequence DESC
           LIMIT 1
         ) AS lastMessageText,
         (
           SELECT COUNT(*)
           FROM chat_message_receipts r
           JOIN chat_messages_v2 m ON m.id = r.message_id
           WHERE m.conversation_id = c.id
             AND r.recipient_member_id = cm.member_id
             AND r.read_at IS NULL
         ) AS unreadCount
       FROM chat_conversations c
       JOIN chat_conversation_members cm ON cm.conversation_id = c.id
       JOIN family_members fm
         ON fm.id = cm.member_id AND fm.family_id = c.family_id
       JOIN families f ON f.id = c.family_id
       WHERE cm.member_id = ?
         AND cm.is_active = 1
         AND c.is_active = 1
         AND fm.is_active = 1
         AND f.is_active = 1
       ORDER BY lastActivityAt DESC, c.id
       LIMIT ?`,
      [memberId, normalizedLimit]
    );
  }

  mapChatMessageV2Row(row) {
    if (!row) return null;
    return {
      id: row.id,
      conversationId: row.conversation_id,
      sequence: row.sequence,
      senderMemberId: row.sender_member_id || null,
      senderDeviceId: row.sender_device_id || null,
      senderRoleSnapshot: row.sender_role_snapshot || null,
      senderDisplayName: row.sender_display_name_snapshot,
      clientMessageId: row.client_message_id,
      text: row.text,
      clientSentAt: row.client_sent_at || null,
      serverCreatedAt: row.server_created_at,
      legacyMessageId: row.legacy_message_id || null,
      legacyDelivered:
        row.legacy_delivered === null || row.legacy_delivered === undefined
          ? null
          : row.legacy_delivered === 1,
      legacyRead:
        row.legacy_read === null || row.legacy_read === undefined
          ? null
          : row.legacy_read === 1,
    };
  }

  async getChatMessageV2ById(messageId) {
    const row = await this.get(
      `SELECT * FROM chat_messages_v2 WHERE id = ? LIMIT 1`,
      [messageId]
    );
    return this.mapChatMessageV2Row(row);
  }

  async allocateChatSequenceRecord(conversationId) {
    const updated = await this.run(
      `UPDATE chat_conversations
       SET next_sequence = next_sequence + 1
       WHERE id = ? AND is_active = 1`,
      [conversationId]
    );
    if (updated.changes !== 1) {
      throw new Error("Chat conversation not found");
    }
    const row = await this.get(
      `SELECT next_sequence AS sequence
       FROM chat_conversations
       WHERE id = ?`,
      [conversationId]
    );
    return Number(row.sequence);
  }

  async insertChatMessageV2({
    conversationId,
    senderMemberId,
    senderDeviceId = null,
    senderRoleSnapshot = null,
    senderDisplayName = null,
    clientMessageId,
    text,
    clientSentAt = null,
    serverCreatedAt = Date.now(),
  }) {
    const normalizedConversationId = String(conversationId || "").trim();
    const normalizedSenderMemberId = String(senderMemberId || "").trim();
    const normalizedClientMessageId = String(clientMessageId || "").trim();
    const normalizedText = this.normalizeChatText(text);
    if (!normalizedConversationId || !normalizedSenderMemberId) {
      throw new Error("Conversation and sender member are required");
    }
    if (!normalizedClientMessageId || normalizedClientMessageId.length > 200) {
      throw new Error("A valid client message id is required");
    }

    return this.withTransaction(async () => {
      const membership = await this.get(
        `SELECT fm.display_name AS displayName, fm.role
         FROM chat_conversation_members cm
         JOIN chat_conversations c ON c.id = cm.conversation_id
         JOIN family_members fm
           ON fm.id = cm.member_id AND fm.family_id = c.family_id
         JOIN families f ON f.id = c.family_id
         WHERE cm.conversation_id = ?
           AND cm.member_id = ?
           AND cm.is_active = 1
           AND fm.is_active = 1
           AND f.is_active = 1
           AND c.is_active = 1
         LIMIT 1`,
        [normalizedConversationId, normalizedSenderMemberId]
      );
      if (!membership) {
        throw new Error("Sender is not an active conversation member");
      }

      const duplicate = await this.get(
        `SELECT *
         FROM chat_messages_v2
         WHERE sender_member_id = ? AND client_message_id = ?
         LIMIT 1`,
        [normalizedSenderMemberId, normalizedClientMessageId]
      );
      if (duplicate) {
        if (duplicate.conversation_id !== normalizedConversationId) {
          throw new Error(
            "Client message id is already used in another conversation"
          );
        }
        return {
          created: false,
          deduplicated: true,
          message: this.mapChatMessageV2Row(duplicate),
        };
      }

      const sequence = await this.allocateChatSequenceRecord(
        normalizedConversationId
      );
      const messageId = this.createStableScopedId(
        "chat_message",
        normalizedConversationId,
        normalizedSenderMemberId,
        normalizedClientMessageId
      );
      const now = this.normalizeEpochMilliseconds(serverCreatedAt);
      const normalizedClientSentAt = clientSentAt
        ? this.normalizeEpochMilliseconds(clientSentAt, now)
        : null;
      const displayName =
        String(senderDisplayName || "").trim() || membership.displayName || "Участник";
      const role =
        String(senderRoleSnapshot || "").trim() || membership.role || null;

      await this.run(
        `INSERT INTO chat_messages_v2 (
           id,
           conversation_id,
           sequence,
           sender_member_id,
           sender_device_id,
           sender_role_snapshot,
           sender_display_name_snapshot,
           client_message_id,
           text,
           client_sent_at,
           server_created_at,
           created_at
         ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        [
          messageId,
          normalizedConversationId,
          sequence,
          normalizedSenderMemberId,
          String(senderDeviceId || "").trim() || null,
          role,
          displayName,
          normalizedClientMessageId,
          normalizedText,
          normalizedClientSentAt,
          now,
          now,
        ]
      );

      await this.run(
        `INSERT OR IGNORE INTO chat_message_receipts (
           message_id,
           recipient_member_id,
           created_at,
           updated_at
         )
         SELECT ?, member_id, ?, ?
         FROM chat_conversation_members cm
         JOIN chat_conversations c ON c.id = cm.conversation_id
         JOIN family_members fm
           ON fm.id = cm.member_id AND fm.family_id = c.family_id
         JOIN families f ON f.id = c.family_id
         WHERE cm.conversation_id = ?
           AND cm.is_active = 1
           AND fm.is_active = 1
           AND f.is_active = 1
           AND cm.member_id <> ?
           AND (
             c.type = 'DIRECT'
             OR EXISTS (
               SELECT 1
               FROM family_devices fd
               LEFT JOIN devices d ON d.device_id = fd.device_id
               WHERE fd.family_id = c.family_id
                 AND fd.member_id = cm.member_id
                 AND fd.is_active = 1
                 AND (
                   fd.member_binding_source = 'EXPLICIT'
                   OR COALESCE(d.updated_at, d.created_at, 0) >=
                      strftime('%s', 'now') - ?
                 )
             )
           )`,
        [
          messageId,
          now,
          now,
          normalizedConversationId,
          normalizedSenderMemberId,
          CHAT_LEGACY_MEMBER_ACTIVE_WINDOW_SECONDS,
        ]
      );
      await this.run(
        `UPDATE chat_conversations
         SET updated_at = MAX(updated_at, ?)
         WHERE id = ?`,
        [now, normalizedConversationId]
      );

      return {
        created: true,
        deduplicated: false,
        message: await this.getChatMessageV2ById(messageId),
      };
    });
  }

  async getChatMessagesV2Page(
    conversationId,
    { beforeSequence = null, limit = 50 } = {}
  ) {
    const normalizedLimit = Math.max(1, Math.min(Number(limit) || 50, 200));
    const conversation = await this.getChatConversationById(conversationId);
    if (!conversation || conversation.isActive !== 1) {
      throw new Error("Chat conversation not found");
    }
    const parsedBefore = Number(beforeSequence);
    const exclusiveBefore =
      Number.isInteger(parsedBefore) && parsedBefore > 0
        ? parsedBefore
        : Number(conversation.nextSequence) + 1;
    const descending = await this.all(
      `SELECT *
       FROM chat_messages_v2
       WHERE conversation_id = ? AND sequence < ?
       ORDER BY sequence DESC
       LIMIT ?`,
      [conversationId, exclusiveBefore, normalizedLimit]
    );
    const rows = descending.reverse();
    const messages = rows.map((row) => this.mapChatMessageV2Row(row));
    const oldestSequence = messages.length ? messages[0].sequence : null;
    const older = oldestSequence
      ? await this.get(
          `SELECT 1 AS present
           FROM chat_messages_v2
           WHERE conversation_id = ? AND sequence < ?
           LIMIT 1`,
          [conversationId, oldestSequence]
        )
      : null;
    return {
      messages,
      hasMore: Boolean(older),
      nextBeforeSequence: oldestSequence,
    };
  }

  async advanceChatMemberReceipt({
    conversationId,
    memberId,
    deliveredThroughSequence = null,
    readThroughSequence = null,
    deviceId = null,
    timestamp = Date.now(),
  }) {
    const deliveredCandidate = Number(deliveredThroughSequence);
    const readCandidate = Number(readThroughSequence);
    const hasDelivered =
      Number.isInteger(deliveredCandidate) && deliveredCandidate >= 0;
    const hasRead = Number.isInteger(readCandidate) && readCandidate >= 0;
    if (!hasDelivered && !hasRead) {
      throw new Error("A delivered or read sequence is required");
    }

    return this.withTransaction(async () => {
      const conversation = await this.getChatConversationForMember(
        conversationId,
        memberId
      );
      if (!conversation) {
        throw new Error("Conversation membership not found");
      }
      const maximum = Number(conversation.nextSequence) || 0;
      const readSequence = hasRead
        ? Math.min(readCandidate, maximum)
        : Number(conversation.lastReadSequence) || 0;
      const deliveredSequence = Math.max(
        hasDelivered ? Math.min(deliveredCandidate, maximum) : 0,
        readSequence
      );
      const now = this.normalizeEpochMilliseconds(timestamp);
      const normalizedDeviceId = String(deviceId || "").trim() || null;

      await this.run(
        `UPDATE chat_conversation_members
         SET last_delivered_sequence = MAX(last_delivered_sequence, ?),
             last_read_sequence = MAX(last_read_sequence, ?),
             updated_at = ?
         WHERE conversation_id = ? AND member_id = ? AND is_active = 1`,
        [deliveredSequence, readSequence, now, conversationId, memberId]
      );
      if (deliveredSequence > 0) {
        await this.run(
          `UPDATE chat_message_receipts
           SET delivered_at = COALESCE(delivered_at, ?),
               delivered_by_device_id = COALESCE(delivered_by_device_id, ?),
               updated_at = ?
           WHERE recipient_member_id = ?
             AND message_id IN (
               SELECT id FROM chat_messages_v2
               WHERE conversation_id = ? AND sequence <= ?
             )`,
          [
            now,
            normalizedDeviceId,
            now,
            memberId,
            conversationId,
            deliveredSequence,
          ]
        );
      }
      if (hasRead && readSequence > 0) {
        await this.run(
          `UPDATE chat_message_receipts
           SET delivered_at = COALESCE(delivered_at, ?),
               read_at = COALESCE(read_at, ?),
               delivered_by_device_id = COALESCE(delivered_by_device_id, ?),
               read_by_device_id = COALESCE(read_by_device_id, ?),
               updated_at = ?
           WHERE recipient_member_id = ?
             AND message_id IN (
               SELECT id FROM chat_messages_v2
               WHERE conversation_id = ? AND sequence <= ?
             )`,
          [
            now,
            now,
            normalizedDeviceId,
            normalizedDeviceId,
            now,
            memberId,
            conversationId,
            readSequence,
          ]
        );
      }

      return this.getChatConversationForMember(conversationId, memberId);
    });
  }

  async getChatMessageReceipts(messageId) {
    return this.all(
      `SELECT
         message_id AS messageId,
         recipient_member_id AS recipientMemberId,
         delivered_at AS deliveredAt,
         read_at AS readAt,
         delivered_by_device_id AS deliveredByDeviceId,
         read_by_device_id AS readByDeviceId
       FROM chat_message_receipts
       WHERE message_id = ?
       ORDER BY recipient_member_id`,
      [messageId]
    );
  }

  async ensureLegacyThreadConversationRecord(legacyDeviceId, now = Date.now()) {
    const normalizedDeviceId = String(legacyDeviceId || "").trim();
    if (!normalizedDeviceId) {
      throw new Error("Legacy chat device id is required");
    }

    const existingThread = await this.get(
      `SELECT
         legacy_device_id AS legacyDeviceId,
         conversation_id AS conversationId,
         family_id AS familyId
       FROM chat_legacy_threads
       WHERE legacy_device_id = ?
       LIMIT 1`,
      [normalizedDeviceId]
    );
    if (existingThread) {
      return existingThread;
    }

    const membership = await this.get(
      `SELECT fd.family_id AS familyId
       FROM family_devices fd
       JOIN families f ON f.id = fd.family_id
       WHERE fd.device_id = ?
         AND fd.is_active = 1
         AND f.is_active = 1
       ORDER BY fd.family_id
       LIMIT 1`,
      [normalizedDeviceId]
    );

    let familyId = membership?.familyId || null;
    let conversationId;
    if (familyId) {
      const conversation = await this.ensureFamilyConversationRecord(familyId, {
        now,
      });
      conversationId = conversation.id;
    } else {
      // Preserve messages written before a valid device link existed. These
      // orphan conversations are migration-only and are not returned by the
      // member-scoped list until a later explicit reconciliation attaches them.
      conversationId = this.createStableScopedId(
        "chat_conversation",
        "LEGACY",
        normalizedDeviceId
      );
      await this.run(
        `INSERT INTO chat_conversations (
           id,
           family_id,
           type,
           title,
           next_sequence,
           is_active,
           created_at,
           updated_at
         ) VALUES (?, NULL, 'LEGACY', ?, 0, 1, ?, ?)
         ON CONFLICT(id) DO UPDATE SET
           is_active = 1,
           updated_at = MAX(chat_conversations.updated_at, excluded.updated_at)`,
        [conversationId, `Legacy ${normalizedDeviceId}`, now, now]
      );
    }

    await this.run(
      `INSERT INTO chat_legacy_threads (
         legacy_device_id,
         conversation_id,
         family_id,
         created_at,
         updated_at
       ) VALUES (?, ?, ?, ?, ?)
       ON CONFLICT(legacy_device_id) DO NOTHING`,
      [normalizedDeviceId, conversationId, familyId, now, now]
    );
    return {
      legacyDeviceId: normalizedDeviceId,
      conversationId,
      familyId,
    };
  }

  async resolveLegacyChatSender(row, thread) {
    const senderRole = String(row.sender || "").trim().toLowerCase();
    const explicitSenderDeviceId = String(row.sender_device_id || "").trim();
    const inferredSenderDeviceId =
      explicitSenderDeviceId ||
      (senderRole === "child" ? String(row.device_id || "").trim() : "");
    let member = null;

    if (thread.familyId && inferredSenderDeviceId) {
      member = await this.get(
        `SELECT
           fd.member_id AS memberId,
           fm.display_name AS displayName,
           fm.role
         FROM family_devices fd
         JOIN family_members fm ON fm.id = fd.member_id
         WHERE fd.family_id = ? AND fd.device_id = ?
         ORDER BY fd.is_active DESC, fm.is_active DESC, fd.updated_at DESC
         LIMIT 1`,
        [thread.familyId, inferredSenderDeviceId]
      );
    }

    if (!member && thread.familyId && senderRole === "parent") {
      const possibleParents = await this.all(
        `SELECT
           cm.member_id AS memberId,
           fm.display_name AS displayName,
           fm.role
         FROM chat_conversation_members cm
         JOIN family_members fm ON fm.id = cm.member_id
         WHERE cm.conversation_id = ?
           AND cm.is_active = 1
           AND fm.is_active = 1
           AND fm.role IN ('PARENT', 'GUARDIAN')
         ORDER BY cm.member_id
         LIMIT 2`,
        [thread.conversationId]
      );
      // A missing legacy parent device id is attributable only when there is
      // exactly one possible parent. Otherwise keep sender_member_id NULL.
      if (possibleParents.length === 1) {
        member = possibleParents[0];
      }
    }

    const explicitDisplayName = String(row.sender_display_name || "").trim();
    return {
      memberId: member?.memberId || null,
      deviceId: inferredSenderDeviceId || null,
      roleSnapshot: senderRole ? senderRole.toUpperCase() : null,
      displayName:
        explicitDisplayName ||
        member?.displayName ||
        (senderRole === "child" ? "Ребенок" : "Родитель"),
      ambiguous: !member,
    };
  }

  async insertLegacyChatMessageV2Record(row, thread, sender, now = Date.now()) {
    const sequence = await this.allocateChatSequenceRecord(thread.conversationId);
    const messageId = this.createStableScopedId(
      "chat_message_legacy",
      row.id
    );
    const clientMessageId =
      String(row.client_message_id || "").trim() || `legacy-${row.id}`;
    const clientSentAt = this.normalizeEpochMilliseconds(row.timestamp, now);
    const serverCreatedAt = this.normalizeEpochMilliseconds(
      row.created_at,
      clientSentAt
    );
    const legacyDelivered = row.delivered === 1 ? 1 : 0;
    const legacyRead = row.is_read === 1 || row.read_at ? 1 : 0;

    await this.run(
      `INSERT INTO chat_messages_v2 (
         id,
         conversation_id,
         sequence,
         sender_member_id,
         sender_device_id,
         sender_role_snapshot,
         sender_display_name_snapshot,
         client_message_id,
         text,
         client_sent_at,
         server_created_at,
         legacy_message_id,
         legacy_delivered,
         legacy_read,
         created_at
       ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      [
        messageId,
        thread.conversationId,
        sequence,
        sender.memberId,
        sender.deviceId,
        sender.roleSnapshot,
        sender.displayName,
        clientMessageId,
        String(row.message ?? ""),
        clientSentAt,
        serverCreatedAt,
        row.id,
        legacyDelivered,
        legacyRead,
        serverCreatedAt,
      ]
    );

    const recipients = await this.all(
      `SELECT member_id AS memberId
       FROM chat_conversation_members
       WHERE conversation_id = ?
         AND is_active = 1
         AND (? IS NULL OR member_id <> ?)
       ORDER BY member_id
       LIMIT 2`,
      [thread.conversationId, sender.memberId, sender.memberId]
    );
    const hasUnambiguousRecipient = Boolean(sender.memberId) && recipients.length === 1;
    const rawDeliveredAt = row.delivered_at || (legacyDelivered ? serverCreatedAt : null);
    const rawReadAt = row.read_at || (legacyRead ? serverCreatedAt : null);
    const readAt =
      hasUnambiguousRecipient && legacyRead
        ? this.normalizeEpochMilliseconds(rawReadAt, serverCreatedAt)
        : null;
    const deliveredAt =
      hasUnambiguousRecipient && (legacyDelivered || legacyRead)
        ? this.normalizeEpochMilliseconds(
            rawDeliveredAt || rawReadAt,
            serverCreatedAt
          )
        : null;

    if (hasUnambiguousRecipient) {
      const recipient = recipients[0];
      await this.run(
        `INSERT OR IGNORE INTO chat_message_receipts (
           message_id,
           recipient_member_id,
           delivered_at,
           read_at,
           created_at,
           updated_at
         ) VALUES (?, ?, ?, ?, ?, ?)`,
        [
          messageId,
          recipient.memberId,
          deliveredAt,
          readAt,
          serverCreatedAt,
          serverCreatedAt,
        ]
      );
    }
    if (hasUnambiguousRecipient && recipients[0]) {
      await this.run(
        `UPDATE chat_conversation_members
         SET last_delivered_sequence = MAX(last_delivered_sequence, ?),
             last_read_sequence = MAX(last_read_sequence, ?),
             updated_at = MAX(updated_at, ?)
         WHERE conversation_id = ? AND member_id = ?`,
        [
          deliveredAt ? sequence : 0,
          readAt ? sequence : 0,
          serverCreatedAt,
          thread.conversationId,
          recipients[0].memberId,
        ]
      );
    }
    return messageId;
  }

  async migrateLegacyChatToConversations() {
    return this.withTransaction(async () => {
      const now = Date.now();
      const activeFamilies = await this.all(
        `SELECT id FROM families WHERE is_active = 1 ORDER BY id`
      );
      for (const family of activeFamilies) {
        await this.ensureFamilyConversationRecord(family.id, { now });
      }

      const totalLegacy = await this.get(
        `SELECT COUNT(*) AS count FROM chat_messages`
      );
      const legacyRows = await this.all(
        `SELECT legacy.*
         FROM chat_messages legacy
         LEFT JOIN chat_messages_v2 migrated
           ON migrated.legacy_message_id = legacy.id
         WHERE migrated.id IS NULL
         ORDER BY legacy.timestamp ASC, legacy.id ASC`
      );
      let imported = 0;
      const totalLegacyRows = Number(totalLegacy?.count) || 0;
      const alreadyImported = Math.max(0, totalLegacyRows - legacyRows.length);
      let orphaned = 0;
      let ambiguousAuthors = 0;
      const threadCache = new Map();

      if (legacyRows.length > 0) {
        console.log(
          `[chat-v2] Importing ${legacyRows.length} pending legacy message(s) out of ${totalLegacyRows}`
        );
      }

      for (const row of legacyRows) {
        const legacyDeviceId = String(row.device_id || "").trim();
        let thread = threadCache.get(legacyDeviceId);
        if (!thread) {
          thread = await this.ensureLegacyThreadConversationRecord(
            legacyDeviceId,
            now
          );
          threadCache.set(legacyDeviceId, thread);
        }
        const sender = await this.resolveLegacyChatSender(row, thread);
        if (!thread.familyId) orphaned += 1;
        if (sender.ambiguous) ambiguousAuthors += 1;
        await this.insertLegacyChatMessageV2Record(row, thread, sender, now);
        imported += 1;
        if (imported % 50 === 0 || imported === legacyRows.length) {
          console.log(
            `[chat-v2] Legacy import progress: ${imported}/${legacyRows.length}`
          );
        }
      }

      const details = {
        totalLegacyRows,
        imported,
        alreadyImported,
        orphaned,
        ambiguousAuthors,
        removedPathologicalReceipts:
          await this.cleanupPathologicalLegacyReceipts(),
      };
      await this.run(
        `INSERT INTO schema_migrations (
           name,
           applied_at,
           last_run_at,
           details_json
         ) VALUES ('chat_conversations_v1', ?, ?, ?)
         ON CONFLICT(name) DO UPDATE SET
           last_run_at = excluded.last_run_at,
           details_json = excluded.details_json`,
        [now, now, JSON.stringify(details)]
      );
      return details;
    });
  }

  async cleanupPathologicalLegacyReceipts() {
    const migrationName = "chat_legacy_receipt_cleanup_v1";
    const existing = await this.get(
      `SELECT 1 AS applied
       FROM schema_migrations
       WHERE name = ?
       LIMIT 1`,
      [migrationName]
    );
    if (existing) return 0;

    // Early chat-v2 builds created an empty receipt for every provisional
    // legacy identity. On databases containing years of stale device links a
    // single old message could therefore produce thousands of rows. Preserve
    // normal family receipts and remove only clearly pathological fan-out.
    const result = await this.run(
      `DELETE FROM chat_message_receipts
       WHERE message_id IN (
         SELECT receipts.message_id
         FROM chat_message_receipts receipts
         JOIN chat_messages_v2 message ON message.id = receipts.message_id
         WHERE message.legacy_message_id IS NOT NULL
         GROUP BY receipts.message_id
         HAVING COUNT(*) > ?
       )`,
      [MAX_SAFE_LEGACY_RECEIPTS_PER_MESSAGE]
    );
    const removed = Number(result?.changes) || 0;
    const now = Date.now();
    await this.run(
      `INSERT INTO schema_migrations (
         name, applied_at, last_run_at, details_json
       ) VALUES (?, ?, ?, ?)`,
      [migrationName, now, now, JSON.stringify({ removed })]
    );
    if (removed > 0) {
      console.log(
        `[chat-v2] Removed ${removed} pathological legacy receipt row(s)`
      );
    }
    return removed;
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
  isInsideTransaction() {
    const context = this.transactionContext.getStore();
    return context?.manager === this && context.active === true;
  }

  enqueueDatabaseOperation(operation) {
    const queued = this.databaseQueue.then(operation, operation);
    this.databaseQueue = queued.catch(() => undefined);
    return queued;
  }

  rawRun(sql, params = []) {
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

  run(sql, params = []) {
    if (this.isInsideTransaction()) {
      return this.rawRun(sql, params);
    }
    return this.enqueueDatabaseOperation(() => this.rawRun(sql, params));
  }

  /**
   * Get all rows from query
   */
  rawAll(sql, params = []) {
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

  all(sql, params = []) {
    if (this.isInsideTransaction()) {
      return this.rawAll(sql, params);
    }
    return this.enqueueDatabaseOperation(() => this.rawAll(sql, params));
  }

  /**
   * Get single row from query
   */
  rawGet(sql, params = []) {
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

  get(sql, params = []) {
    if (this.isInsideTransaction()) {
      return this.rawGet(sql, params);
    }
    return this.enqueueDatabaseOperation(() => this.rawGet(sql, params));
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
    const normalizeOptionalDisplayName = (value) => {
      const normalized = String(value || "").trim().slice(0, 100);
      return normalized || null;
    };
    displayName = normalizeOptionalDisplayName(displayName);
    parentDisplayName = normalizeOptionalDisplayName(parentDisplayName);
    childDisplayName = normalizeOptionalDisplayName(childDisplayName);

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
      await this.bootstrapFamiliesFromDeviceLinks({ force: true });
    }

    // The relation can be renamed after its compatibility family was created.
    // Keep canonical family-member labels synchronized so notifications identify
    // a person, not an old phone-model label. Re-reading also repairs stale rows
    // on the next ordinary registration when the submitted label is unchanged.
    const refreshedLink = await this.get(
      `SELECT
         parent_display_name AS parentDisplayName,
         child_display_name AS childDisplayName,
         display_name AS displayName
       FROM device_links
       WHERE parent_device_id = ? AND child_device_id = ?
       LIMIT 1`,
      [parentDeviceId, childDeviceId]
    );
    if (refreshedLink) {
      await this.syncFamilyMemberDisplayName(
        parentDeviceId,
        refreshedLink.parentDisplayName
      );
      await this.syncFamilyMemberDisplayName(
        childDeviceId,
        refreshedLink.childDisplayName || refreshedLink.displayName
      );
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
                sender_device_id = COALESCE(chat_messages.sender_device_id, excluded.sender_device_id),
                sender_display_name = COALESCE(chat_messages.sender_display_name, excluded.sender_display_name),
                delivered = MAX(chat_messages.delivered, excluded.delivered),
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
    if (!this.db) {
      return Promise.resolve();
    }
    if (this.isInsideTransaction()) {
      return Promise.reject(
        new Error("Database cannot be closed from inside a transaction")
      );
    }
    return this.enqueueDatabaseOperation(
      () =>
        new Promise((resolve, reject) => {
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
        })
    );
  }
}

module.exports = DatabaseManager;
