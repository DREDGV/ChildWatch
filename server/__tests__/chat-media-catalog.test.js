const fs = require('fs');
const path = require('path');
const http = require('http');
const express = require('express');
const crypto = require('crypto');
const zlib = require('zlib');
const Database = require('../database/DatabaseManager');
const Chat = require('../services/ChatConversationService');
const routes = require('../routes/chat-v2');
jest.setTimeout(30000);
function crc32(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) { crc ^= byte; for (let i = 0; i < 8; i++) crc = crc >>> 1 ^ (crc & 1 ? 0xedb88320 : 0); }
  return (crc ^ 0xffffffff) >>> 0;
}
function png() {
  const chunk = (type, data) => {
    const payload = Buffer.concat([Buffer.from(type), data]), result = Buffer.alloc(payload.length + 8);
    result.writeUInt32BE(data.length); payload.copy(result, 4); result.writeUInt32BE(crc32(payload), result.length - 4); return result;
  };
  const header = Buffer.alloc(13); header.writeUInt32BE(256); header.writeUInt32BE(256, 4); header[8] = 8; header[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', header),
    chunk('IDAT', zlib.deflateSync(Buffer.alloc(256 * (1 + 256 * 4)))), chunk('IEND', Buffer.alloc(0))]);
}
const sticker = png();
const gif = Buffer.from('R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7', 'base64');
gif.writeUInt16LE(256, 6); gif.writeUInt16LE(256, 8);
function item(id, type, bytes) { return { id, label: id === 'hug' ? 'Обнимаю' : 'Ура!', type, mimeType: type === 'STICKER' ? 'image/png' : 'image/gif',
  sizeBytes: bytes.length, sha256: crypto.createHash('sha256').update(bytes).digest('hex'), filename: id + (type === 'STICKER' ? '.png' : '.gif'), width: 256, height: 256 }; }
