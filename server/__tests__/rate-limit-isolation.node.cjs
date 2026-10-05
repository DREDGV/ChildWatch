const { test } = require('node:test');
const assert = require('node:assert/strict');
const AuthMiddleware = require('../middleware/AuthMiddleware');

test('independent endpoint budgets still enforce the same-device limit', () => {
  const auth = new AuthMiddleware({});
  const location = auth.rateLimit(60000, 2), camera = auth.rateLimit(60000, 1);
  function request(middleware, deviceId = 'same-phone') {
    let passed = false, status = 200;
    const response = { set() {}, status(code) { status = code; return this; }, json() {} };
    middleware({ deviceId, ip: '127.0.0.1' }, response, () => { passed = true; });
    return { passed, status };
  }
  assert.equal(request(location).passed, true);
  assert.equal(request(location).passed, true);
  assert.equal(request(location).status, 429);
  assert.equal(request(camera).passed, true);
  assert.equal(request(camera).status, 429);
  assert.equal(request(camera, 'another-phone').passed, true);
  assert.equal(request(location).status, 429);
});
