// SPDX-License-Identifier: MIT
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { apkName, parseVersion, unsignedApk, verifyAssets } from './release-tools.mjs';

const dir = () => mkdtempSync(join(tmpdir(), 'pikatv-release-'));

test('version comes from the build file and must be plain semver', () => {
  assert.equal(parseVersion('versionName = "0.4.1"'), '0.4.1');
  assert.throws(() => parseVersion('versionName = "0.4"'));
  assert.throws(() => parseVersion('nothing here'));
});

test('exactly one unsigned APK is accepted', () => {
  const folder = dir();
  writeFileSync(join(folder, 'app-release-unsigned.apk'), 'apk');
  assert.equal(unsignedApk(folder), 'app-release-unsigned.apk');
  writeFileSync(join(folder, 'other.apk'), 'apk');
  assert.throws(() => unsignedApk(folder));
});

test('assets must match their checksum and hold nothing else', () => {
  const folder = dir();
  const name = apkName('1.2.3');
  writeFileSync(join(folder, name), 'signed');
  const sum = createHash('sha256').update('signed').digest('hex');
  writeFileSync(join(folder, 'SHA256SUMS'), `${sum}  ${name}\n`);
  verifyAssets(folder, '1.2.3');
  writeFileSync(join(folder, name), 'changed');
  assert.throws(() => verifyAssets(folder, '1.2.3'));
  writeFileSync(join(folder, name), 'signed');
  writeFileSync(join(folder, 'extra.txt'), 'x');
  assert.throws(() => verifyAssets(folder, '1.2.3'));
});
