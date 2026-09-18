import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, readdirSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
const root = resolve(import.meta.dirname, '..');
function run(command, args) {
  const r = spawnSync(command, args, { encoding: 'utf8', timeout: 60_000 });
  assert.equal(r.status, 0, `${r.error ?? ''}\n${r.stdout}\n${r.stderr}`);
  return r.stdout;
}
test('Swift persistent runtime sequence gate and numeric bounds', { skip: process.platform !== 'darwin' && 'Requires the macOS native validation runner' }, () => {
  const dir = mkdtempSync(join(tmpdir(), 'nitropush-sequence-swift-'));
  try {
    const binary = join(dir, 'sequence');
    run('swiftc', [join(root, 'ios/NPSequenceLedger.swift'), join(root, 'test/native/SequenceDriver.swift'), '-o', binary]);
    assert.match(run(binary, []), /persistence passed/);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
test('Kotlin persistent runtime sequence gate and numeric bounds', t => {
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
  } catch { t.skip('Kotlin 2.2.10 compiler cache is unavailable; run this native check in Android CI'); return; }
  const dir = mkdtempSync(join(tmpdir(), 'nitropush-sequence-kotlin-'));
  try {
    const artifact = join(dir, 'sequence.jar');
    run('java', ['-cp', compiler.join(':'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', stdlib,
      join(root, 'android/src/main/java/com/nitropush/sdk/NPSequenceLedger.kt'), join(root, 'test/native/SequenceDriver.kt'), '-d', artifact]);
    assert.match(run('java', ['-cp', `${artifact}:${stdlib}`, 'com.nitropush.sdk.SequenceDriverKt']), /persistence passed/);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
