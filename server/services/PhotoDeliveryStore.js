const stores = new WeakMap();
class PhotoDeliveryStore {
  constructor(db) { this.db=db; this.queue=Promise.resolve(); this.ready=null; }
  ensure() {
    if (!this.ready) this.ready=this.db.run(`CREATE TABLE IF NOT EXISTS photo_deliveries (
      request_id TEXT PRIMARY KEY, device_id TEXT NOT NULL, filename TEXT NOT NULL, captured_at INTEGER NOT NULL
    )`).then(() => this.db.run(`CREATE TABLE IF NOT EXISTS photo_capture_failures (
      request_id TEXT PRIMARY KEY, device_id TEXT NOT NULL, error TEXT NOT NULL, created_at INTEGER NOT NULL
    )`)).catch(error => { this.ready=null; throw error; });
    return this.ready;
  }
  save(deviceId, requestId, metadata) {
    const work=this.queue.then(async () => {
      await this.ensure();
      return this.db.withTransaction(async () => {
      if (requestId) {
        const previous=await this.db.get('SELECT * FROM photo_deliveries WHERE request_id=?',[requestId]);
        if (previous) {
          if (previous.device_id !== deviceId) throw Object.assign(new Error('Photo request belongs to another device'),{status:403});
          return {filename:previous.filename, capturedAt:previous.captured_at, reused:true};
        }
      }
      if (requestId) {
        const failed = await this.db.get('SELECT device_id FROM photo_capture_failures WHERE request_id=?',[requestId]);
        if (failed && failed.device_id !== deviceId) throw Object.assign(new Error('Photo request belongs to another device'),{status:403});
      }
      await this.db.savePhotoFile(deviceId,metadata);
      if (requestId) await this.db.run('INSERT INTO photo_deliveries(request_id,device_id,filename,captured_at) VALUES(?,?,?,?)',
        [requestId,deviceId,metadata.filename,metadata.timestamp]);
      return {filename:metadata.filename,capturedAt:metadata.timestamp,reused:false};
      });
    });
    this.queue=work.catch(()=>{}); return work;
  }
  fail(deviceId, requestId, error) {
    const work = this.queue.then(async () => {
      await this.ensure();
      const delivered = await this.db.get('SELECT device_id FROM photo_deliveries WHERE request_id=?',[requestId]);
      if (delivered) {
        if (delivered.device_id !== deviceId) throw Object.assign(new Error('Photo request belongs to another device'),{status:403});
        return; // A late failure never downgrades a delivered image.
      }
      const failed = await this.db.get('SELECT device_id FROM photo_capture_failures WHERE request_id=?',[requestId]);
      if (failed && failed.device_id !== deviceId) throw Object.assign(new Error('Photo request belongs to another device'),{status:403});
      await this.db.run(`INSERT INTO photo_capture_failures(request_id,device_id,error,created_at) VALUES(?,?,?,?)
        ON CONFLICT(request_id) DO UPDATE SET error=excluded.error,created_at=excluded.created_at`,
        [requestId,deviceId,error.slice(0,200),Date.now()]);
    });
    this.queue = work.catch(()=>{}); return work;
  }
  async owner(requestId) {
    await this.ensure();
    const row = await this.db.get(`SELECT device_id FROM photo_deliveries WHERE request_id=?
      UNION SELECT device_id FROM photo_capture_failures WHERE request_id=? LIMIT 1`,[requestId,requestId]);
    return row?.device_id || null;
  }
  async result(deviceIds, requestId) {
    await this.ensure();
    const marks=deviceIds.map(()=>'?').join(',');
    const saved=await this.db.get(`SELECT f.* FROM photo_deliveries d JOIN photo_files f ON f.filename=d.filename AND f.device_id=d.device_id
      WHERE d.request_id=? AND d.device_id IN (${marks})`,[requestId,...deviceIds]);
    if (saved) return {status:'ready',photo:{id:saved.id,filename:saved.filename,fileSize:saved.file_size,mimeType:saved.mime_type,
      timestamp:saved.timestamp,width:saved.width,height:saved.height,createdAt:saved.created_at,requestId,
      downloadUrl:`/api/media/download/photo/${saved.id}`,thumbnailUrl:`/api/media/thumbnail/${saved.id}`}};
    const failed=await this.db.get(`SELECT error FROM photo_capture_failures WHERE request_id=? AND device_id IN (${marks})`,[requestId,...deviceIds]);
    if (failed?.error === 'photo_upload_queued') return {status:'uploading',error:failed.error};
    return failed ? {status:'error',error:failed.error} : {status:'pending'};
  }
  async attach(files) {
    await this.ensure();
    if (!files.length) return files;
    const rows=await this.db.all(`SELECT request_id,filename FROM photo_deliveries WHERE filename IN (${files.map(()=>'?').join(',')})`,files.map(f=>f.filename));
    const requests=new Map(rows.map(r=>[r.filename,r.request_id]));
    return files.map(file=>({...file,request_id:requests.get(file.filename)||null}));
  }
  static forDatabase(db) { if (!stores.has(db)) stores.set(db,new PhotoDeliveryStore(db)); return stores.get(db); }
}
module.exports=PhotoDeliveryStore;
