class LocationWatch {
  constructor(db, access) {
    this.db = db;
    this.access = access;
    this.watchers = new Map();
  }

  register(socket) {
    socket.on("location:watch", (request) => {
      if (!socket.authenticatedDeviceId) return;
      const familyId = typeof request?.familyId === "string" && request.familyId.length <= 128
        ? request.familyId.trim() : "";
      const devices = Array.isArray(request?.deviceIds) ? request.deviceIds.slice(0, 2)
        .filter(id => typeof id === "string" && id.length > 0 && id.length <= 256) : [];
      if (!familyId && !devices.length) {
        this.watchers.delete(socket);
        return;
      }
      this.watchers.set(socket, { familyId, devices });
    });
    socket.on("disconnect", () => this.watchers.delete(socket));
  }

  async memberships(deviceId) {
    return (await Promise.all(this.access.idForms(deviceId)
      .map(id => this.db.getFamilyIdentityMembershipsForDevice(id)))).flat();
  }

  async publish(deviceId) {
    if (!this.watchers.size) return;
    const targets = await this.memberships(deviceId);
    await Promise.all([...this.watchers].map(async ([socket, watch]) => {
      const target = targets.find(member => member.familyId === watch.familyId);
      let allowed = false;
      const matchesDevice = !watch.devices.length || watch.devices.some(id => this.access.isSameDevice(id, deviceId));
      if (target && matchesDevice) {
        const actor = (await this.memberships(socket.authenticatedDeviceId))
          .find(member => member.familyId === target.familyId);
        if (actor) {
          const permission = actor.memberId === target.memberId ? null : await this.db.getFamilyPermission({
            familyId: target.familyId, actorMemberId: actor.memberId, targetMemberId: target.memberId, feature: "LOCATION"
          });
          allowed = permission?.allowed !== 0;
        }
      } else if (!watch.familyId && watch.devices.some(id => this.access.isSameDevice(id, deviceId))) {
        allowed = (await this.access.authorizeRelatedDeviceRead(socket.authenticatedDeviceId, deviceId)).allowed;
      }
      if (allowed && socket.connected && this.watchers.get(socket) === watch) {
        socket.emit("location:changed", {});
      }
    }));
  }
}

module.exports = LocationWatch;
