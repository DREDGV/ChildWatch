const fs = require('fs');
const fsp = fs.promises;
const path = require('path');
const crypto = require('crypto');
const MAX_RESOURCE_BYTES = 1024 * 1024;
const MAX_MANIFEST_BYTES = 64 * 1024;
class CatalogError extends Error {
  constructor(status, code, message = code) { super(message); this.status = status; this.code = code; }
}
class ChatMediaCatalog {
  constructor(chat, options = {}) {
    this.chat = chat;
    this.root = path.resolve(options.root || path.join(__dirname, '../assets/chat-catalog'));
  }
  async safeResource(directory, filename, limit) {
    const target = path.resolve(directory, filename);
    if (!target.startsWith(directory + path.sep)) throw new CatalogError(503, 'MEDIA_CATALOG_UNAVAILABLE');
    const stat = await fsp.lstat(target);
    if (!stat.isFile() || stat.isSymbolicLink() || stat.size <= 0 || stat.size > limit) throw new CatalogError(503, 'MEDIA_CATALOG_UNAVAILABLE');
    // No symlink directory can move a configured resource outside its canonical catalog root.
    const realRoot = await fsp.realpath(this.root), realTarget = await fsp.realpath(target);
    if (!realTarget.startsWith(realRoot + path.sep)) throw new CatalogError(503, 'MEDIA_CATALOG_UNAVAILABLE');
    const handle = await fsp.open(target, fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW || 0));
    try {
      const current = await handle.stat();
      if (!current.isFile() || current.size !== stat.size) throw new CatalogError(503, 'MEDIA_CATALOG_UNAVAILABLE');
      // Bound allocation/read even if an operator replaces or grows an asset during deployment.
      const buffer = Buffer.alloc(current.size + 1); let read = 0;
      while (read < buffer.length) {
        const result = await handle.read(buffer, read, buffer.length - read, null);
        if (!result.bytesRead) break;
        read += result.bytesRead;
      }
      if (read !== current.size || read > limit) throw new CatalogError(503, 'MEDIA_CATALOG_UNAVAILABLE');
      return buffer.subarray(0, read);
    } finally { await handle.close(); }
  }
  validateBytes(item, bytes) {
    if (bytes.length !== item.sizeBytes || crypto.createHash('sha256').update(bytes).digest('hex') !== item.sha256) throw new CatalogError(503, 'MEDIA_CATALOG_UNAVAILABLE');
    let width, height;
    if (item.type === 'STICKER') {
      if (bytes.length < 33 || !bytes.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])) || bytes.toString('ascii', 12, 16) !== 'IHDR') throw new CatalogError(503, 'MEDIA_CATALOG_UNAVAILABLE');
      width = bytes.readUInt32BE(16); height = bytes.readUInt32BE(20);
    } else {
      if (bytes.length < 14 || !/^GIF8[79]a$/.test(bytes.toString('ascii', 0, 6))) throw new CatalogError(503, 'MEDIA_CATALOG_UNAVAILABLE');
      width = bytes.readUInt16LE(6); height = bytes.readUInt16LE(8);
    }
    if (width !== item.width || height !== item.height) throw new CatalogError(503, 'MEDIA_CATALOG_UNAVAILABLE');
  }
  async load() {
    try {
      const directory = path.resolve(this.root, 'v1');
      const manifest = JSON.parse((await this.safeResource(directory, 'catalog.json', MAX_MANIFEST_BYTES)).toString('utf8'));
      if (manifest.version !== 1 || !Array.isArray(manifest.items) || manifest.items.length < 1 || manifest.items.length > 64) throw new CatalogError(503, 'MEDIA_CATALOG_UNAVAILABLE');
      const ids = new Set(), filenames = new Set(), items = [];
      for (const source of manifest.items) {
        if (!source || typeof source.id !== 'string' || !/^[a-z][a-z0-9_-]{0,63}$/.test(source.id) || ids.has(source.id) ||
            typeof source.label !== 'string' || !source.label.trim() || source.label.length > 80 || /[\x00-\x1f\x7f]/.test(source.label) ||
            !['STICKER', 'GIF'].includes(source.type) || source.mimeType !== (source.type === 'STICKER' ? 'image/png' : 'image/gif') ||
            typeof source.filename !== 'string' || !/^[a-z][a-z0-9_-]{0,63}\.(png|gif)$/.test(source.filename) || filenames.has(source.filename) ||
            !source.filename.endsWith(source.type === 'STICKER' ? '.png' : '.gif') ||
            !Number.isSafeInteger(source.sizeBytes) || source.sizeBytes <= 0 || source.sizeBytes > MAX_RESOURCE_BYTES ||
            typeof source.sha256 !== 'string' || !/^[0-9a-f]{64}$/.test(source.sha256) || source.width !== 256 || source.height !== 256) throw new CatalogError(503, 'MEDIA_CATALOG_UNAVAILABLE');
        const item = { id: source.id, label: source.label, type: source.type, mimeType: source.mimeType, sizeBytes: source.sizeBytes,
          sha256: source.sha256, filename: source.filename, width: source.width, height: source.height };
        this.validateBytes(item, await this.safeResource(directory, item.filename, MAX_RESOURCE_BYTES));
        ids.add(item.id); filenames.add(item.filename); items.push(item);
      }
      return { version: 1, items };
    } catch (error) {
      if (error instanceof CatalogError) throw error;
      throw new CatalogError(503, 'MEDIA_CATALOG_UNAVAILABLE');
    }
  }
  async capability() {
    try { const catalog = await this.load(); return { mediaCatalog: true, mediaCatalogVersion: catalog.version }; }
    catch { return { mediaCatalog: false, mediaCatalogVersion: null }; }
  }
  async assertActor(actor) {
    const current = await this.chat.resolveConversationActor(actor.deviceId, actor.conversation.id);
    if (current.memberId !== actor.memberId || current.familyId !== actor.familyId) throw new CatalogError(403, 'MEDIA_CATALOG_ACCESS_CHANGED');
  }
  async list(actor) { const catalog = await this.load(); await this.assertActor(actor); return catalog; }
  async content(actor, version, id) {
    if (version !== '1' || typeof id !== 'string' || !/^[a-z][a-z0-9_-]{0,63}$/.test(id)) throw new CatalogError(404, 'MEDIA_CATALOG_ITEM_NOT_FOUND');
    const catalog = await this.load();
    const item = catalog.items.find(item => item.id === id);
    if (!item) throw new CatalogError(404, 'MEDIA_CATALOG_ITEM_NOT_FOUND');
    try {
      const bytes = await this.safeResource(path.resolve(this.root, 'v1'), item.filename, MAX_RESOURCE_BYTES);
      this.validateBytes(item, bytes); await this.assertActor(actor);
      return { item, bytes };
    } catch (error) {
      if (error instanceof CatalogError || error instanceof require('./ChatConversationService').Error) throw error;
      throw new CatalogError(503, 'MEDIA_CATALOG_UNAVAILABLE');
    }
  }
}
ChatMediaCatalog.Error = CatalogError;
ChatMediaCatalog.MAX_RESOURCE_BYTES = MAX_RESOURCE_BYTES;
module.exports = ChatMediaCatalog;
