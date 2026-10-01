const DatabaseManager = require('../database/DatabaseManager');
const PhotoDeliveryStore = require('../services/PhotoDeliveryStore');
describe('durable remote photo delivery', () => {
  let db, store;
  const captureTime = 1790904000000;
  const metadata = name => ({filename:name,file_path:'uploads/'+name,file_size:12,mime_type:'image/jpeg',timestamp:captureTime,width:null,height:null});
  beforeEach(async () => {
    jest.spyOn(console,'log').mockImplementation(()=>{});
    db = new DatabaseManager(':memory:'); await db.initialize();
    await db.registerDevice('photo-child',{device_name:'Child',device_type:'android'});
    await db.registerDevice('foreign-child',{device_name:'Other',device_type:'android'});
    store = new PhotoDeliveryStore(db); await store.ensure();
  });
  afterEach(async () => { await db.close(); jest.restoreAllMocks(); });
  test('retry and concurrent upload produce one gallery item with original capture time', async () => {
    await Promise.all([store.save('photo-child','capture-1',metadata('a.jpg')),store.save('photo-child','capture-1',metadata('retry.jpg'))]);
    expect(await db.getPhotoFiles('photo-child')).toHaveLength(1);
    const reopened = new PhotoDeliveryStore(db);
    expect(await reopened.result(['photo-child'],'capture-1')).toMatchObject({status:'ready',photo:{filename:'a.jpg',timestamp:captureTime}});
    expect(await reopened.result(['foreign-child'],'capture-1')).toEqual({status:'pending'});
  });
  test('queued upload recovers without recapture and a late failure cannot erase its result', async () => {
    await store.fail('photo-child','capture-2','photo_upload_queued');
    expect(await store.result(['photo-child'],'capture-2')).toMatchObject({status:'uploading'});
    await store.save('photo-child','capture-2',metadata('b.jpg'));
    await store.fail('photo-child','capture-2','camera_permission_denied');
    expect(await store.result(['photo-child'],'capture-2')).toMatchObject({status:'ready'});
  });
  test('request ownership survives failure and rejects a different device', async () => {
    await store.fail('photo-child','capture-3','photo_upload_queued');
    await expect(store.save('foreign-child','capture-3',metadata('foreign.jpg'))).rejects.toMatchObject({status:403});
    await expect(store.fail('foreign-child','capture-3','fake')).rejects.toMatchObject({status:403});
    expect(await db.getPhotoFiles('foreign-child')).toHaveLength(0);
  });
  test('failed result insertion rolls gallery metadata back', async () => {
    const original = db.run.bind(db);
    jest.spyOn(db,'run').mockImplementation((sql,args) => sql.startsWith('INSERT INTO photo_deliveries') ? Promise.reject(new Error('disk full')) : original(sql,args));
    await expect(store.save('photo-child','capture-4',metadata('rollback.jpg'))).rejects.toThrow('disk full');
    expect(await db.getPhotoFiles('photo-child')).toHaveLength(0);
  });
  test('gallery pages honor offset and have stable order for equal timestamps', async () => {
    for(let index=0;index<9;index++) await store.save('photo-child','page-'+index,metadata('page-'+index+'.jpg'));
    const first = await db.getPhotoFiles('photo-child',6,0), second = await db.getPhotoFiles('photo-child',6,6);
    expect(first).toHaveLength(6); expect(second).toHaveLength(3);
    expect(new Set([...first,...second].map(file=>file.id)).size).toBe(9);
    expect([...first,...second].map(file=>file.filename)).toEqual(Array.from({length:9},(_,i)=>'page-'+(8-i)+'.jpg'));
  });
});
