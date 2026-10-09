const http = require("http");
const express = require("express");
const DatabaseManager = require("../database/DatabaseManager");
const createChatV2Routes = require("../routes/chat-v2");
jest.setTimeout(30_000);
function requestJson(server, {
  method = "GET",
  path,
  deviceId,
  body
}) {
  const address = server.address();
  const encodedBody = body === undefined ? null : JSON.stringify(body);
  return new Promise((resolve, reject) => {
    const request = http.request({
      host: "127.0.0.1",
      port: address.port,
      method,
      path,
      headers: {
        "x-test-device-id": deviceId,
        ...(encodedBody === null ? {} : {
          "content-type": "application/json",
          "content-length": Buffer.byteLength(encodedBody)
        })
      }
    }, response => {
      let responseBody = "";
      response.setEncoding("utf8");
      response.on("data", chunk => {
        responseBody += chunk;
      });
      response.on("end", () => {
        resolve({
          status: response.statusCode,
          body: responseBody ? JSON.parse(responseBody) : null
        });
      });
    });
    request.on("error", reject);
    if (encodedBody !== null) request.write(encodedBody);
    request.end();
  });
}
async function registerFamily(db, suffix, {
  thirdMember = false
} = {}) {
  const parentDeviceId = `chat-parent-${suffix}-0001`;
  const childDeviceId = `chat-child-${suffix}-0001`;
  const thirdDeviceId = `chat-parent-${suffix}-0002`;
  await db.registerDevice(parentDeviceId, {
    device_name: `Parent ${suffix}`,
    device_type: "android",
    app_version: "8.0.0"
  });
  await db.registerDevice(childDeviceId, {
    device_name: `Child ${suffix}`,
    device_type: "android",
    app_version: "8.0.0"
  });
  await db.upsertDeviceLink({
    parentDeviceId,
    childDeviceId,
    parentDisplayName: `Parent ${suffix}`,
    childDisplayName: `Child ${suffix}`,
    createdBy: "chat-v2-route-test"
  });
  if (thirdMember) {
    await db.registerDevice(thirdDeviceId, {
      device_name: `Second parent ${suffix}`,
      device_type: "android",
      app_version: "8.0.0"
    });
    await db.upsertDeviceLink({
      parentDeviceId: thirdDeviceId,
      childDeviceId,
      parentDisplayName: `Second parent ${suffix}`,
      childDisplayName: `Child ${suffix}`,
      createdBy: "chat-v2-route-test"
    });
  }
  const [family] = await db.getFamiliesForDevice(parentDeviceId);
  const membershipFor = async deviceId => db.getFamilyDeviceMembership(family.id, deviceId);
  return {
    family,
    parentDeviceId,
    parentMembership: await membershipFor(parentDeviceId),
    childDeviceId,
    childMembership: await membershipFor(childDeviceId),
    thirdDeviceId: thirdMember ? thirdDeviceId : null,
    thirdMembership: thirdMember ? await membershipFor(thirdDeviceId) : null
  };
}
const fs = require('fs'),
  pathModule = require('path'),
  crypto = require('crypto');
