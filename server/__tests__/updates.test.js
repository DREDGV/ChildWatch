/**
 * The release manifest and the files it names.
 *
 * These tests exist because the manifest is produced outside the server, by a
 * script, and is then read by every phone that wants to update. A manifest the
 * server cannot parse means no phone learns that a release exists, and the failure
 * is silent by design: the client says nothing when a check fails.
 *
 * They exercise the real route over HTTP rather than calling its helpers, so what
 * is checked is what a phone actually receives.
 */

const fs = require("fs");
const os = require("os");
const path = require("path");
const express = require("express");

const MANIFEST = {
  schema: 1,
  generatedAt: 1789000000000,
  apps: {
    parent: {
      packageName: "ru.example.childwatch",
      versionCode: 1789000000,
      versionName: "7.3.test",
      sizeBytes: 5,
      sha256: "a".repeat(64),
      file: "ParentMonitor-test.apk",
      signingCertSha256: "b".repeat(64),
    },
  },
};

/**
 * Starts the route over a temporary release directory.
 *
 * The directory is read when the module is first required, so it is set before
 * that happens and the whole route is loaded once for the file's tests.
 */
function startServer({ manifestText = JSON.stringify(MANIFEST), packageText = "APK!" } = {}) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "cw-releases-"));
  if (manifestText !== null) {
    fs.writeFileSync(path.join(directory, "manifest.json"), manifestText);
  }
  if (packageText !== null) {
    fs.writeFileSync(path.join(directory, "ParentMonitor-test.apk"), packageText);
  }
  process.env.CW_UPDATE_DIR = directory;

  const route = require("../routes/updates");
  const app = express();
  app.use("/updates", route);
  const server = app.listen(0);
  const base = `http://127.0.0.1:${server.address().port}`;
  return { server, base, directory, route };
}

describe("the release manifest as a phone receives it", () => {
  let running;

  beforeAll(() => {
    running = startServer();
  });
  afterAll(() => {
    running.server.close();
    delete process.env.CW_UPDATE_DIR;
  });

  test("is served and never cached", async () => {
    const response = await fetch(`${running.base}/updates/manifest`);
    expect(response.status).toBe(200);
    // A cached manifest is a functional bug, not a performance choice: it can hide
    // a release that was just published or keep announcing one already installed.
    expect(response.headers.get("cache-control")).toBe("no-store");
    expect(response.headers.get("x-content-type-options")).toBe("nosniff");
  });

  test("carries a ready-to-use address beside the file name", async () => {
    const body = await (await fetch(`${running.base}/updates/manifest`)).json();
    // A client that builds the address itself has to agree with the server about
    // the route and the name forever. Handing the address over leaves one place.
    expect(body.apps.parent.url).toBe("/updates/files/ParentMonitor-test.apk");
    expect(body.apps.parent.sha256).toBe("a".repeat(64));
  });

  test("serves the package so the installer will accept it", async () => {
    const response = await fetch(`${running.base}/updates/files/ParentMonitor-test.apk`);
    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toBe(
      "application/vnd.android.package-archive"
    );
    expect(response.headers.get("content-disposition")).toContain("attachment");
    expect(await response.text()).toBe("APK!");
  });

  test("refuses to serve anything outside the release directory", async () => {
    // Otherwise this route would become a way to read any file on the server.
    for (const attempt of [
      "/updates/files/..%2f..%2fetc%2fpasswd",
      "/updates/files/....//manifest.json",
      "/updates/files/notanapk.txt",
    ]) {
      const response = await fetch(`${running.base}${attempt}`);
      expect([400, 404]).toContain(response.status);
    }
  });

  test("answers plainly when a release file is missing", async () => {
    const response = await fetch(`${running.base}/updates/files/nope.apk`);
    expect(response.status).toBe(404);
  });
});

describe("a manifest a script might actually write", () => {
  test("is read even when it starts with a byte order mark", async () => {
    // PowerShell's Set-Content -Encoding UTF8 writes a mark, and the release script
    // is written in PowerShell. Before this was handled, the mark made JSON.parse
    // throw, the route answered 500, and no phone could ever have seen a release.
    const running = startServer({ manifestText: "\uFEFF" + JSON.stringify(MANIFEST) });
    try {
      const response = await fetch(`${running.base}/updates/manifest`);
      expect(response.status).toBe(200);
      const body = await response.json();
      expect(body.apps.parent.versionCode).toBe(1789000000);
    } finally {
      running.server.close();
      delete process.env.CW_UPDATE_DIR;
    }
  });

  test("is reported as absent rather than as an error", async () => {
    const running = startServer({ manifestText: null });
    try {
      const response = await fetch(`${running.base}/updates/manifest`);
      expect(response.status).toBe(404);
      const body = await response.json();
      expect(body.code).toBe("UPDATE_MANIFEST_MISSING");
    } finally {
      running.server.close();
      delete process.env.CW_UPDATE_DIR;
    }
  });
});
