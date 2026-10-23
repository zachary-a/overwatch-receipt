const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const asset = fs.readFileSync(path.join(__dirname, '../../main/assets/official_role_capture.js'), 'utf8');
const body = '{"code":0,"result":{"accounts":[{"account":999999999999999999,"nickname":"玩家#1234"}]}}';

function browser(origin) {
  let now = 0, id = 0;
  const tasks = new Map(), messages = [], requests = [];
  const state = {signedIn: false, failure: false, opened: 0};
  class Xhr { open() {} send() {} addEventListener() {} }
  const window = {XMLHttpRequest: Xhr}; window.top = window; window.window = window;
  Object.assign(window, {
    location: {origin, href: origin + '/home'},
    document: {visibilityState: 'visible', cookie: '',
      querySelector: () => ({click: () => state.opened++}), querySelectorAll: () => []},
    localStorage: {getItem: () => null},
    ReceiptAuthBridge: {postMessage: message => messages.push(JSON.parse(message))},
    fetch: async (url, options) => {
      requests.push({url, options});
      if (state.failure) throw new Error('offline');
      const text = url.includes('/mine/userInfo') ? JSON.stringify({code: state.signedIn ? 200 : 825}) : body;
      const response = {status: 200, text: async () => text, clone: () => response, headers: {get: () => null}};
      return response;
    },
    setTimeout: (fn, delay) => { tasks.set(++id, {fn, time: now + delay}); return id; },
    clearTimeout: id => tasks.delete(id), URL, AbortController,
  });
  vm.runInNewContext(asset, window);
  async function tick() {
    const next = [...tasks].sort((a,b) => a[1].time - b[1].time)[0];
    assert.ok(next, 'discovery must be scheduled');
    tasks.delete(next[0]); now = next[1].time; await next[1].fn();
    await new Promise(resolve => setImmediate(resolve));
  }
  return {window, state, messages, requests, tasks, tick};
}

test('official login is confirmed before reading Blizzard bindings; IDs stay exact', async () => {
  const b = browser('https://ds.163.com');
  await b.tick();
  assert.equal(b.state.opened, 1);
  assert.equal(b.requests.filter(r => r.url.includes('blizzard')).length, 0);
  b.state.signedIn = true;
  b.window.__owReceiptReadRoles(); await b.tick();
  assert.ok(b.messages.some(m => m.kind === 'siteStatus' && m.ready));
  assert.equal(b.requests.at(-1).url, 'https://payms.ds.163.com/v1/plutusuinfo/blizzard/accounts?game_code=ld5');
  assert.equal(b.messages.find(m => m.kind === 'roles').body, body);
  assert.equal(b.state.opened, 1);
});
test('reread on logged-in recharge home queries current cookies without navigating', async () => {
  const b = browser('https://pay.ds.163.com');
  const location = b.window.location.href;
  await b.tick(); b.window.__owReceiptReadRoles(); await b.tick();
  assert.equal(b.requests.length, 2);
  assert.ok(b.requests.every(r => r.url.includes('/blizzard/accounts?game_code=ld5')));
  assert.ok(b.requests.every(r => r.options.credentials === 'include'));
  assert.equal(b.window.location.href, location);
  assert.equal(b.tasks.size, 0, 'a completed read must not keep polling forever');
});
test('network failure emits an actionable result and retry can recover', async () => {
  const b = browser('https://pay.ds.163.com'); b.state.failure = true;
  await b.tick();
  assert.ok(b.messages.some(m => m.kind === 'discoveryError' && m.stage === 'roles'));
  assert.equal(b.tasks.size, 0);
  b.state.failure = false; b.window.__owReceiptReadRoles(); await b.tick();
  assert.ok(b.messages.some(m => m.kind === 'roles'));
});
