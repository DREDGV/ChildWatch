CREATE TABLE IF NOT EXISTS attention_signals (
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
);

CREATE INDEX IF NOT EXISTS
idx_attention_target_created
ON attention_signals(
    target_device_id,
    created_at DESC
);

CREATE INDEX IF NOT EXISTS
idx_attention_requester_created
ON attention_signals(
    requester_device_id,
    created_at DESC
);

CREATE INDEX IF NOT EXISTS
idx_attention_family_created
ON attention_signals(
    family_id,
    created_at DESC
);
