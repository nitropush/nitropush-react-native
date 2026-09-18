import assert from 'node:assert/strict';
import test from 'node:test';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, readdirSync, rmSync, symlinkSync } from 'node:fs';
import { tmpdir, homedir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
const require = createRequire(import.meta.url);
const { inventory, writeInventory, INDEX_NAME } = require('../scripts/embedded-assets.cjs');
const root = resolve(import.meta.dirname, '..');
function fixture(callback) {
  const directory = mkdtempSync(join(tmpdir(), 'np-embedded-inventory-'));
  try { return callback(directory); } finally { rmSync(directory, { recursive: true, force: true }); }
}
function put(directory, relative, bytes) { const file = join(directory, relative); mkdirSync(resolve(file, '..'), { recursive: true }); writeFileSync(file, bytes); return file; }
function run(command, args) {
  const result = spawnSync(command, args, { encoding: 'utf8', timeout: 60_000 });
  assert.equal(result.status, 0, `${result.error ?? ''}\n${result.stdout}\n${result.stderr}`);
  return result.stdout;
}
test('iOS inventory indexes final Metro files by actual bytes and excludes its own output', () => fixture(directory => {
  put(directory, 'assets/images/logo.png', 'image'); put(directory, 'main.jsbundle', 'hermes');
  put(directory, 'Info.plist', 'not an OTA asset');
  const first = writeInventory({ platform: 'ios', bundleRoot: directory });
  const second = writeInventory({ platform: 'ios', bundleRoot: directory });
  assert.equal(first.json, second.json);
  assert.equal(first.value.entries.length, 2);
  assert(first.value.entries.some(entry => entry.sha256 === createHash('sha256').update('image').digest('hex') && entry.path === 'assets/images/logo.png'));
}));
test('Android inventory retains density-specific drawable/raw descriptors and assets paths', () => fixture(directory => {
  const assets = join(directory, 'assets'), resources = join(directory, 'res');
  put(assets, 'index.android.bundle', 'hermes'); put(assets, 'custom.bin', 'asset');
  put(resources, 'drawable-mdpi/assets_icon.png', 'one'); put(resources, 'drawable-xhdpi/assets_icon.png', 'two');
  put(resources, 'raw/font.ttf', 'font'); put(resources, 'raw/keep.xml', 'keep');
  const result = inventory({ platform: 'android', assetsRoot: assets, resourcesRoot: resources });
  assert.equal(result.value.entries.length, 5);
  assert.deepEqual(result.value.entries.filter(entry => entry.resourceName === 'assets_icon').map(entry => entry.density).sort((a,b) => a-b), [160,320]);
  assert(result.value.entries.some(entry => entry.resourceType === 'raw' && entry.resourceName === 'font' && entry.density === 0));
}));
test('NativeScript uses immutable packaged app paths and a real repeatable CLI', () => fixture(directory => {
  put(directory, 'app/package.json', '{}'); put(directory, 'app/bundle.js', 'compiled app'); put(directory, 'app/images/logo.png', 'image');
  run(process.execPath, [join(root, 'scripts/embedded-assets.cjs'), '--platform', 'nativescript-android', '--app-root', join(directory, 'app')]);
  const index = JSON.parse(readFileSync(join(directory, 'app', INDEX_NAME), 'utf8'));
  assert.equal(index.entries.length, 3); assert(index.entries.every(entry => entry.path.startsWith('app/')));
}));
test('inventory rejects symlink inputs and excessive recursive depth', () => fixture(directory => {
  put(directory, 'assets/target.png', 'image'); symlinkSync(join(directory, 'assets/target.png'), join(directory, 'assets/link.png'));
  assert.throws(() => inventory({ platform: 'ios', bundleRoot: directory }), /symlink/);
  rmSync(join(directory, 'assets/link.png'));
  put(directory, `assets/${Array(34).fill('nested').join('/')}/file`, 'deep');
  assert.throws(() => inventory({ platform: 'ios', bundleRoot: directory }), /depth/);
}));
test('Swift executes build-generated inventory lookup and verified embedded reuse', { skip: process.platform !== 'darwin' && 'Requires macOS Swift/CryptoKit' }, () => fixture(directory => {
  const bundle = join(directory, 'bundle'), bytes = Buffer.from('embedded image fixture');
  put(bundle, 'assets/images/logo.png', bytes);
  const index = writeInventory({ platform: 'ios', bundleRoot: bundle });
  const binary = join(directory, 'embedded-test');
  run('swiftc', ['-module-cache-path', join(directory, 'modules'), join(root, 'ios/NPContentHashCache.swift'), join(root, 'ios/NPEmbeddedAssets.swift'), join(root, 'test/native/EmbeddedAssetsDriver.swift'), '-o', binary]);
  assert.match(run(binary, [bundle, index.value.entries[0].sha256, String(bytes.length)]), /path rejection passed/);
}));
test('Kotlin executes actual embedded-stream verification helper', t => {
  const cache = join(homedir(), '.gradle/caches/modules-2/files-2.1');
  function jar(group, name, version) {
    const dir = join(cache, group, name, version);
    for (const hash of readdirSync(dir)) for (const file of readdirSync(join(dir, hash))) if (file === `${name}-${version}.jar`) return join(dir, hash, file);
    throw new Error(`Missing ${name}`);
  }
  let compiler, stdlib, android;
  try {
    stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.10');
    compiler = ['kotlin-compiler-embeddable', 'kotlin-stdlib', 'kotlin-script-runtime', 'kotlin-reflect', 'kotlin-daemon-embeddable'].map(name => jar('org.jetbrains.kotlin', name, '2.2.10'));
    compiler.push(jar('org.jetbrains', 'annotations', '13.0'), jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'));
    android = join(process.env.ANDROID_HOME || join(homedir(), 'Library/Android/sdk'), 'platforms/android-36/android.jar');
    readFileSync(android);
  } catch { t.skip('Requires cached Kotlin compiler and Android 36 SDK'); return; }
  fixture(directory => {
    const output = join(directory, 'embedded.jar'), classpath = `${stdlib}:${android}`;
    run('java', ['-cp', compiler.join(':'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', classpath,
      join(root, 'android/src/main/java/com/nitropush/sdk/NPContentHashCache.kt'), join(root, 'android/src/main/java/com/nitropush/sdk/NPEmbeddedAssets.kt'), join(root, 'test/native/EmbeddedAssetsDriver.kt'), '-d', output]);
    assert.match(run('java', ['-cp', `${output}:${classpath}`, 'com.nitropush.sdk.EmbeddedAssetsDriverKt']), /path rejection passed/);
  });
});
