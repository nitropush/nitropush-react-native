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
test('Swift executes production asset-proxy authorization URL policy', { skip: process.platform !== 'darwin' && 'Requires macOS native runner' }, () => {
  const directory = mkdtempSync(join(tmpdir(), 'np-asset-url-swift-'));
  try {
    const binary = join(directory, 'url');
    run('swiftc', ['-module-cache-path', join(directory, 'modules'), join(root, 'ios/NPAssetDeliveryURL.swift'), join(root, 'test/native/AssetDeliveryURLDriver.swift'), '-o', binary]);
    assert.match(run(binary, []), /credential isolation passed/);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});
test('Kotlin executes production asset-proxy authorization URL policy', t => {
  const cache = join(homedir(), '.gradle/caches/modules-2/files-2.1');
  function jar(group, name, version) {
    const directory = join(cache, group, name, version);
    for (const hash of readdirSync(directory)) for (const file of readdirSync(join(directory, hash)))
      if (file === `${name}-${version}.jar`) return join(directory, hash, file);
    throw new Error(`Missing ${name}`);
  }
  let compiler, stdlib;
  try {
    stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.10');
    compiler = ['kotlin-compiler-embeddable', 'kotlin-stdlib', 'kotlin-script-runtime', 'kotlin-reflect', 'kotlin-daemon-embeddable'].map(name => jar('org.jetbrains.kotlin', name, '2.2.10'));
    compiler.push(jar('org.jetbrains', 'annotations', '13.0'), jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'));
  } catch { t.skip('Requires cached Kotlin 2.2.10 compiler'); return; }
  const directory = mkdtempSync(join(tmpdir(), 'np-asset-url-kotlin-'));
  try {
    const output = join(directory, 'url.jar');
    run('java', ['-cp', compiler.join(':'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', stdlib,
      join(root, 'android/src/main/java/com/nitropush/sdk/NPAssetDeliveryURL.kt'), join(root, 'test/native/AssetDeliveryURLDriver.kt'), '-d', output]);
    assert.match(run('java', ['-cp', `${output}:${stdlib}`, 'com.nitropush.sdk.AssetDeliveryURLDriverKt']), /credential isolation passed/);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});
test('native negotiation, identity payloads and credential forwarding stay at authenticated boundaries', () => {
  const ios = readFileSync(join(root, 'ios/NitroPushSdk.swift'), 'utf8');
  const android = readFileSync(join(root, 'android/src/main/java/com/nitropush/sdk/NitroPushSdk.kt'), 'utf8');
  assert.match(ios, /deviceToken != nil && validatedURL\.scheme\?\.lowercased\(\) == "https"[\s\S]*proxy-v1/);
  assert.match(android, /sameOrigin\(parsed, api\) && parsed\.protocol\.equals\("https", ignoreCase = true\) && deviceToken != null[\s\S]*proxy-v1/);
  assert.match(ios, /guard isAssetProxy else \{ return \(url, nil\) \}/);
  assert.match(android, /includeDeviceToken = isAssetProxy/);
  for (const source of [ios, android]) {
    assert.match(source, /NPAssetDeliveryURL\.resolve/);
    assert.match(source, /Accept-Encoding/);
    assert.match(source, /asset proxy must return a complete response/);
  }
  assert.match(ios, /willPerformHTTPRedirection[\s\S]*completionHandler\(nil\)/);
  assert.match(android, /instanceFollowRedirects = false/);
});
