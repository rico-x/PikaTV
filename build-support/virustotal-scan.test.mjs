// SPDX-License-Identifier: MIT
import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createClient, markdown, reportLink, scan, verdict } from './virustotal-scan.mjs';

const clean = { malicious: 0, suspicious: 0, undetected: 60, harmless: 0 };
const sha = bytes => createHash('sha256').update(bytes).digest('hex');

/** A fake API with a clock: records requests, answers from small tables. */
function fakeApi({ known = {}, analyses = {}, rateLimited = 0, conflict = false, reportAfter = null, analysisMissing = 0, uploadBody } = {}) {
  let clock = 0;
  const calls = [];
  let limit = rateLimited;
  let missing = analysisMissing;
  const reply = (status, body) => ({ status, ok: status < 300, json: async () => body });
  const fetch = async (url, init = {}) => {
    calls.push({ at: clock, method: init.method ?? 'GET', path: url.replace('https://www.virustotal.com/api/v3', ''), key: init.headers['x-apikey'] });
    if (limit > 0) { limit--; return reply(429, {}); }
    const [, kind, id] = url.split('/api/v3')[1].split('/');
    if (kind === 'files' && init.method === 'POST') return conflict ? reply(409, {}) : reply(200, uploadBody ?? { data: { id: `analysis-${calls.length}` } });
    if (kind === 'files') {
      const stats = known[id] ?? (reportAfter !== null && clock >= reportAfter ? clean : null);
      return stats ? reply(200, { data: { attributes: { last_analysis_date: 1, last_analysis_stats: stats } } }) : reply(404, {});
    }
    if (missing-- > 0) return reply(404, {});
    const queue = analyses[id] ?? [clean];
    if (queue.length > 1) { queue.shift(); return reply(200, { data: { attributes: { status: 'queued' } } }); }
    return reply(200, { data: { attributes: { status: 'completed', stats: queue[0] } } });
  };
  const sleep = async ms => { clock += ms; };
  return { fetch, sleep, now: () => clock, calls, advance: ms => { clock += ms; } };
}

test('known files are looked up by hash, new ones uploaded and awaited', async () => {
  const known = Buffer.from('known apk');
  const api = fakeApi({ known: { [sha(known)]: clean } });
  const client = createClient({ apiKey: 'test-key', ...api });
  const results = await scan([{ file: 'b.apk', bytes: known }, { file: 'a.apk', bytes: Buffer.from('new apk') }], client,
    { now: api.now, log: () => {} });
  assert.deepEqual(results.map(r => r.file), ['a.apk', 'b.apk']);
  assert.deepEqual(api.calls.map(c => `${c.method} ${c.path.split('/')[1]}`), ['GET files', 'GET files', 'POST files', 'GET analyses']);
  assert.ok(api.calls.every(c => c.key === 'test-key'));
});

test('requests stay within four a minute and wait out a 429', async () => {
  const api = fakeApi({ rateLimited: 1 });
  const client = createClient({ apiKey: 'k', ...api });
  await scan([{ file: 'a.apk', bytes: Buffer.from('a') }, { file: 'b.apk', bytes: Buffer.from('b') }], client, { now: api.now, log: () => {} });
  const gaps = api.calls.slice(1).map((c, i) => c.at - api.calls[i].at);
  assert.ok(gaps.every(g => g >= 15_000), `gaps ${gaps}`);
  assert.ok(gaps[0] >= 60_000, 'Waited a minute after the 429');
});

test('a queued analysis is polled until done; past the deadline nothing passes', async () => {
  const slow = fakeApi({ analyses: { 'analysis-2': [{}, {}, clean] } });
  const results = await scan([{ file: 'a.apk', bytes: Buffer.from('x') }], createClient({ apiKey: 'k', ...slow }),
    { now: slow.now, log: () => {} });
  assert.equal(results[0].stats, clean);
  const stuck = fakeApi({ analyses: { 'analysis-2': Array(100).fill({}) } });
  await assert.rejects(scan([{ file: 'a.apk', bytes: Buffer.from('x') }], createClient({ apiKey: 'k', ...stuck }),
    { now: stuck.now, timeoutMs: 120_000, log: () => {} }), /did not finish in time/);
});

test('a scan without real results never passes as clean', () => {
  for (const stats of [{}, null, undefined, { failure: 76 }, { timeout: 3, 'type-unsupported': 5 }, { harmless: 'many' }, { ...clean, undetected: -1 }])
    assert.equal(verdict(stats).blocked, true, JSON.stringify(stats));
  const table = markdown([{ file: 'a.apk', sha256: 'a'.repeat(64), stats: { failure: 76 } }]);
  assert.match(table, /проверка не завершена/);
  assert.doesNotMatch(table, /чисто/);
});

