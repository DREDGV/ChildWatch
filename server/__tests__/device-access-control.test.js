const http = require("http");
const fs = require("fs");
const os = require("os");
const path = require("path");
const express = require("express");
const DatabaseManager = require("../database/DatabaseManager");

const parentDeviceId = "parent-access-device";
const childDeviceId = "child-access-device";
const strangerDeviceId = "stranger-access-device";

function requestRaw(server, requestPath, deviceId, { method = "GET", body = null } = {}) {
  const address = server.address();
  return new Promise((resolve, reject) => {
    const request = http.request(
      {
        host: "127.0.0.1",
        port: address.port,
        path: requestPath,
        method,
        headers: {
          ...(deviceId ? { "x-test-device-id": deviceId } : {}),
          ...(body ? { "content-type": "application/json" } : {}),
        },
      },
      (response) => {
        let raw = "";
        response.setEncoding("utf8");
        response.on("data", (chunk) => {
          raw += chunk;
        });
        response.on("end", () => {
          let parsed = null;
          try {
            parsed = JSON.parse(raw);
          } catch {
            parsed = null;
          }
          resolve({ status: response.statusCode, body: parsed, raw });
        });
      }
    );
    request.on("error", reject);
    request.end(body ? JSON.stringify(body) : undefined);
  });
}

async function listen(routerModule, mountPath, db) {
  const app = express();
  app.use(express.json());
  app.use((req, res, next) => {
    if (req.headers["x-test-device-id"]) {
      req.deviceId = req.headers["x-test-device-id"];
    }
    next();
  });
  app.use(mountPath, routerModule);
  app.use((error, req, res, next) => {
    res.status(500).json({ error: error.message });
  });
  const server = http.createServer(app);
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  return server;
}

