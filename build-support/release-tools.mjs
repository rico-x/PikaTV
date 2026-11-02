// SPDX-License-Identifier: MIT
// Release facts live in one place: the version in app/build.gradle.kts.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = new URL('..', import.meta.url);

export function parseVersion(gradle) {
  const version = gradle.match(/versionName\s*=\s*"([^"]+)"/)?.[1];
  assert.match(version ?? '', /^\d+\.\d+\.\d+$/, 'versionName must be MAJOR.MINOR.PATCH');
  return version;
}

export const version = () => parseVersion(readFileSync(new URL('app/build.gradle.kts', root), 'utf8'));
export const apkName = v => `pikatv-v${v}.apk`;
export const docsName = v => `pikatv-v${v}-docs.zip`;

/** The release build leaves exactly one unsigned APK; CI signs that file and nothing else. */
export function unsignedApk(dir) {
  const apks = readdirSync(dir).filter(name => name.endsWith('.apk'));
  assert.equal(apks.length, 1, `expected one APK in ${dir}, found ${apks.length}`);
  assert.match(apks[0], /-unsigned\.apk$/);
  return apks[0];
}

/** Checks the APK against SHA256SUMS, so a release holds exactly the scanned file. */
export function verifyAssets(dir, v = version()) {
  const files = [apkName(v), docsName(v)];
  const expected = [...files, 'SHA256SUMS'];
  assert.deepEqual(readdirSync(dir).sort(), [...expected].sort());
  const sums = files.map(file => `${createHash('sha256').update(readFileSync(join(dir, file))).digest('hex')}  ${file}\n`).join('');
  assert.equal(readFileSync(join(dir, 'SHA256SUMS'), 'utf8'), sums);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const [command, argument] = process.argv.slice(2);
  if (command === 'version') console.log(version());
  else if (command === 'apk-name') console.log(apkName(version()));
  else if (command === 'check-apk') console.log(unsignedApk(argument));
  else if (command === 'verify-assets') verifyAssets(argument);
  else throw new Error(`Unknown command: ${command}`);
}
