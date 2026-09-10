import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, writeFileSync, rmSync, readdirSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
const root = resolve(import.meta.dirname, '..');
function run(command, args) {
  const r = spawnSync(command, args, { encoding: 'utf8', timeout: 60_000 });
  assert.equal(r.status, 0, `${r.error ?? ''}\n${r.stdout}\n${r.stderr}`);
}
function section(source, start, end) {
  const from = source.indexOf(start), to = source.indexOf(end, from + start.length);
  assert(from >= 0 && to > from, `missing SDK method section: ${start}`);
  return source.slice(from, to);
}

test('Swift release-envelope canonicalizer rejects trapping numeric values and preserves valid payloads', { skip: process.platform !== 'darwin' && 'Requires the macOS native validation runner' }, () => {
  const source = readFileSync(join(root, 'ios/NitroPushSdk.swift'), 'utf8');
  const types = section(source, 'private struct SdkManifest:', 'extension NitroPushSdk {').replaceAll('private struct', 'struct');
  const validators = section(source, '    private static func validateCanonicalUUID(', '    fileprivate static func releaseDirectory(');
  const canonicalizer = section(source, '    private static func releaseIntegrityPayload(', '    private func validateSignedContext(').replace('private static func', 'static func');
  const fixture = `import Foundation
${types}
enum Envelope {
${validators}
${canonicalizer}
}
@main struct Driver {
  static func main() throws {
    let bundle = SdkManifestBundle(originalPath: "main.hbc", sha256: String(repeating: "b", count: 64), objectKey: "bundle", size: 4, downloadUrl: nil, signature: nil, delta: nil, fileDelta: nil)
    func manifest(_ version: Double) -> SdkManifest {
      SdkManifest(schemaVersion: 4, releaseId: "00000000-0000-0000-8000-000000000001", projectId: "00000000-0000-0000-8000-000000000002", environment: "prod", deploymentKeyHash: String(repeating: "a", count: 64), kind: "codepush", platforms: ["ios"], targetAppVersion: "1.0", otaVersion: version, label: "release", isMandatory: false, bundle: bundle, assets: [], integritySignature: nil, releaseSignature: nil)
    }
    for bad in [Double(Int64.max), 9_007_199_254_740_992, .nan, .infinity, -1, 0, 1.5] {
      do { _ = try Envelope.releaseIntegrityPayload(manifest(bad)); fatalError("invalid sequence accepted") } catch {}
    }
    for good in [1.0, 9_007_199_254_740_991] {
      let payload = try Envelope.releaseIntegrityPayload(manifest(good))
      assert(payload.contains("otaVersion:" + String(Int64(good)) + "\\n"))
      assert(payload.hasPrefix("nitropush-release-v2\\n"))
    }
  }
}`;
  const dir = mkdtempSync(join(tmpdir(), 'nitropush-envelope-swift-'));
  try {
    writeFileSync(join(dir, 'Driver.swift'), fixture);
    const binary = join(dir, 'driver');
    run('swiftc', [join(root, 'ios/NitroPushTypes.swift'), join(dir, 'Driver.swift'), '-o', binary]);
    run(binary, []);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('Swift SDK migrates retained legacy floors and rejects stale pending without bypassing rollback', { skip: process.platform !== 'darwin' && 'Requires the macOS native validation runner' }, () => {
  const dir = mkdtempSync(join(tmpdir(), 'nitropush-sdk-state-swift-'));
  const source = readFileSync(join(root, 'ios/NitroPushSdk.swift'), 'utf8');
  // Exercise the actual state-transition/migration methods. Platform logging,
  // reload, and envelope verification are stubbed; crypto has a separate build
  // check. This test intentionally uses legacy state with no saved envelope.
  const methods = [
    section(source, '    private func consumePendingPointerOnLaunch()', '    private func installLifecycleObservers()'),
    section(source, '    private func activateDeferred(', '    private func requestLatestRelease('),
    section(source, '    private func sequenceScope(', '    private func deleteBundleDir('),
  ].join('\n').replaceAll('private func', 'func').replaceAll('UserDefaults.standard', 'self.defaults');
  const fixture = `import Foundation
import CryptoKit
struct NPLocalPackage {
  let releaseId: String; let appVersion: String; let otaVersion: Double?; let bundlePath: String = "unused"
  func toDict() -> [String: Any] { ["releaseId": releaseId, "appVersion": appVersion, "otaVersion": otaVersion!] }
  static func fromDict(_ d: [String: Any]) -> NPLocalPackage? {
    guard let id = d["releaseId"] as? String, let runtime = d["appVersion"] as? String else { return nil }
    return NPLocalPackage(releaseId: id, appVersion: runtime, otaVersion: d["otaVersion"] as? Double)
  }
}
struct SdkManifest: Decodable {
  struct Bundle: Decodable { let sha256: String }
  let schemaVersion: Int?; let releaseId: String?; let deploymentKeyHash: String?; let targetAppVersion: String?
  let otaVersion: Double?; let platforms: [String]?; let releaseSignature: String?; let bundle: Bundle
}
class Harness {
  enum DefaultsKey { static let active = "active", previous = "previous", pending = "pending", unconfirmed = "unconfirmed", pendingRollbackEvent = "rollback" }
  static var root = URL(fileURLWithPath: CommandLine.arguments[1])
  static let sequenceReceiptName = ".nitropush-sequence.json", maxManifestBytes = 2 * 1024 * 1024
  let defaults: UserDefaults
  let suite = "org.nitropush.security-test." + UUID().uuidString
  var bundlePublicKey: String? = "test-key", deploymentKeyHash: String? = "deployment"
  var reloads = 0, deleted: [String] = []
  lazy var sequenceLedger = NPSequenceLedger(load: { scope in
    guard let d = self.defaults.dictionary(forKey: "floor." + scope) else { return nil }
    return NPSequenceStamp(version: d["version"] as! Double, releaseId: d["id"] as! String)
  }, save: { scope, stamp in self.defaults.set(["version": stamp.version, "id": stamp.releaseId], forKey: "floor." + scope) })
  init() { defaults = UserDefaults(suiteName: suite)! }
  deinit { defaults.removePersistentDomain(forName: suite) }
  static func releaseDirectory(for id: String) throws -> URL { root.appendingPathComponent(id) }
  static func sha256Hex(_ d: Data) -> String { SHA256.hash(data: d).map { String(format: "%02x", $0) }.joined() }
  static func sha256Hex(of: URL) throws -> String { sha256Hex(try Data(contentsOf: of)) }
  static func releaseIntegrityPayload(_ m: SdkManifest) throws -> String { throw NPSequenceLedger.Failure.invalid }
  static func verifySignature(message: String, signatureBase64: String, publicKeyBase64: String, description: String) throws { throw NPSequenceLedger.Failure.invalid }
  func log(_ s: String, error: Error) {}
  func persistFlag(releaseId: String, isFirstRun: Bool = false, isFailedInstall: Bool = false) {}
  func deleteBundleDir(releaseId: String) { deleted.append(releaseId) }
  func reloadBridge() { reloads += 1 }
  ${methods}
}
@main struct Driver {
  static func main() throws {
    let h = Harness()
    func pkg(_ id: String, _ version: Double, _ runtime: String = "1.0") throws -> NPLocalPackage {
      try FileManager.default.createDirectory(at: Harness.root.appendingPathComponent(id), withIntermediateDirectories: true)
      return NPLocalPackage(releaseId: id, appVersion: runtime, otaVersion: version)
    }
    let a = try pkg("accepted-exact", 10), old = try pkg("previous-exact", 8), wildcard = try pkg("wildcard", 1, "*")
    h.defaults.set(a.toDict(), forKey: "active"); h.defaults.set(old.toDict(), forKey: "previous")
    try h.seedAcceptedSequenceHistory()
    let scope = h.sequenceScope(keyHash: "deployment", runtime: "1.0")
    h.defaults.set(wildcard.toDict(), forKey: "active"); h.defaults.removeObject(forKey: "previous")
    try h.seedAcceptedSequenceHistory()
    do { try h.sequenceLedger.check(scope, 9, "replayed"); fatalError("legacy runtime floor lost") } catch {}
    // Reconfiguration must not rebind the old package to another deployment.
    h.deploymentKeyHash = "other"; h.defaults.set(a.toDict(), forKey: "active")
    try h.seedAcceptedSequenceHistory()
    try h.sequenceLedger.check(h.sequenceScope(keyHash: "other", runtime: "1.0"), 1, "other-release")
    h.deploymentKeyHash = "deployment"
    let b = try pkg("pending", 11), previous = try pkg("healthy", 7)
    let receipt: [String: Any] = ["scope": scope, "version": 11.0, "releaseId": b.releaseId]
    try JSONSerialization.data(withJSONObject: receipt).write(to: Harness.root.appendingPathComponent("pending/.nitropush-sequence.json"))
    try h.sequenceLedger.check(scope, 12, "downloaded-newer", remember: true)
    h.defaults.set(b.toDict(), forKey: "pending"); h.defaults.set(previous.toDict(), forKey: "previous"); h.defaults.set(true, forKey: "unconfirmed")
    h.consumePendingPointerOnLaunch()
    assert(h.readActive()?.releaseId == previous.releaseId && h.readPending() == nil)
    assert(!h.defaults.bool(forKey: "unconfirmed") && h.deleted == [a.releaseId])
    for reload in [false, true] {
      h.defaults.set(b.toDict(), forKey: "pending")
      h.activateDeferred(b, reload: reload)
      assert(h.readActive()?.releaseId == previous.releaseId && h.readPending() == nil && h.reloads == 0)
    }
    // Legitimate current pending state still activates and requests a reload.
    let current = try pkg("current", 13)
    try h.sequenceLedger.check(scope, 13, current.releaseId, remember: true)
    try JSONSerialization.data(withJSONObject: ["scope": scope, "version": 13.0, "releaseId": current.releaseId])
      .write(to: Harness.root.appendingPathComponent("current/.nitropush-sequence.json"))
    h.activateDeferred(current, reload: true)
    assert(h.readActive()?.releaseId == current.releaseId && h.reloads == 1)
    print("legacy migration, startup rollback and deferred activation passed")
  }
}`;
  try {
    writeFileSync(join(dir, 'Driver.swift'), fixture);
    const binary = join(dir, 'driver');
    run('swiftc', [join(root, 'ios/NPSequenceLedger.swift'), join(dir, 'Driver.swift'), '-o', binary]);
    run(binary, [dir]);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('Kotlin SDK stale deferred callbacks do not throw and stale pending still rolls back failed boot', t => {
  const cache = join(homedir(), '.gradle/caches/modules-2/files-2.1');
  function jar(group, name, version) {
    const dir = join(cache, group, name, version);
    for (const hash of readdirSync(dir)) for (const file of readdirSync(join(dir, hash)))
      if (file === `${name}-${version}.jar`) return join(dir, hash, file);
    throw new Error(`Missing ${name}`);
  }
  let compiler, stdlib;
  try {
    stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.10');
    compiler = ['kotlin-compiler-embeddable', 'kotlin-stdlib', 'kotlin-script-runtime', 'kotlin-reflect', 'kotlin-daemon-embeddable'].map(n => jar('org.jetbrains.kotlin', n, '2.2.10'));
    compiler.push(jar('org.jetbrains', 'annotations', '13.0'), jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'));
  } catch { t.skip('Kotlin compiler cache unavailable'); return; }
  const source = readFileSync(join(root, 'android/src/main/java/com/nitropush/sdk/NitroPushSdk.kt'), 'utf8');
  const methods = [
    section(source, '    private fun consumePendingPointerOnLaunch()', '    private fun requestLatestRelease('),
    section(source, '    private fun persistPending(', '    private fun deleteBundleDir('),
  ].join('\n').replaceAll('private fun', 'fun');
  // In-memory preferences and lifecycle adapters keep this JVM harness isolated
  // from Android. JSON package fixtures are IDs; real JSON/core type compatibility
  // is checked separately against android.jar. Transition method bodies are exact.
  const fixture = `package com.nitropush.sdk
class JSONObject(val id: String) { fun getString(key: String) = id; override fun toString() = id }
data class NPLocalPackage(val releaseId: String) {
  fun toJson() = JSONObject(releaseId)
  companion object { fun fromJson(j: JSONObject) = NPLocalPackage(j.id) }
}
class Prefs {
  val data = mutableMapOf<String, Any>()
  fun getString(k: String, d: String?): String? = data[k] as? String ?: d
  fun getBoolean(k: String, d: Boolean) = data[k] as? Boolean ?: d
  fun edit() = Editor()
  inner class Editor {
    val writes = mutableMapOf<String, Any?>()
    fun putString(k: String, v: String) = apply { writes[k] = v }
    fun putBoolean(k: String, v: Boolean) = apply { writes[k] = v }
    fun remove(k: String) = apply { writes[k] = null }
    fun apply() { writes.forEach { (k, v) -> if (v == null) data.remove(k) else data[k] = v } }
  }
}
class LifecycleOwner
interface DefaultLifecycleObserver { fun onStop(owner: LifecycleOwner); fun onStart(owner: LifecycleOwner) }
class Lifecycle { lateinit var observer: DefaultLifecycleObserver; fun addObserver(o: DefaultLifecycleObserver) { observer = o } }
object ProcessLifecycleOwner { val lifecycle = Lifecycle(); fun get() = this }
object Looper { fun getMainLooper() = this }
class Handler(l: Looper) { fun post(f: () -> Unit) = f() }
class Harness {
  object Keys { const val ACTIVE = "active"; const val PREVIOUS = "previous"; const val PENDING = "pending"; const val UNCONFIRMED = "unconfirmed"; const val PENDING_ROLLBACK_EVENT = "rollback" }
  val prefs = Prefs(); val deleted = mutableListOf<String>(); var reloads = 0
  var pendingSuspend: NPLocalPackage? = null; var pendingResume: Pair<NPLocalPackage, Long>? = null; var lastBackgroundedAt = 0L
  fun validateSequenceReceipt(pkg: NPLocalPackage) { check(pkg.releaseId != "stale") { "stale sequence" } }
  fun persistFlag(id: String, isFirstRun: Boolean = false, isFailedInstall: Boolean = false) {}
  fun deleteBundleDir(id: String) { deleted.add(id) }
  fun log(s: String, d: () -> String) {}; fun log(s: String, e: Exception) {}
  fun reloadBridge() { reloads++ }; fun pruneUnusedBundlesAndCache() {}
  ${methods}
}
fun main() {
  val h = Harness()
  h.prefs.data.putAll(mapOf("active" to "failed", "previous" to "healthy", "pending" to "stale", "unconfirmed" to true))
  h.consumePendingPointerOnLaunch()
  check(h.readActive()?.releaseId == "healthy" && h.readPending() == null && h.deleted == listOf("failed"))
  check(!h.prefs.getBoolean("unconfirmed", false))
  h.installLifecycleObservers()
  val lifecycle = ProcessLifecycleOwner.lifecycle.observer
  h.pendingSuspend = NPLocalPackage("stale"); h.prefs.data["pending"] = "stale"
  lifecycle.onStop(LifecycleOwner())
  check(h.pendingSuspend == null && h.readActive()?.releaseId == "healthy" && h.readPending() == null)
  h.pendingResume = NPLocalPackage("stale") to 0L; h.prefs.data["pending"] = "stale"
  lifecycle.onStart(LifecycleOwner())
  check(h.pendingResume == null && h.readActive()?.releaseId == "healthy" && h.readPending() == null && h.reloads == 0)
  h.pendingResume = NPLocalPackage("current") to 0L
  lifecycle.onStart(LifecycleOwner())
  check(h.readActive()?.releaseId == "current" && h.reloads == 1)
  println("startup rollback and deferred callbacks passed")
}`;
  const dir = mkdtempSync(join(tmpdir(), 'nitropush-sdk-state-kotlin-'));
  try {
    writeFileSync(join(dir, 'Driver.kt'), fixture);
    const artifact = join(dir, 'driver.jar');
    run('java', ['-cp', compiler.join(':'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', stdlib, join(dir, 'Driver.kt'), '-d', artifact]);
    run('java', ['-cp', `${artifact}:${stdlib}`, 'com.nitropush.sdk.DriverKt']);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