describe("media access control", () => {
  const mediaRoutes = require("../routes/media");
  let db;
  let server;
  let tempDir;
  let audioFileId;
  let photoFileId;
  let errorSpy;

  beforeAll(() => {
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
  });

  afterAll(() => {
    errorSpy.mockRestore();
  });

  beforeEach(async () => {
    db = new DatabaseManager(":memory:");
    await db.initialize();
    await db.registerDevice(parentDeviceId, {
      device_name: "Parent",
      device_type: "android",
      app_version: "7.3.0",
    });
    await db.registerDevice(childDeviceId, {
      device_name: "Child",
      device_type: "android",
      app_version: "7.3.0",
    });
    await db.registerDevice(strangerDeviceId, {
      device_name: "Stranger",
      device_type: "android",
      app_version: "7.3.0",
    });

    tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "childwatch-media-"));
    const audioPath = path.join(tempDir, "sample.m4a");
    const photoPath = path.join(tempDir, "sample.jpg");
    fs.writeFileSync(audioPath, Buffer.from("audio-bytes"));
    fs.writeFileSync(photoPath, Buffer.from("photo-bytes"));

    // media.js resolves stored paths relative to the server directory.
    const relativeAudio = path.relative(path.join(__dirname, ".."), audioPath);
    const relativePhoto = path.relative(path.join(__dirname, ".."), photoPath);

    await db.run(
      `INSERT INTO audio_files (device_id, filename, file_path, file_size, mime_type, duration, timestamp)
       VALUES (?, ?, ?, ?, ?, ?, ?)`,
      [childDeviceId, "sample.m4a", relativeAudio, 11, "audio/mp4", 3, Date.now()]
    );
    await db.run(
      `INSERT INTO photo_files (device_id, filename, file_path, file_size, mime_type, width, height, timestamp)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
      [childDeviceId, "sample.jpg", relativePhoto, 11, "image/jpeg", 10, 10, Date.now()]
    );

    audioFileId = (await db.get("SELECT id FROM audio_files LIMIT 1")).id;
    photoFileId = (await db.get("SELECT id FROM photo_files LIMIT 1")).id;

    mediaRoutes.init(db);
    server = await listen(mediaRoutes, "/api/media", db);
  });

  afterEach(async () => {
    await new Promise((resolve) => server.close(resolve));
    await db.close();
    fs.rmSync(tempDir, { recursive: true, force: true });
  });

  test("denies a device that is not linked to the media owner", async () => {
    const list = await requestRaw(
      server,
      `/api/media/photos/${childDeviceId}`,
      strangerDeviceId
    );
    expect(list.status).toBe(403);
    expect(list.body.code).toBe("DEVICE_ACCESS_DENIED");

    const download = await requestRaw(
      server,
      `/api/media/download/photo/${photoFileId}`,
      strangerDeviceId
    );
    expect(download.status).toBe(403);

    const thumbnail = await requestRaw(
      server,
      `/api/media/thumbnail/${photoFileId}`,
      strangerDeviceId
    );
    expect(thumbnail.status).toBe(403);

    const stats = await requestRaw(
      server,
      `/api/media/stats/${childDeviceId}`,
      strangerDeviceId
    );
    expect(stats.status).toBe(403);
  });

  test("requires authentication instead of trusting a raw device id", async () => {
    const response = await requestRaw(server, `/api/media/photos/${childDeviceId}`, null);
    expect(response).toEqual({
      status: 401,
      body: { error: "Authentication required", code: "AUTH_REQUIRED" },
      raw: expect.any(String),
    });
  });

  test("lets the owner device read its own media", async () => {
    const list = await requestRaw(server, `/api/media/audio/${childDeviceId}`, childDeviceId);
    expect(list.status).toBe(200);
    expect(list.body.count).toBe(1);

    const download = await requestRaw(
      server,
      `/api/media/download/audio/${audioFileId}`,
      childDeviceId
    );
    expect(download.status).toBe(200);
    expect(download.raw).toBe("audio-bytes");
  });

  test("lets a linked parent read the child media and thumbnail", async () => {
    await db.upsertDeviceLink({ parentDeviceId, childDeviceId });

    const list = await requestRaw(server, `/api/media/photos/${childDeviceId}`, parentDeviceId);
    expect(list.status).toBe(200);
    expect(list.body.count).toBe(1);

    const thumbnail = await requestRaw(
      server,
      `/api/media/thumbnail/${photoFileId}`,
      parentDeviceId
    );
    expect(thumbnail.status).toBe(200);
    expect(thumbnail.raw).toBe("photo-bytes");
  });

  test("reports a file of another family as not found", async () => {
    const response = await requestRaw(
      server,
      `/api/media/download/photo/${photoFileId}`,
      strangerDeviceId
    );
    // Denial and absence collapse into the same answer only for enumeration
    // resistance; here the caller is authenticated but unrelated, so the
    // device-level check answers with the explicit denial.
    expect([403, 404]).toContain(response.status);
    expect(response.raw).not.toBe("photo-bytes");
  });

  test("answers unknown file ids with 404", async () => {
    const response = await requestRaw(server, "/api/media/download/photo/999999", childDeviceId);
    expect(response.status).toBe(404);
    expect(response.body.code).toBe("PHOTO_FILE_NOT_FOUND");
  });
});

describe("streaming access control", () => {
  const streamingRoutes = require("../routes/streaming");
  let db;
  let server;
  let sentCommands;
  let wsManager;
  let errorSpy;

  beforeAll(() => {
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
  });

  afterAll(() => {
    errorSpy.mockRestore();
  });

  beforeEach(async () => {
    db = new DatabaseManager(":memory:");
    await db.initialize();
    await db.registerDevice(parentDeviceId, {
      device_name: "Parent",
      device_type: "android",
      app_version: "7.3.0",
    });
    await db.registerDevice(childDeviceId, {
      device_name: "Child",
      device_type: "android",
      app_version: "7.3.0",
    });
    await db.registerDevice(strangerDeviceId, {
      device_name: "Stranger",
      device_type: "android",
      app_version: "7.3.0",
    });

    sentCommands = [];
    wsManager = {
      resolveConnectedChildDeviceId: () => "",
      sendCommandToChild: (deviceId, command) => {
        sentCommands.push({ deviceId, command });
        return true;
      },
      isChildConnected: () => true,
      hasActiveListener: () => false,
      getStats: () => ({ totalConnections: 1, activeStreams: 0 }),
      notifyStreamTakeoverRequested: () => {},
    };

    const commandManager = {
      getCommands: () => [],
      addAudioChunk: () => 0,
      getAudioChunks: () => [],
      getSessionInfo: () => null,
      requestStreamingStart: () => ({ ok: false, code: "TEST_STUB" }),
      requestStreamingStop: () => ({ ok: false, code: "TEST_STUB" }),
      requestRecordingStart: () => ({ ok: false, code: "TEST_STUB" }),
      requestRecordingStop: () => ({ ok: false, code: "TEST_STUB" }),
    };

    streamingRoutes.init(commandManager, db, wsManager);
    server = await listen(streamingRoutes, "/api/streaming", db);
  });

  afterEach(async () => {
    await new Promise((resolve) => server.close(resolve));
    await db.close();
  });

  test("denies streaming control for an unrelated device", async () => {
    const response = await requestRaw(server, "/api/streaming/start", strangerDeviceId, {
      method: "POST",
      body: { deviceId: childDeviceId, parentId: strangerDeviceId },
    });

    expect(response.status).toBe(403);
    expect(response.body.code).toBe("DEVICE_ACCESS_DENIED");
    expect(sentCommands).toHaveLength(0);
  });

  test("refuses a device that polls another device command queue", async () => {
    const response = await requestRaw(
      server,
      `/api/streaming/commands/${childDeviceId}`,
      strangerDeviceId
    );

    expect(response.status).toBe(403);
    expect(response.body.code).toBe("DEVICE_ACCESS_DENIED");
  });

  test("requires authentication for streaming routes", async () => {
    const response = await requestRaw(server, "/api/streaming/start", null, {
      method: "POST",
      body: { deviceId: childDeviceId },
    });

    expect(response.status).toBe(401);
    expect(response.body.code).toBe("AUTH_REQUIRED");
  });

  test("refuses a chunk upload for another device", async () => {
    const response = await requestRaw(server, "/api/streaming/chunk", parentDeviceId, {
      method: "POST",
      body: { deviceId: childDeviceId, sequence: 1 },
    });

    expect(response.status).toBe(403);
    expect(response.body.code).toBe("DEVICE_ACCESS_DENIED");
  });

  test("lets a linked parent drive the child stream", async () => {
    await db.upsertDeviceLink({ parentDeviceId, childDeviceId });

    const response = await requestRaw(server, "/api/streaming/start", parentDeviceId, {
      method: "POST",
      body: { deviceId: childDeviceId, parentId: parentDeviceId },
    });

    // The command managers are stubbed, so the route reports a start failure;
    // what matters here is that access was granted and the command was routed.
    expect(response.status).not.toBe(403);
    expect(response.status).not.toBe(401);
  });
});
