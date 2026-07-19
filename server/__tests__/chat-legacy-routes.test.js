const http = require("http");
const express = require("express");
const DatabaseManager = require("../database/DatabaseManager");
const FamilyPermissionService = require("../services/FamilyPermissionService");
const createChatRoutes = require("../routes/chat");

function requestJson(server, { method = "GET", path, deviceId, body }) {
  const encodedBody = body === undefined ? null : JSON.stringify(body);
  return new Promise((resolve, reject) => {
    const request = http.request(
      {
        host: "127.0.0.1",
        port: server.address().port,
        method,
        path,
        headers: {
          "x-test-device-id": deviceId,
          ...(encodedBody === null
            ? {}
            : {
                "content-type": "application/json",
                "content-length": Buffer.byteLength(encodedBody),
              }),
        },
      },
      (response) => {
        let raw = "";
        response.setEncoding("utf8");
        response.on("data", (chunk) => (raw += chunk));
        response.on("end", () =>
          resolve({
            status: response.statusCode,
            body: raw ? JSON.parse(raw) : null,
          })
        );
      }
    );
    request.on("error", reject);
    if (encodedBody !== null) request.write(encodedBody);
    request.end();
  });
}

async function registerFamily(db, suffix) {
  const parent = `legacy-parent-${suffix}-0001`;
  const child = `legacy-child-${suffix}-0001`;
  await db.registerDevice(parent, {
    device_name: `Parent ${suffix}`,
    device_type: "android",
    app_version: "8.0.0",
  });
  await db.registerDevice(child, {
    device_name: `Child ${suffix}`,
    device_type: "android",
    app_version: "8.0.0",
  });
  await db.upsertDeviceLink({
    parentDeviceId: parent,
    childDeviceId: child,
    parentDisplayName: `Parent ${suffix}`,
    childDisplayName: `Child ${suffix}`,
    createdBy: "legacy-route-test",
  });
  return { parent, child };
}

describe("legacy chat HTTP compatibility routes", () => {
  let db;
  let server;
  let first;
  let second;
  let logSpy;
  let errorSpy;

  beforeEach(async () => {
    logSpy = jest.spyOn(console, "log").mockImplementation(() => {});
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    db = new DatabaseManager(":memory:");
    await db.initialize();
    first = await registerFamily(db, "first");
    second = await registerFamily(db, "second");

    const app = express();
    app.use(express.json({ limit: "64kb" }));
    app.use((req, _res, next) => {
      req.deviceId = req.headers["x-test-device-id"];
      next();
    });
    app.use(
      "/api/chat",
      createChatRoutes(db, new FamilyPermissionService(db))
    );
    server = http.createServer(app);
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  });

  afterEach(async () => {
    await new Promise((resolve) => server.close(resolve));
    await db.close();
    logSpy.mockRestore();
    errorSpy.mockRestore();
  });

  test("derives sender from authentication and preserves Unicode whitespace", async () => {
    const text = "  Семья 👨‍👩‍👧‍👦 👍🏽  ";
    const response = await requestJson(server, {
      method: "POST",
      path: "/api/chat/messages",
      deviceId: first.parent,
      body: {
        deviceId: first.child,
        sender: "child",
        message: text,
        timestamp: 1_784_300_000_000,
        id: "legacy-auth-derived-1",
      },
    });

    expect(response.status).toBe(200);
    const [stored] = await db.getChatMessages(first.child, 10, 0);
    expect(stored.sender).toBe("parent");
    expect(stored.sender_device_id).toBe(first.parent);
    expect(stored.message).toBe(text);
  });

  test("denies cross-family history, send and read access", async () => {
    const send = await requestJson(server, {
      method: "POST",
      path: "/api/chat/messages",
      deviceId: first.parent,
      body: {
        deviceId: second.child,
        sender: "parent",
        message: "secret",
        id: "cross-family-message",
      },
    });
    expect(send.status).toBe(403);

    const history = await requestJson(server, {
      path: `/api/chat/messages/${second.child}`,
      deviceId: first.parent,
    });
    expect(history.status).toBe(403);

    const saved = await db.saveChatMessage(second.child, {
      sender: "child",
      message: "private",
      timestamp: Date.now(),
      id: "private-second-family",
    });
    const read = await requestJson(server, {
      method: "PUT",
      path: `/api/chat/messages/${saved.id}/read`,
      deviceId: first.parent,
    });
    expect(read.status).toBe(403);
  });

  test("rejects oversized UTF-8 text and never reopens the shared database", async () => {
    const initializeSpy = jest.spyOn(db, "initialize");
    const closeSpy = jest.spyOn(db, "close");
    const response = await requestJson(server, {
      method: "POST",
      path: "/api/chat/messages",
      deviceId: first.parent,
      body: {
        deviceId: first.child,
        message: "😀".repeat(4097),
        id: "oversized-legacy-message",
      },
    });

    expect(response.status).toBe(413);
    expect(response.body.code).toBe("MESSAGE_TEXT_TOO_LARGE");
    expect(initializeSpy).not.toHaveBeenCalled();
    expect(closeSpy).not.toHaveBeenCalled();
    initializeSpy.mockRestore();
    closeSpy.mockRestore();
  });
});
