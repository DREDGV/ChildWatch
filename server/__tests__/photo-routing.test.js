const PhotoDeliveryStore = require('../services/PhotoDeliveryStore');
const WebSocketManager = require("../managers/WebSocketManager");

function createHarness() {
  const sockets = new Map();
  const io = { sockets: { sockets } };
  const manager = new WebSocketManager(io, null, {});
  manager.deviceAccess = {
    authorizePhotoAccess: jest.fn().mockResolvedValue({allowed:true}),
    isSameDevice: (a,b) => a === b,
    idForms: id => [id],
  };
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
  beforeEach(() => {
    jest.spyOn(PhotoDeliveryStore.prototype, 'owner').mockResolvedValue(null);
    jest.spyOn(PhotoDeliveryStore.prototype, 'result').mockResolvedValue({status:'pending'});
    jest.spyOn(console, 'log').mockImplementation(()=>{});
    jest.spyOn(console, 'warn').mockImplementation(()=>{});
  });
  afterEach(() => jest.restoreAllMocks());
  test("treats a repeated requestId as one camera operation", async () => {
    const { manager, parent, child } = createHarness();
    const request = {
      requestId: "photo-1",
      targetDevice: "child-1",
      camera: "back",
    };

    await manager.handlePhotoRequest(parent, request);
    await manager.handlePhotoRequest(parent, request);

    expect(
      child.emit.mock.calls.filter(([event]) => event === "request_photo")
    ).toHaveLength(1);
    expect(
      parent.emit.mock.calls.filter(([event]) => event === "photo_request_queued")
    ).toHaveLength(2);
    manager.completePhotoRequest(request.requestId);
  });

  test("does not start a second camera operation while one is active", async () => {
    const { manager, parent, child } = createHarness();

    await manager.handlePhotoRequest(parent, {
      requestId: "photo-1",
      targetDevice: "child-1",
      camera: "back",
    });
    await manager.handlePhotoRequest(parent, {
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

  test("accepts a response authenticated for the expected child", async () => {
    const { manager, parent, child } = createHarness();
    await manager.handlePhotoRequest(parent, {
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

  test("fails an in-flight request immediately when the child disconnects", async () => {
    const { manager, parent } = createHarness();
    await manager.handlePhotoRequest(parent, {
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
  test('simultaneous replay delivers exactly one camera operation', async () => {
    const {manager,parent,child} = createHarness();
    const request = {requestId:'photo-race',targetDevice:'child-1',camera:'back'};
    await Promise.all([manager.handlePhotoRequest(parent,request),manager.handlePhotoRequest(parent,request)]);
    expect(child.emit.mock.calls.filter(([event]) => event === 'request_photo')).toHaveLength(1);
    expect(parent.emit.mock.calls.filter(([event]) => event === 'photo_request_queued')).toHaveLength(2);
    manager.completePhotoRequest(request.requestId);
  });
  test('socket reconnect by the same parent recovers its pending request', async () => {
    const {manager,parent,child} = createHarness();
    const request = {requestId:'photo-reconnect',targetDevice:'child-1',camera:'back'};
    await manager.handlePhotoRequest(parent,request);
    const reconnect = {...parent,id:'parent-new',emit:jest.fn()};
    manager.io.sockets.sockets.set(reconnect.id,reconnect);
    await manager.handlePhotoRequest(reconnect,request);
    expect(manager.pendingPhotoRequests.get(request.requestId).parentSocketId).toBe(reconnect.id);
    expect(child.emit.mock.calls.filter(([event]) => event === 'request_photo')).toHaveLength(1);
    manager.completePhotoRequest(request.requestId);
  });
  test('a delivered request recovers through HTTP without opening the camera', async () => {
    const {manager,parent,child} = createHarness();
    PhotoDeliveryStore.prototype.owner.mockResolvedValue('child-1');
    PhotoDeliveryStore.prototype.result.mockResolvedValue({status:'ready',photo:{id:1}});
    await manager.handlePhotoRequest(parent,{requestId:'photo-done',targetDevice:'child-1',camera:'back'});
    expect(child.emit).not.toHaveBeenCalled();
    expect(parent.emit).toHaveBeenCalledWith('photo_request_received',expect.objectContaining({requestId:'photo-done'}));
  });
  test('a stored request from another phone cannot trigger capture', async () => {
    const {manager,parent,child} = createHarness();
    PhotoDeliveryStore.prototype.owner.mockResolvedValue('another-child');
    await manager.handlePhotoRequest(parent,{requestId:'photo-foreign',targetDevice:'child-1',camera:'back'});
    expect(child.emit).not.toHaveBeenCalled();
    expect(parent.emit).toHaveBeenCalledWith('photo_error',expect.objectContaining({error:'photo_request_id_conflict'}));
  });

});
