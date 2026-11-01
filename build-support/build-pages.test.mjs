// SPDX-License-Identifier: MIT
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { newestApk, render } from './build-pages.mjs';

const release = (tag, published_at, extra = {}) => ({ tag_name: tag, published_at, draft: false, prerelease: false,
  assets: [{ name: `pikatv-${tag}.apk`, browser_download_url: `https://example.invalid/${tag}.apk` }], ...extra });

test('without a release the site says so', () => {
  const page = render(readFileSync('site/index.html', 'utf8'), newestApk([]));
  assert.match(page, /Первый релиз ещё готовится/);
  assert.doesNotMatch(page, /<!-- release -->/);
});

test('the newest published APK is linked, drafts are not', () => {
  const apk = newestApk([release('v0.4.0', '2026-11-01T00:00:00Z'), release('v0.5.0', '2026-12-01T00:00:00Z', { prerelease: true }), release('v0.6.0', '2026-12-02T00:00:00Z', { draft: true })]);
  assert.equal(apk.tag, 'v0.5.0');
  assert.match(render('<!-- release -->', apk), /v0\.5\.0\.apk.*предварительная/);
});

test('a release without an APK is skipped', () => {
  assert.equal(newestApk([release('v0.4.0', '2026-11-01T00:00:00Z', { assets: [] })]), null);
});

test('a page without the marker stops the build', () => {
  assert.throws(() => render('<html></html>', null));
});