const ChatService = require('../services/ChatConversationService');
const Store = require('../services/ChatAttachmentStore');
function rawRequest(server, p, deviceId, body = null, contentType = null, method = 'GET') {
  return new Promise((resolve, reject) => {
    const req = http.request({
      host: '127.0.0.1',
      port: server.address().port,
      path: p,
      method,
      headers: {
        'x-test-device-id': deviceId,
        ...(body ? {
          'content-type': contentType,
          'content-length': body.length
        } : {})
      }
    }, res => {
      const chunks = [];
      res.on('data', c => chunks.push(c));
      res.on('end', () => {
        const data = Buffer.concat(chunks);
        let json = null;
        try {
          json = JSON.parse(data.toString());
        } catch {}
        resolve({
          status: res.statusCode,
          body: json,
          data,
          headers: res.headers
        });
      });
    });
    req.on('error', reject);
    req.end(body);
  });
}
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aV6cAAAAASUVORK5CYII=', 'base64');
describe('private chat attachments, real SQLite and HTTP', () => {
  let db, server, primary, secondary, service, root, c, spies;
  async function start() {
    service = new ChatService(db, {
      attachments: {
        root: pathModule.join(root, 'blobs')
      }
    });
    const app = express();
    app.use(express.json());
    app.use((req, res, next) => {
      req.deviceId = req.headers['x-test-device-id'];
      next();
    });
    app.use('/api/chat/v2', createChatV2Routes(db, service));
    server = http.createServer(app);
    await new Promise(r => server.listen(0, '127.0.0.1', r));
  }
  beforeEach(async () => {
    spies = ['log', 'error', 'warn'].map(k => jest.spyOn(console, k).mockImplementation(() => {}));
    root = fs.mkdtempSync(pathModule.join(__dirname, '../../.runtime/chat-media-test-'));
    db = new DatabaseManager(pathModule.join(root, 'test.db'));
    await db.initialize();
    primary = await registerFamily(db, 'media-primary', {
      thirdMember: true
    });
    secondary = await registerFamily(db, 'media-other');
    await start();
    const list = await requestJson(server, {
      path: '/api/chat/v2/conversations',
      deviceId: primary.parentDeviceId
    });
    c = list.body.conversations.find(c => c.type === 'FAMILY').conversationId;
  });
  afterEach(async () => {
    await new Promise(r => server.close(r));
    await db.close();
    fs.rmSync(root, {
      recursive: true,
      force: true
    });
    spies.forEach(s => s.mockRestore());
  });
  async function upload({
    device = primary.parentDeviceId,
    conversation = c,
    type = 'IMAGE',
    bytes = png,
    id = 'draft-1',
    filename = 'photo.png'
  } = {}) {
    const boundary = 'cw-boundary';
    const fields = {
      attachmentType: type,
      clientUploadId: id
    };
    const text = Object.entries(fields).map(([k, v]) => `--${boundary}\r\nContent-Disposition: form-data; name="${k}"\r\n\r\n${v}\r\n`).join('');
    const body = Buffer.concat([Buffer.from(text + `--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="${filename}"\r\nContent-Type: image/png\r\n\r\n`), bytes, Buffer.from(`\r\n--${boundary}--\r\n`)]);
    return rawRequest(server, `/api/chat/v2/conversations/${conversation}/attachments`, device, body, `multipart/form-data; boundary=${boundary}`, 'POST');
  }
  async function send(a, {
    id = 'message-1',
    device = primary.parentDeviceId,
    conversation = c,
    type = a.type
  } = {}) {
    return requestJson(server, {
      path: `/api/chat/v2/conversations/${conversation}/messages`,
      method: 'POST',
      deviceId: device,
      body: {
        clientMessageId: id,
        messageType: type,
        text: '',
        attachmentIds: [a.attachmentId]
      }
    });
  }
  const content = a => `/api/chat/v2/conversations/${c}/attachments/${a}/content`;
  test('capabilities, exact bytes, typed send, duplicate and TEXT compatibility', async () => {
    expect((await requestJson(server, {
      path: '/api/chat/v2/capabilities',
      deviceId: primary.parentDeviceId
    })).body).toMatchObject({
      attachments: true,
      maxFileBytes: 26214400,
      maxImageBytes: 10485760
    });
    const u = await upload();
    expect(u.status).toBe(201);
    const a = u.body.attachment;
    expect(a.sha256).toBe(crypto.createHash('sha256').update(png).digest('hex'));
    expect((await upload()).body.attachment.attachmentId).toBe(a.attachmentId);
    expect((await rawRequest(server, content(a.attachmentId), primary.childDeviceId)).status).toBe(403);
    const sent = await send(a);
    expect(sent.status).toBe(201);
    expect(sent.body.message).toMatchObject({
      messageType: 'IMAGE',
      text: '[Вложение: photo.png]',
      mediaFallback: true,
      attachments: [a]
    });
    expect((await db.get('SELECT text FROM chat_messages_v2 WHERE id=?', [sent.body.message.messageId])).text).toBe('');
    const again = await send(a);
    expect(again.status).toBe(200);
    expect(again.body.message.messageId).toBe(sent.body.message.messageId);
    const got = await rawRequest(server, content(a.attachmentId), primary.childDeviceId);
    expect(got.status).toBe(200);
    expect(got.data.equals(png)).toBe(true);
    expect(got.headers['cache-control']).toBe('private, no-store');
    const text = await requestJson(server, {
      path: `/api/chat/v2/conversations/${c}/messages`,
      method: 'POST',
      deviceId: primary.childDeviceId,
      body: {
        clientMessageId: 'text-old',
        text: 'hello'
      }
    });
    expect(text.body.message).toMatchObject({
      messageType: 'TEXT',
      attachments: []
    });
    expect((await db.get('SELECT COUNT(*) n FROM chat_messages_v2 WHERE client_message_id=?', ['message-1'])).n).toBe(1);
  });
  test('foreign family, wrong conversation and another sender cannot bind or read', async () => {
    const a = (await upload()).body.attachment;
    expect((await upload({
      device: secondary.parentDeviceId
    })).status).toBe(403);
    expect((await rawRequest(server, content(a.attachmentId), secondary.parentDeviceId)).status).toBe(403);
    expect((await send(a, {
      device: primary.childDeviceId
    })).status).toBe(403);
    const direct = await service.createDirectConversation(primary.parentDeviceId, primary.childMembership.memberId);
    expect((await send(a, {
      conversation: direct.conversation.conversationId
    })).status).toBe(403);
    expect((await db.get('SELECT COUNT(*) n FROM chat_messages_v2')).n).toBe(0);
  });
  test('database and process restart preserve drafts and idempotency', async () => {
    const a = (await upload()).body.attachment;
    await new Promise(r => server.close(r));
    await db.close();
    db = new DatabaseManager(pathModule.join(root, 'test.db'));
    await db.initialize();
    await start();
    expect((await upload()).body.attachment.attachmentId).toBe(a.attachmentId);
    const sent = await send(a);
    expect(sent.status).toBe(201);
    await new Promise(r => server.close(r));
    await start();
    expect((await send(a)).body.message.messageId).toBe(sent.body.message.messageId);
    expect((await rawRequest(server, content(a.attachmentId), primary.childDeviceId)).status).toBe(200);
  });
  test('delete for me keeps bytes; withdraw denies everyone and physically collects', async () => {
    const a = (await upload()).body.attachment;
    const sent = await send(a);
    const base = `/api/chat/v2/conversations/${c}/messages/${sent.body.message.messageId}`;
    expect((await requestJson(server, {
      path: base,
      method: 'DELETE',
      deviceId: primary.childDeviceId
    })).status).toBe(200);
    expect((await rawRequest(server, content(a.attachmentId), primary.parentDeviceId)).status).toBe(200);
    expect(fs.existsSync(service.attachments.blob(a.attachmentId))).toBe(true);
    expect((await requestJson(server, {
      path: base + '?forEveryone=true',
      method: 'DELETE',
      deviceId: primary.childDeviceId
    })).status).toBe(403);
    const withdrawn = await requestJson(server, {
      path: base + '?forEveryone=true',
      method: 'DELETE',
      deviceId: primary.parentDeviceId
    });
    expect(withdrawn.body.message).toMatchObject({
      messageType: 'IMAGE',
      attachments: []
    });
    expect((await rawRequest(server, content(a.attachmentId), primary.parentDeviceId)).status).toBe(404);
    expect(fs.existsSync(service.attachments.blob(a.attachmentId))).toBe(false);
  });
  test('cancel, spoofed MIME, actual byte limits and upload conflict', async () => {
    const a = (await upload()).body.attachment;
    expect((await requestJson(server, {
      path: `/api/chat/v2/conversations/${c}/attachments/${a.attachmentId}`,
      method: 'DELETE',
      deviceId: primary.parentDeviceId
    })).status).toBe(200);
    expect((await send(a)).status).toBe(409);
    expect(fs.existsSync(service.attachments.blob(a.attachmentId))).toBe(false);
    expect((await upload({
      id: 'fake',
      bytes: Buffer.from('not image')
    })).status).toBe(415);
    expect((await upload({
      id: 'large',
      bytes: Buffer.alloc(Store.IMAGE_LIMIT + 1)
    })).status).toBe(413);
    await upload({
      id: 'stable'
    });
    expect((await upload({
      id: 'stable',
      type: 'FILE'
    })).status).toBe(409);
  });
  test('revoked participant loses read; bounded GC cleans stale and interrupted drafts', async () => {
    const a = (await upload()).body.attachment;
    await send(a);
    await db.run('UPDATE chat_conversation_members SET is_active=0 WHERE conversation_id=? AND member_id=?', [c, primary.childMembership.memberId]);
    expect((await rawRequest(server, content(a.attachmentId), primary.childDeviceId)).status).toBe(403);
    const draft = (await upload({
      id: 'orphan'
    })).body.attachment;
    await db.run('UPDATE chat_attachments SET created_at=? WHERE id=?', [Date.now() - 25 * 3600000, draft.attachmentId]);
    const temp = pathModule.join(service.attachments.temp, 'interrupted.part');
    fs.writeFileSync(temp, 'partial');
    fs.utimesSync(temp, new Date(0), new Date(0));
    await service.attachments.gc();
    expect(fs.existsSync(service.attachments.blob(draft.attachmentId))).toBe(false);
    expect(fs.existsSync(temp)).toBe(false);
    expect(fs.existsSync(service.attachments.blob(a.attachmentId))).toBe(true);
  });
  test('direct and group FILE attachments keep original bytes and use safe download MIME', async () => {
    const direct = await service.createDirectConversation(primary.parentDeviceId, primary.childMembership.memberId);
    const group = await service.createGroup(primary.parentDeviceId, {
      title: 'Media group',
      memberIds: [primary.childMembership.memberId, primary.thirdMembership.memberId]
    });
    for (const [index, conversation] of [direct.conversation.conversationId, group.conversation.conversationId].entries()) {
      const bytes = Buffer.from('original document bytes ' + index);
      const a = (await upload({
        type: 'FILE',
        bytes,
        id: 'file-' + index,
        conversation,
        filename: 'document.txt'
      })).body.attachment;
      expect(a.mimeType).toBe('application/octet-stream');
      expect((await send(a, {
        id: 'doc-' + index,
        conversation
      })).status).toBe(201);
      const got = await rawRequest(server, `/api/chat/v2/conversations/${conversation}/attachments/${a.attachmentId}/content`, primary.childDeviceId);
      expect(got.status).toBe(200);
      expect(got.data.equals(bytes)).toBe(true);
      expect(got.headers['content-disposition'].startsWith('attachment;')).toBe(true);
    }
  });
  test('committed cancel is refused, attachment reuse rolls back message, and staged bytes remain private', async () => {
    const a = (await upload()).body.attachment;
    await send(a);
    expect((await requestJson(server, {
      path: `/api/chat/v2/conversations/${c}/attachments/${a.attachmentId}`,
      method: 'DELETE',
      deviceId: primary.parentDeviceId
    })).status).toBe(409);
    expect((await send(a, {
      id: 'illegal-reuse'
    })).status).toBe(409);
    expect((await db.get('SELECT COUNT(*) n FROM chat_messages_v2 WHERE client_message_id=?', ['illegal-reuse'])).n).toBe(0);
    const missing = await requestJson(server, {
      path: `/api/chat/v2/conversations/${c}/messages`,
      method: 'POST',
      deviceId: primary.parentDeviceId,
      body: {
        clientMessageId: 'invalidmedia',
        messageType: 'IMAGE',
        text: '',
        attachmentIds: []
      }
    });
    expect(missing.status).toBe(400);
    expect(fs.readdirSync(service.attachments.temp)).toEqual([]);
  });
  test('bounded cleanup resumes beyond the first hundred directory entries', async () => {
    await service.attachments.gc();
    const names = [];
    for (let index = 0; index < 205; index++) {
      const name = pathModule.join(service.attachments.temp, `abandoned-${index}.part`);
      fs.writeFileSync(name, 'partial');
      fs.utimesSync(name, new Date(0), new Date(0));
      names.push(name);
    }
    await service.attachments.gc();
    expect(names.filter(name => fs.existsSync(name)).length).toBeGreaterThan(0);
    for (let pass = 0; pass < 5; pass++) await service.attachments.gc();
    expect(names.some(name => fs.existsSync(name))).toBe(false);
  });

});
