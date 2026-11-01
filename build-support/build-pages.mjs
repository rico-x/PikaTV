// SPDX-License-Identifier: MIT
// Builds the project site: the pages in site/ plus a download link once a release exists.
import { cpSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const NO_RELEASE = 'Первый релиз ещё готовится.';

/** The newest published release that holds an APK, pre-releases included. */
export function newestApk(releases) {
  for (const release of releases.filter(r => !r.draft).sort((a, b) => b.published_at.localeCompare(a.published_at))) {
    const apk = release.assets.find(asset => asset.name.endsWith('.apk'));
    if (apk) return { tag: release.tag_name, url: apk.browser_download_url, prerelease: release.prerelease };
  }
  return null;
}

export function releaseBlock(apk) {
  if (!apk) return NO_RELEASE;
  return `<a class="button" href="${apk.url}">Скачать ${apk.tag}${apk.prerelease ? ' (предварительная версия)' : ''}</a>`;
}

export function render(html, apk) {
  if (!html.includes('<!-- release -->')) throw new Error('site/index.html lost its release marker');
  return html.replace('<!-- release -->', releaseBlock(apk));
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const [command, releasesFile] = process.argv.slice(2);
  if (command !== 'build') throw new Error(`Unknown command: ${command}`);
  const releases = releasesFile && existsSync(releasesFile) ? JSON.parse(readFileSync(releasesFile, 'utf8')) : [];
  rmSync('build/pages', { recursive: true, force: true });
  mkdirSync('build', { recursive: true });
  cpSync('site', 'build/pages', { recursive: true });
  writeFileSync('build/pages/index.html', render(readFileSync('site/index.html', 'utf8'), newestApk(releases)));
}
