const fs = require('fs');
const fsp = fs.promises;
const path = require('path');
const crypto = require('crypto');
const ready = new WeakMap();
const gcJobs = new WeakMap();
const FILE_LIMIT = 25 * 1024 * 1024;
const IMAGE_LIMIT = 10 * 1024 * 1024;
const TYPES = ['IMAGE', 'FILE', 'GIF', 'VOICE', 'STICKER'];
class AttachmentError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}
class ChatAttachmentStore {
  constructor(db, options = {}) {
    this.db = db;
    this.root = path.resolve(options.root || process.env.CHAT_ATTACHMENT_ROOT || path.join(__dirname, '../private-data/chat-attachments'));
    this.temp = path.join(this.root, 'staging');
    this.gcDirectories = new Map();
    this.gcPending = null;
    this.db.db?.once('close', () => {
      for (const directory of this.gcDirectories.values()) directory.close().catch(() => {});
      this.gcDirectories.clear();
    });
    fs.mkdirSync(this.temp, {
      recursive: true,
      mode: 0o700
    });
  }
  startGc() {
    if (gcJobs.has(this.db)) return;
    const job = {
      busy: false,
      timer: null,
      stopped: false
    };
    gcJobs.set(this.db, job);
    const run = () => {
      if (job.busy || job.stopped) return;
      job.busy = true;
      this.gc().catch(error => {
        if (!job.stopped) console.warn('Chat attachment cleanup deferred:', error.message);
      }).finally(() => {
        job.busy = false;
      });
    };
    job.timer = setInterval(run, 5 * 60 * 1000);
    job.timer.unref();
    this.db.db?.once('close', () => {
      job.stopped = true;
      clearInterval(job.timer);
      gcJobs.delete(this.db);
    });
    run();
  }
  async ensure() {
    if (!ready.has(this.db)) ready.set(this.db, (async () => {
      await this.db.run(`CREATE TABLE IF NOT EXISTS chat_attachments (
        id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL, owner_member_id TEXT NOT NULL,
        owner_device_id TEXT NOT NULL, client_upload_id TEXT, filename TEXT NOT NULL,
        mime_type TEXT NOT NULL, size_bytes INTEGER NOT NULL, sha256 TEXT NOT NULL,
        type TEXT NOT NULL, duration_ms INTEGER, state TEXT NOT NULL DEFAULT 'READY', created_at INTEGER NOT NULL, cleaned_at INTEGER,
        UNIQUE(conversation_id,owner_device_id,client_upload_id),
        FOREIGN KEY(conversation_id) REFERENCES chat_conversations(id))`);
      await this.db.run(`CREATE TABLE IF NOT EXISTS chat_message_media (
        message_id TEXT PRIMARY KEY, message_type TEXT NOT NULL, attachment_id TEXT NOT NULL UNIQUE,
        FOREIGN KEY(message_id) REFERENCES chat_messages_v2(id), FOREIGN KEY(attachment_id) REFERENCES chat_attachments(id))`);
      await this.db.run('CREATE INDEX IF NOT EXISTS idx_chat_attachment_gc ON chat_attachments(state,created_at)');
    })().catch(error => {
      ready.delete(this.db);
      throw error;
    }));
    await ready.get(this.db);
  }
  blob(id) {
    if (!/^[0-9a-f-]{36}$/.test(id)) throw new AttachmentError(404, 'ATTACHMENT_NOT_FOUND', 'Attachment not found');
    return path.join(this.root, id + '.blob');
  }
  dto(row) {
    return {
      attachmentId: row.id,
      filename: row.filename,
      mimeType: row.mime_type,
      sizeBytes: row.size_bytes,
      sha256: row.sha256,
      type: row.type,
      durationMs: row.duration_ms || null
    };
  }
  async assertActor(actor) {
    const membership = await this.db.getFamilyDeviceMembership(actor.familyId, actor.deviceId);
    const conversation = await this.db.getChatConversationForMember(actor.conversation.id, actor.memberId);
    if (!membership || membership.memberId !== actor.memberId || !conversation || conversation.familyId !== actor.familyId) throw new AttachmentError(403, 'ATTACHMENT_ACCESS_DENIED', 'Conversation or sending phone access changed');
  }
  async stage(actor, file, input) {
    if (!file) throw new AttachmentError(400, 'ATTACHMENT_REQUIRED', 'Choose a file');
    try {
      await this.ensure();
      const type = input.attachmentType || 'FILE';
      if (!TYPES.includes(type)) throw new AttachmentError(400, 'INVALID_ATTACHMENT_TYPE', 'Unsupported attachment type');
      const limit = type === 'FILE' ? FILE_LIMIT : IMAGE_LIMIT;
      if (file.size < 1 || file.size > limit) throw new AttachmentError(413, 'ATTACHMENT_TOO_LARGE', `Attachment must be between 1 byte and ${limit} bytes`);
      let original = String(file.originalname || "file");
      if (/^[\x00-\xff]*$/.test(original)) {
        const decoded = Buffer.from(original, "latin1").toString("utf8");
        if (!decoded.includes("\ufffd")) original = decoded;
      }
      const filename = path.basename(String(original || 'file').replace(/\\/g, '/')).replace(/[\x00-\x1f\x7f]/g, '').slice(0, 180) || 'file';
      const header = Buffer.alloc(32);
      const handle = await fsp.open(file.path, 'r');
      try {
        await handle.read(header, 0, 32, 0);
      } finally {
        await handle.close();
      }
      let detected = null;
      if (header.subarray(0, 3).equals(Buffer.from([255, 216, 255]))) detected = 'image/jpeg';else if (header.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))) detected = 'image/png';else if (/^GIF8[79]a/.test(header.toString('ascii', 0, 6))) detected = 'image/gif';else if (header.toString('ascii', 0, 4) === 'RIFF' && header.toString('ascii', 8, 12) === 'WEBP') detected = 'image/webp';
      const durationMs = type === 'VOICE' && input.durationMs !== undefined ? Number(input.durationMs) : null;
      if (type === 'IMAGE' && !detected) throw new AttachmentError(415, 'INVALID_IMAGE', 'File is not a supported image');
      if (type === 'GIF' && detected !== 'image/gif') throw new AttachmentError(415, 'INVALID_GIF', 'File is not GIF');
      if (type === 'STICKER' && detected !== 'image/png') throw new AttachmentError(415, 'INVALID_STICKER', 'Sticker requires PNG');
      if (type === 'VOICE' && (header.toString('ascii', 4, 8) !== 'ftyp' || !Number.isInteger(durationMs) || durationMs < 1 || durationMs > 180000)) throw new AttachmentError(415, 'INVALID_VOICE', 'Voice requires M4A and a duration up to three minutes');
      // Generic files always download; a client MIME label never makes executable content inline.
      const mime = type === 'FILE' ? 'application/octet-stream' : type === 'VOICE' ? 'audio/mp4' : detected;
      const hash = crypto.createHash('sha256');
      for await (const chunk of fs.createReadStream(file.path)) hash.update(chunk);
      const sha = hash.digest('hex');
      const clientId = input.clientUploadId === undefined ? null : String(input.clientUploadId);
      if (clientId !== null && (!clientId.trim() || clientId.length > 200)) throw new AttachmentError(400, 'INVALID_UPLOAD_ID', 'Invalid upload id');
      return await this.db.withTransaction(async () => {
        await this.assertActor(actor);
        if (clientId) {
          const old = await this.db.get('SELECT * FROM chat_attachments WHERE conversation_id=? AND owner_device_id=? AND client_upload_id=?', [actor.conversation.id, actor.deviceId, clientId]);
          if (old) {
            if (old.owner_member_id !== actor.memberId || old.state === 'DELETED' || old.sha256 !== sha || old.type !== type || old.duration_ms !== durationMs) throw new AttachmentError(409, 'UPLOAD_ID_CONFLICT', 'Upload id already used for different or removed content');
            return this.dto(old);
          }
        }
        const id = crypto.randomUUID();
        const destination = this.blob(id);
        await fsp.rename(file.path, destination);
        try {
          const row = {
            id,
            conversation_id: actor.conversation.id,
            owner_member_id: actor.memberId,
            owner_device_id: actor.deviceId,
            client_upload_id: clientId,
            filename,
            mime_type: mime,
            size_bytes: file.size,
            sha256: sha,
            type,
            duration_ms: durationMs,
            state: 'READY',
            created_at: Date.now()
          };
          await this.db.run(`INSERT INTO chat_attachments(id,conversation_id,owner_member_id,owner_device_id,client_upload_id,filename,mime_type,size_bytes,sha256,type,duration_ms,state,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)`, Object.values(row));
          return this.dto(row);
        } catch (error) {
          await fsp.rm(destination, {
            force: true
          });
          throw error;
        }
      });
    } finally {
      await fsp.rm(file.path, {
        force: true
      });
    }
  }
  async bind(actor, messageId, type, ids) {
    await this.assertActor(actor);
    const row = await this.db.get('SELECT * FROM chat_attachments WHERE id=?', [ids[0]]);
    if (!row || row.conversation_id !== actor.conversation.id || row.owner_member_id !== actor.memberId || row.owner_device_id !== actor.deviceId) throw new AttachmentError(403, 'ATTACHMENT_ACCESS_DENIED', 'Attachment does not belong to this sender and conversation');
    if (row.state !== 'READY' || row.type !== type || Date.now() - row.created_at > 24 * 3600000) throw new AttachmentError(409, 'ATTACHMENT_NOT_READY', 'Attachment is unavailable or already sent');
    await this.db.run('INSERT INTO chat_message_media(message_id,message_type,attachment_id) VALUES(?,?,?)', [messageId, type, row.id]);
    await this.db.run("UPDATE chat_attachments SET state='COMMITTED' WHERE id=?", [row.id]);
  }
  async media(message) {
    await this.ensure();
    const row = await this.db.get('SELECT a.*,m.message_type FROM chat_message_media m JOIN chat_attachments a ON a.id=m.attachment_id WHERE m.message_id=?', [message.id]);
    return {
      messageType: row ? row.message_type : 'TEXT',
      attachments: row && !message.deletedAt && row.state !== 'DELETED' ? [this.dto(row)] : []
    };
  }
  async readable(actor, id) {
    await this.ensure();
    const row = await this.db.get('SELECT * FROM chat_attachments WHERE id=? AND conversation_id=?', [id, actor.conversation.id]);
    if (!row || row.state === 'DELETED') throw new AttachmentError(404, 'ATTACHMENT_NOT_FOUND', 'Attachment unavailable');
    if (row.state === 'READY') {
      if (row.owner_device_id !== actor.deviceId || row.owner_member_id !== actor.memberId || Date.now() - row.created_at > 24 * 3600000) throw new AttachmentError(403, 'ATTACHMENT_ACCESS_DENIED', 'Draft is private to its sender');
    } else {
      const visible = await this.db.get(`SELECT m.id FROM chat_message_media r JOIN chat_messages_v2 m ON m.id=r.message_id
        WHERE r.attachment_id=? AND m.deleted_at IS NULL AND NOT EXISTS(
          SELECT 1 FROM chat_message_deletions_v2 d WHERE d.message_id=m.id AND d.device_id=?)`, [id, actor.deviceId]);
      if (!visible) throw new AttachmentError(404, 'ATTACHMENT_NOT_FOUND', 'Message attachment unavailable');
    }
    return row;
  }
  async cancel(actor, id) {
    await this.ensure();
    await this.db.withTransaction(async () => {
      await this.assertActor(actor);
      const row = await this.db.get('SELECT * FROM chat_attachments WHERE id=? AND conversation_id=?', [id, actor.conversation.id]);
      if (!row || row.owner_device_id !== actor.deviceId) throw new AttachmentError(404, 'ATTACHMENT_NOT_FOUND', 'Draft unavailable');
      if (row.state === 'COMMITTED') throw new AttachmentError(409, 'ATTACHMENT_ALREADY_SENT', 'Delete the sent message instead');
      await this.db.run("UPDATE chat_attachments SET state='DELETED' WHERE id=?", [id]);
    });
    await fsp.rm(this.blob(id), {
      force: true
    });
  }
  gc(now = Date.now()) {
    if (this.gcPending) return this.gcPending;
    this.gcPending = this.sweep(now).finally(() => { this.gcPending = null; });
    return this.gcPending;
  }
  async sweep(now) {
    await this.ensure();
    const expired = await this.db.all(`SELECT a.id FROM chat_attachments a WHERE (a.state='DELETED' AND a.cleaned_at IS NULL) OR
      (a.state='READY' AND a.created_at<?) OR (a.state='COMMITTED' AND NOT EXISTS(
        SELECT 1 FROM chat_message_media r JOIN chat_messages_v2 m ON m.id=r.message_id WHERE r.attachment_id=a.id AND m.deleted_at IS NULL)) LIMIT 100`, [now - 24 * 3600000]);
    for (const row of expired) {
      const removed = await this.db.withTransaction(async () => {
        const eligible = await this.db.get(`SELECT a.id FROM chat_attachments a WHERE a.id=? AND (
          a.state='DELETED' OR (a.state='READY' AND a.created_at<?) OR
          (a.state='COMMITTED' AND NOT EXISTS(SELECT 1 FROM chat_message_media r JOIN chat_messages_v2 m ON m.id=r.message_id WHERE r.attachment_id=a.id AND m.deleted_at IS NULL)))`, [row.id, now - 24 * 3600000]);
        if (!eligible) return false;
        // Keep message type as a withdrawn tombstone; no live content reference remains.
        await this.db.run("UPDATE chat_attachments SET state='DELETED' WHERE id=?", [row.id]);
        return true;
      });
      if (removed) {
        try {
          await fsp.rm(this.blob(row.id), {
            force: true
          });
          await this.db.run('UPDATE chat_attachments SET cleaned_at=? WHERE id=?', [now, row.id]);
        } catch (error) {
          if (!['EBUSY', 'EPERM', 'EACCES'].includes(error.code)) throw error;
        } // retry busy files on the next bounded sweep
      }
    }
    // Catch interrupted multipart uploads and a crash between blob rename and metadata insertion.
    for (const directory of [this.temp, this.root]) {
      let entries = this.gcDirectories.get(directory);
      if (!entries) {
        entries = await fsp.opendir(directory);
        this.gcDirectories.set(directory, entries);
      }
      // Retain the iterator between bounded sweeps so live files cannot starve later orphans.
      for (let checked = 0; checked < 100; checked++) {
        const entry = await entries.read();
        if (!entry) {
          this.gcDirectories.delete(directory);
          await entries.close();
          break;
        }
        if (!entry.isFile()) continue;
        const target = path.join(directory, entry.name);
        let stat;
        try {
          stat = await fsp.stat(target);
        } catch (error) {
          if (error.code === 'ENOENT') continue;
          throw error;
        }
        if (now - stat.mtimeMs < 24 * 3600000) continue;
        if (directory === this.root && (await this.db.get("SELECT id FROM chat_attachments WHERE id=? AND state!='DELETED'", [entry.name.replace(/\.blob$/, '')]))) continue;
        await fsp.rm(target, {
          force: true
        });
      }
    }
  }
}
ChatAttachmentStore.Error = AttachmentError;
ChatAttachmentStore.FILE_LIMIT = FILE_LIMIT;
ChatAttachmentStore.IMAGE_LIMIT = IMAGE_LIMIT;
ChatAttachmentStore.TYPES = TYPES;
module.exports = ChatAttachmentStore;