function request(server, uri, device, method = 'GET', bytes, contentType) {
  return new Promise((resolve, reject) => {
    const req = http.request({ host: '127.0.0.1', port: server.address().port, path: uri, method,
      headers: { 'x-test-device-id': device, ...(bytes ? { 'content-type': contentType, 'content-length': bytes.length } : {}) } }, res => {
      const chunks = []; res.on('data', chunk => chunks.push(chunk)); res.on('end', () => {
        const data = Buffer.concat(chunks); let body; try { body = JSON.parse(data.toString()); } catch {}
        resolve({ status: res.statusCode, body, data, headers: res.headers });
      });
    }); req.on('error', reject); req.end(bytes);
  });
}
async function family(db, suffix) {
  const parent = 'catalog-parent-' + suffix, child = 'catalog-child-' + suffix;
  for (const id of [parent, child]) await db.registerDevice(id, { device_name: id, device_type: 'android' });
  await db.upsertDeviceLink({ parentDeviceId: parent, childDeviceId: child, parentDisplayName: parent, childDisplayName: child, createdBy: 'catalog-fixture' });
  const [family] = await db.getFamiliesForDevice(parent);
  return { parent, child, family, member: await db.getFamilyDeviceMembership(family.id, parent) };
}
describe('own media catalog: private current conversation access and immutable checked assets', () => {
  let root, db, chat, server, primary, other, conversation, directory, manifest, spies;
  const catalogUrl = () => `/api/chat/v2/conversations/${conversation}/media-catalog`;
  const resourceUrl = (id = 'hug', version = '1') => `${catalogUrl()}/${version}/${id}/content`;
  function writeManifest() { fs.writeFileSync(path.join(directory, 'catalog.json'), JSON.stringify(manifest)); }
  beforeEach(async () => {
    spies = ['log', 'warn', 'error'].map(key => jest.spyOn(console, key).mockImplementation(() => {}));
    root = fs.mkdtempSync(path.join(__dirname, '../../.runtime/chat-catalog-test-'));
    directory = path.join(root, 'catalog', 'v1'); fs.mkdirSync(directory, { recursive: true });
    manifest = { version: 1, items: [item('hug', 'STICKER', sticker), item('yay', 'GIF', gif)] };
    fs.writeFileSync(path.join(directory, 'hug.png'), sticker); fs.writeFileSync(path.join(directory, 'yay.gif'), gif); writeManifest();
    db = new Database(path.join(root, 'test.db')); await db.initialize(); primary = await family(db, 'main'); other = await family(db, 'other');
    chat = new Chat(db, { mediaCatalog: { root: path.join(root, 'catalog') }, attachments: { root: path.join(root, 'attachments') },
      transcriptions: { root: path.join(root, 'transcriptions'), autoStart: false } });
    conversation = (await chat.listConversations(primary.parent)).conversations.find(c => c.type === 'FAMILY').conversationId;
    const app = express(); app.use(express.json()); app.use((req, res, next) => { req.deviceId = req.headers['x-test-device-id']; next(); });
    app.use('/api/chat/v2', routes(db, chat)); server = http.createServer(app); await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  });
  afterEach(async () => {
    chat.transcriptions.stop(); await new Promise(resolve => server.close(resolve)); await db.close();
    fs.rmSync(root, { recursive: true, force: true }); spies.forEach(s => s.mockRestore());
  });
  test('capabilities/version/checksums and exact PNG/GIF content accessible privately to current participants', async () => {
    expect((await request(server, '/api/chat/v2/capabilities', primary.parent)).body).toMatchObject({ mediaCatalog: true, mediaCatalogVersion: 1 });
    const catalog = await request(server, catalogUrl(), primary.child); expect(catalog.status).toBe(200);
    expect(catalog.body).toEqual({ success: true, ...manifest }); expect(catalog.headers['cache-control']).toBe('private, no-store');
    for (const [id, bytes, mime] of [['hug', sticker, 'image/png'], ['yay', gif, 'image/gif']]) {
      const content = await request(server, resourceUrl(id), primary.child); expect(content.status).toBe(200);
      expect(content.data).toEqual(bytes); expect(content.headers['content-type']).toContain(mime);
      expect(content.headers['x-content-sha256']).toBe(crypto.createHash('sha256').update(bytes).digest('hex'));
      expect(content.headers['cache-control']).toBe('private, no-store');
    }
  });
  test('other family and revoked member cannot read metadata or content', async () => {
    expect((await request(server, catalogUrl(), other.parent)).status).toBe(403);
    expect((await request(server, resourceUrl(), other.parent)).status).toBe(403);
    await db.run('UPDATE family_devices SET is_active=0 WHERE device_id=?', [primary.parent]);
    expect((await request(server, catalogUrl(), primary.parent)).status).toBe(403);
    expect((await request(server, resourceUrl(), primary.parent)).status).toBe(403);
  });
  test('revocation during resource loading is rechecked before sending', async () => {
    const original = chat.mediaCatalog.load.bind(chat.mediaCatalog);
    chat.mediaCatalog.load = async () => { const catalog = await original(); await db.run('UPDATE family_devices SET is_active=0 WHERE device_id=?', [primary.parent]); return catalog; };
    expect((await request(server, resourceUrl(), primary.parent)).status).toBe(403);
  });
  test('unknown version/item and encoded path traversal do not expose a file', async () => {
    for (const uri of [resourceUrl('hug', '2'), resourceUrl('missing'), resourceUrl('%2e%2e%2fcatalog.json'), resourceUrl('hug', '%2e%2e')]) {
      expect((await request(server, uri, primary.parent)).status).toBe(404);
    }
  });
  test('wrong bytes/hash disable capabilities and fail closed even after previous successful load', async () => {
    expect((await request(server, resourceUrl(), primary.parent)).status).toBe(200);
    fs.writeFileSync(path.join(directory, 'hug.png'), Buffer.alloc(sticker.length));
    expect((await request(server, '/api/chat/v2/capabilities', primary.parent)).body).toMatchObject({ mediaCatalog: false, mediaCatalogVersion: null });
    expect((await request(server, catalogUrl(), primary.parent)).status).toBe(503);
    expect((await request(server, resourceUrl(), primary.parent)).status).toBe(503);
  });
  test('missing, duplicate, oversized, unsafe filename, false dimension and MIME metadata fail closed', async () => {
    for (const mutate of [m => { m.version = 2; }, m => { m.items.push(m.items[0]); }, m => { m.items[0].filename = '../secret.png'; },
      m => { m.items[0].sizeBytes = 1048577; }, m => { m.items[0].width = 300; }, m => { m.items[0].mimeType = 'text/html'; }]) {
      const original = manifest; manifest = JSON.parse(JSON.stringify(manifest)); mutate(manifest); writeManifest();
      expect((await request(server, '/api/chat/v2/capabilities', primary.parent)).body.mediaCatalog).toBe(false);
      manifest = original; writeManifest();
    }
    fs.unlinkSync(path.join(directory, 'yay.gif'));
    expect((await request(server, catalogUrl(), primary.parent)).status).toBe(503);
    fs.unlinkSync(path.join(directory, 'catalog.json'));
    expect((await request(server, '/api/chat/v2/capabilities', primary.parent)).body.mediaCatalog).toBe(false);
  });
  async function upload(bytes = sticker, id = 'sticker-draft') {
    const boundary = 'catalog-upload';
    const body = Buffer.concat([Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="attachmentType"\r\n\r\nSTICKER\r\n--${boundary}\r\nContent-Disposition: form-data; name="clientUploadId"\r\n\r\n${id}\r\n--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="hug.png"\r\nContent-Type: image/png\r\n\r\n`), bytes, Buffer.from(`\r\n--${boundary}--\r\n`)]);
    return request(server, `/api/chat/v2/conversations/${conversation}/attachments`, primary.parent, 'POST', body, 'multipart/form-data; boundary=' + boundary);
  }
  test('STICKER PNG upload and typed send remain idempotent; non-PNG sticker is rejected', async () => {
    const first = await upload(); expect(first.status).toBe(201); expect(first.body.attachment).toMatchObject({ type: 'STICKER', mimeType: 'image/png' });
    expect((await upload()).body.attachment.attachmentId).toBe(first.body.attachment.attachmentId);
    expect((await upload(gif, 'not-png')).status).toBe(415);
    const message = Buffer.from(JSON.stringify({ clientMessageId: 'sticker-message', messageType: 'STICKER', text: '', attachmentIds: [first.body.attachment.attachmentId] }));
    const uri = `/api/chat/v2/conversations/${conversation}/messages`;
    const sent = await request(server, uri, primary.parent, 'POST', message, 'application/json');
    expect(sent.status).toBe(201); expect(sent.body.message).toMatchObject({ messageType: 'STICKER', mediaFallback: true });
    const retry = await request(server, uri, primary.parent, 'POST', message, 'application/json');
    expect(retry.status).toBe(200); expect(retry.body.message.messageId).toBe(sent.body.message.messageId);
    const content = await request(server, `/api/chat/v2/conversations/${conversation}/attachments/${first.body.attachment.attachmentId}/content`, primary.child);
    expect(content.status).toBe(200); expect(content.data).toEqual(sticker);
    expect((await db.get('SELECT COUNT(*) count FROM chat_message_media')).count).toBe(1);
  });
});
