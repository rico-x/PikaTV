// SPDX-License-Identifier: MIT
// Checks release APKs on VirusTotal before they are published. A file VirusTotal
// already knows is looked up by its SHA-256; a new one is uploaded and its
// analysis awaited. The public API allows four requests a minute, so every
// request waits its turn. The API key comes from VIRUSTOTAL_API_KEY and is
// never printed.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const API = 'https://www.virustotal.com/api/v3';
const MAX_UPLOAD_BYTES = 32 * 1024 * 1024;

export const reportLink = sha256 => `https://www.virustotal.com/gui/file/${sha256}`;

/** Engines that really examined the file; failures, timeouts and unsupported types say nothing about it. */
const COMPLETED = ['harmless', 'undetected', 'suspicious', 'malicious'];

/** "Suspicious" is shown but does not block a release; neither does anything short of a real result pass as clean. */
export function verdict(stats, maxMalicious = 0) {
  const entries = Object.entries(stats ?? {});
  const wellFormed = entries.every(([, n]) => Number.isInteger(n) && n >= 0);
  const count = selected => entries.filter(([name]) => selected(name)).reduce((sum, [, n]) => sum + n, 0);
  const malicious = count(name => name === 'malicious');
  const suspicious = count(name => name === 'suspicious');
  const engines = count(name => COMPLETED.includes(name));
  const unfinished = count(name => !COMPLETED.includes(name));
  return { malicious, suspicious, engines, unfinished, blocked: !wellFormed || engines === 0 || malicious > maxMalicious };
}

export function markdown(results) {
  const rows = results.map(r => {
    const v = verdict(r.stats);
    const state = v.malicious ? `⚠️ ${v.malicious} из ${v.engines}` : v.engines ? `чисто, ${v.engines} антивирусов` : '⚠️ проверка не завершена';
    const extra = (v.suspicious ? `, подозрений: ${v.suspicious}` : '') + (v.unfinished ? `, не проверили: ${v.unfinished}` : '');
    return `| \`${r.file}\` | [${state}${extra}](${reportLink(r.sha256)}) |`;
  });
  return ['| Файл | VirusTotal |', '|---|---|', ...rows].join('\n') + '\n';
}

export function createClient({ apiKey, fetch = globalThis.fetch, sleep, now = Date.now, gapMs = 15_500 }) {
  assert.ok(apiKey, 'VIRUSTOTAL_API_KEY is required');
  sleep ??= ms => new Promise(r => setTimeout(r, ms));
  let nextAt = 0;
  async function request(path, init = {}) {
    for (let attempt = 1; ; attempt++) {
      const wait = nextAt - now();
      if (wait > 0) await sleep(wait);
      nextAt = now() + gapMs;
      const response = await fetch(`${API}${path}`, { ...init, headers: { 'x-apikey': apiKey, ...init.headers } });
      if (response.status !== 429 || attempt === 5) return response;
      await sleep(60_000);  // quota exceeded: the minute window has to pass
    }
  }
  async function json(path, init, pendingStatuses = []) {
    const response = await request(path, init);
    if (pendingStatuses.includes(response.status)) return null;
    assert.ok(response.ok, `VirusTotal ${init?.method ?? 'GET'} ${path.split('/')[1]} failed: HTTP ${response.status}`);
    return response.json();
  }
  return {
    /** The latest finished analysis of a known file, or null. */
    async lookup(sha256) {
      const body = await json(`/files/${sha256}`, undefined, [404]);
      const attributes = body?.data?.attributes;
      return attributes?.last_analysis_date ? attributes.last_analysis_stats : null;
    },
    async upload(name, bytes) {
      assert.ok(bytes.length <= MAX_UPLOAD_BYTES, `${name} is larger than the direct upload limit`);
      const form = new FormData();
      form.append('file', new Blob([bytes]), name);
      // A previous upload can already exist before its finished report is available.
      const body = await json('/files', { method: 'POST', body: form }, [409]);
      if (body === null) return null;
      const id = body?.data?.id;
      assert.ok(typeof id === 'string' && id.length, 'VirusTotal upload returned no analysis ID');
      return id;
    },
    /** Polls until the analysis finishes; null if it is still queued at the deadline. */
    async analysis(id, deadline, sha256) {
      while (now() < deadline) {
        const body = id === null ? null : await json(`/analyses/${encodeURIComponent(id)}`, undefined, [404]);
        if (body !== null) {
          const attributes = body.data.attributes;
          if (attributes.status === 'completed') return attributes.stats;
        } else if (sha256) {
          // A newly created analysis may not be indexed yet. Only a finished
          // report for these exact bytes can replace the missing analysis.
          const stats = await this.lookup(sha256);
          if (stats) return stats;
        }
        await sleep(30_000);
      }
      return null;
    },
  };
}

export async function scan(files, client, { now = Date.now, timeoutMs = 25 * 60_000, log = console.log } = {}) {
  const deadline = now() + timeoutMs;
  const results = [];
  const pending = [];
  for (const { file, bytes } of files) {
    const sha256 = createHash('sha256').update(bytes).digest('hex');
    const stats = await client.lookup(sha256);
    if (stats) {
      log(`${file}: already analysed`);
      results.push({ file, sha256, stats });
    } else {
      log(`${file}: uploading`);
      pending.push({ file, sha256, id: await client.upload(file, bytes) });
    }
  }
  for (const item of pending) {
    const stats = await client.analysis(item.id, deadline, item.sha256);
    assert.ok(stats, `${item.file}: VirusTotal did not finish in time; nothing is published unscanned`);
    results.push({ file: item.file, sha256: item.sha256, stats });
  }
  return results.sort((a, b) => a.file.localeCompare(b.file, 'en'));
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [directory, output = 'virustotal'] = process.argv.slice(2);
  try {
    assert.ok(directory, 'Usage: virustotal-scan.mjs <apk directory> [output prefix]');
    const maxMalicious = Number(process.env.VIRUSTOTAL_MAX_MALICIOUS ?? 0);
    assert.ok(Number.isInteger(maxMalicious) && maxMalicious >= 0, 'VIRUSTOTAL_MAX_MALICIOUS must be a whole number');
    const files = readdirSync(directory).filter(f => f.endsWith('.apk')).sort()
      .map(file => ({ file, bytes: readFileSync(join(directory, file)) }));
    assert.ok(files.length, 'No APKs to scan');
    assert.ok(files.every(f => statSync(join(directory, f.file)).isFile()));
    const results = await scan(files, createClient({ apiKey: process.env.VIRUSTOTAL_API_KEY }));
    writeFileSync(`${output}.json`, JSON.stringify(results.map(r => ({ ...r, link: reportLink(r.sha256) })), null, 2) + '\n');
    writeFileSync(`${output}.md`, markdown(results));
    let blocked = false;
    for (const r of results) {
      const v = verdict(r.stats, maxMalicious);
      console.log(`${r.file}: malicious ${v.malicious}, suspicious ${v.suspicious}, engines ${v.engines} ${reportLink(r.sha256)}`);
      if (v.blocked && v.malicious <= maxMalicious) { blocked = true; console.log(`::error::${r.file} has no finished VirusTotal scan`); }
      else if (v.blocked) { blocked = true; console.log(`::error::${r.file} is flagged by ${v.malicious} engines on VirusTotal`); }
    }
    if (blocked) process.exitCode = 1;
  } catch (error) {
    console.error(`VirusTotal scan failed: ${error.message}`);
    process.exitCode = 1;
  }
}
