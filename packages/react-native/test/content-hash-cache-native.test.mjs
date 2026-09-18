import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, readdirSync, readFileSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

const root = resolve(import.meta.dirname, '..');
function run(command, args) {
  const result = spawnSync(command, args, { encoding: 'utf8', timeout: 60_000 });
  assert.equal(result.status, 0, `${result.error ?? ''}\n${result.stdout}\n${result.stderr}`);
  return result.stdout;
}

test('Swift executes production content-cache copy and verification', { skip: process.platform !== 'darwin' && 'Requires macOS Swift/CryptoKit' }, () => {
  const dir = mkdtempSync(join(tmpdir(), 'nitropush-cache-swift-'));
  try {
    const binary = join(dir, 'cache');
    run('swiftc', ['-module-cache-path', join(dir, 'modules'), join(root, 'ios/NPContentHashCache.swift'), join(root, 'test/native/ContentHashCacheDriver.swift'), '-o', binary]);
    assert.match(run(binary, []), /path safety passed/);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('Kotlin executes production content-cache copy and verification', t => {
  const cache = join(homedir(), '.gradle/caches/modules-2/files-2.1');
  function jar(group, name, version) {
    const dir = join(cache, group, name, version);
    for (const hash of readdirSync(dir)) for (const file of readdirSync(join(dir, hash)))
      if (file === `${name}-${version}.jar`) return join(dir, hash, file);
    throw new Error(`Missing cached ${name}`);
  }
  let compiler, stdlib;
  try {
    stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.10');
    compiler = ['kotlin-compiler-embeddable', 'kotlin-stdlib', 'kotlin-script-runtime', 'kotlin-reflect', 'kotlin-daemon-embeddable']
      .map(name => jar('org.jetbrains.kotlin', name, '2.2.10'));
    compiler.push(jar('org.jetbrains', 'annotations', '13.0'));
    compiler.push(jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'));
  } catch { t.skip('Kotlin 2.2.10 compiler cache unavailable; run this check in Android CI'); return; }
  const dir = mkdtempSync(join(tmpdir(), 'nitropush-cache-kotlin-'));
  try {
    const artifact = join(dir, 'cache.jar');
    run('java', ['-cp', compiler.join(':'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', stdlib,
      join(root, 'android/src/main/java/com/nitropush/sdk/NPContentHashCache.kt'), join(root, 'test/native/ContentHashCacheDriver.kt'), '-d', artifact]);
    assert.match(run('java', ['-cp', `${artifact}:${stdlib}`, 'com.nitropush.sdk.ContentHashCacheDriverKt']), /path safety passed/);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('native bundle and file-delta paths are behind the verified target-cache lookup', () => {
  const ios = readFileSync(join(root, 'ios/NitroPushSdk.swift'), 'utf8');
  const android = readFileSync(join(root, 'android/src/main/java/com/nitropush/sdk/NitroPushSdk.kt'), 'utf8');
  assert.match(ios, /let cachedBundleSize = try copyCachedContentHash\([\s\S]*if cachedBundleSize == nil, enableDeltaUpdates/);
  assert.match(ios, /if cachedBundleSize == nil && !usedDelta \{\s*usedDelta = await tryFileDelta/);
  assert.match(ios, /if let cachedSize = try copyCachedContentHash\([\s\S]*installedBytes \+= cachedSize\s*\} else if await tryFileDelta/);
  assert.match(android, /val cachedBundleSize = copyCachedContentHash\([\s\S]*val canUseDelta = cachedBundleSize == null && enableDeltaUpdates/);
  assert.match(android, /if \(cachedBundleSize == null && !usedDelta\) usedDelta = tryFileDelta/);
  assert.match(android, /installedBytes \+= copyCachedContentHash\([\s\S]*\?: if \(tryFileDelta/);
  assert.match(ios, /var installedBytes = cachedBundleSize \?\? 0/);
  assert.match(android, /var installedBytes = cachedBundleSize \?: 0L/);
  for (const source of [ios, android]) {
    assert.match(source, /installedBytes <= (?:Self\.)?maxReleaseBytes/);
    assert.match(source, /NPContentHashCache\.copyVerified/);
  }
});