test('a just-uploaded analysis may be missing before it is indexed', async () => {
  const api = fakeApi({ analysisMissing: 1 });
  const results = await scan([{ file: 'a.apk', bytes: Buffer.from('new apk') }], createClient({ apiKey: 'k', ...api }),
    { now: api.now, log: () => {} });
  assert.equal(results[0].stats, clean);
  assert.equal(api.calls.filter(c => c.path.startsWith('/analyses/')).length, 2);
  assert.ok(api.calls.filter(c => c.path.startsWith('/files/')).every(c => c.path === `/files/${sha(Buffer.from('new apk'))}`));
});

test('an existing pending upload is awaited by its exact hash, without repeated uploads', async () => {
  const bytes = Buffer.from('pending apk');
  const api = fakeApi({ conflict: true, reportAfter: 90_000 });
  const results = await scan([{ file: 'a.apk', bytes }], createClient({ apiKey: 'k', ...api }),
    { now: api.now, log: () => {} });
  assert.equal(results[0].stats, clean);
  assert.equal(api.calls.filter(c => c.method === 'POST').length, 1);
  assert.equal(api.calls.filter(c => c.path.startsWith('/analyses/')).length, 0);
  assert.ok(api.calls.filter(c => c.method === 'GET').every(c => c.path === `/files/${sha(bytes)}`));
});

test('a missing analysis can be completed through the report for the uploaded bytes', async () => {
  const api = fakeApi({ analysisMissing: 100, reportAfter: 90_000 });
  const results = await scan([{ file: 'a.apk', bytes: Buffer.from('a') }], createClient({ apiKey: 'k', ...api }),
    { now: api.now, log: () => {} });
  assert.equal(results[0].stats, clean);
});

test('missing analyses and pending conflicts fail closed at the deadline', async () => {
  for (const options of [{ conflict: true }, { analysisMissing: 100 }]) {
    const api = fakeApi(options);
    await assert.rejects(scan([{ file: 'a.apk', bytes: Buffer.from('a') }], createClient({ apiKey: 'k', ...api }),
      { now: api.now, timeoutMs: 120_000, log: () => {} }), /did not finish in time/);
    assert.ok(api.now() >= 120_000 && api.now() < 180_000);
  }
});

test('authentication failures and malformed uploads do not become pending scans', async () => {
  const api = fakeApi();
  const denied = createClient({ apiKey: 'k', ...api, fetch: async () => ({ status: 401, ok: false }) });
  await assert.rejects(scan([{ file: 'a.apk', bytes: Buffer.from('a') }], denied, { now: api.now, log: () => {} }), /HTTP 401/);
  const malformed = fakeApi({ uploadBody: { data: {} } });
  await assert.rejects(scan([{ file: 'a.apk', bytes: Buffer.from('a') }], createClient({ apiKey: 'k', ...malformed }),
    { now: malformed.now, log: () => {} }), /no analysis ID/);
});

test('analysis IDs are URL encoded before querying', async () => {
  const api = fakeApi({ uploadBody: { data: { id: 'analysis/a+b=' } } });
  const results = await scan([{ file: 'a.apk', bytes: Buffer.from('a') }], createClient({ apiKey: 'k', ...api }),
    { now: api.now, log: () => {} });
  assert.equal(results[0].stats, clean);
  assert.ok(api.calls.some(c => c.path === '/analyses/analysis%2Fa%2Bb%3D'));
});

test('engines count only finished scans and the rest are named', () => {
  const stats = { ...clean, failure: 2, timeout: 1, 'type-unsupported': 4 };
  assert.deepEqual([verdict(stats).engines, verdict(stats).unfinished, verdict(stats).blocked], [60, 7, false]);
  assert.match(markdown([{ file: 'a.apk', sha256: 'a'.repeat(64), stats }]), /чисто, 60 антивирусов, не проверили: 7/);
});

test('any malicious verdict blocks by default; suspicious only shows', () => {
  assert.equal(verdict(clean).blocked, false);
  assert.equal(verdict({ ...clean, suspicious: 2 }).blocked, false);
  assert.equal(verdict({ ...clean, malicious: 1 }).blocked, true);
  assert.equal(verdict({ ...clean, malicious: 1 }, 1).blocked, false, 'The owner may accept a known false positive');
  assert.equal(verdict(clean).engines, 60);
});

test('the release table links every report and never shows the key', () => {
  const hash = 'a'.repeat(64);
  const table = markdown([{ file: 'pikatv-v1.0.0.apk', sha256: hash, stats: { ...clean, suspicious: 1 } }]);
  assert.ok(table.includes(reportLink(hash)));
  assert.ok(table.includes('чисто, 61 антивирусов, подозрений: 1'));
  assert.throws(() => createClient({ apiKey: '' }), /VIRUSTOTAL_API_KEY/);
});
