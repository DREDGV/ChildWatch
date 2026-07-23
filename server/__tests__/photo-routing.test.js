const WebSocketManager = require("../managers/WebSocketManager");

function createHarness() {
  const sockets = new Map();
  const io = { sockets: { sockets } };
  const manager = new WebSocketManager(io);
  const parent = {
    id: "parent-socket",
    connected: true,
    parentDeviceId: "parent-1",
    parentDisplayName: "Мама",
    emit: jest.fn(),
  };
  const child = {
    id: "child-socket",
    connected: true,
    deviceId: "child-1",
    emit: jest.fn(),
  };
  sockets.set(parent.id, parent);
  sockets.set(child.id, child);
  manager.parentSockets.set(parent.id, child.deviceId);
  manager.childSockets.set(child.deviceId, child.id);
  return { manager, parent, child };
}

describe("remote photo routing", () => {
  test("treats a repeated requestId as one camera operation", () => {
    const { manager, parent, child } = createHarness();
    const request = {
      requestId: "photo-1",
      targetDevice: "child-1",
      camera: "back",
    };

    manager.handlePhotoRequest(parent, request);
    manager.handlePhotoRequest(parent, request);

    expect(
      child.emit.mock.calls.filter(([event]) => event === "request_photo")
    ).toHaveLength(1);
    expect(
      parent.emit.mock.calls.filter(([event]) => event === "photo_request_queued")
    ).toHaveLength(2);
    manager.completePhotoRequest(request.requestId);
  });

  test("does not start a second camera operation while one is active", () => {
    const { manager, parent, child } = createHarness();

    manager.handlePhotoRequest(parent, {
      requestId: "photo-1",
      targetDevice: "child-1",
      camera: "back",
    });
    manager.handlePhotoRequest(parent, {
      requestId: "photo-2",
      targetDevice: "child-1",
      camera: "front",
    });

    expect(
      child.emit.mock.calls.filter(([event]) => event === "request_photo")
    ).toHaveLength(1);
    expect(parent.emit).toHaveBeenCalledWith(
      "photo_busy",
      expect.objectContaining({
        requestId: "photo-2",
        deviceId: "child-1",
      })
    );
    manager.completePhotoRequest("photo-1");
  });

  test("accepts a response authenticated for the expected child", () => {
    const { manager, parent, child } = createHarness();
    manager.handlePhotoRequest(parent, {
      requestId: "photo-1",
      targetDevice: "child-1",
      camera: "back",
    });

    delete child.deviceId;
    child.authenticatedDeviceId = "child-1";
    manager.handlePhotoResponse(child, {
      requestId: "photo-1",
      photo: "base64-photo",
      timestamp: 123,
    });

    expect(parent.emit).toHaveBeenCalledWith("photo", {
      requestId: "photo-1",
      photo: "base64-photo",
      timestamp: 123,
    });
    expect(manager.pendingPhotoRequests.has("photo-1")).toBe(false);
    expect(manager.activePhotoRequests.has("child-1")).toBe(false);
  });

  test("fails an in-flight request immediately when the child disconnects", () => {
    const { manager, parent } = createHarness();
    manager.handlePhotoRequest(parent, {
      requestId: "photo-1",
      targetDevice: "child-1",
      camera: "back",
    });

    manager.failPhotoRequestsForChild("child-1");

    expect(parent.emit).toHaveBeenCalledWith("photo_error", {
      requestId: "photo-1",
      error: "Child device disconnected",
    });
    expect(manager.pendingPhotoRequests.has("photo-1")).toBe(false);
  });
});
