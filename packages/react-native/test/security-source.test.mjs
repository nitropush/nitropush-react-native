import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { fileURLToPath } from "node:url";

const packageRoot = fileURLToPath(new URL("..", import.meta.url));
const ios = readFileSync(`${packageRoot}/ios/NitroPushSdk.swift`, "utf8");
const android = readFileSync(
  `${packageRoot}/android/src/main/java/com/nitropush/sdk/NitroPushSdk.kt`,
  "utf8",
);
const iosAnalytics = readFileSync(
  `${packageRoot}/ios/NitroPushAnalytics.swift`,
  "utf8",
);
const androidAnalytics = readFileSync(
  `${packageRoot}/android/src/main/java/com/nitropush/sdk/NitroPushAnalytics.kt`,
  "utf8",
);
const androidBspatchJni = readFileSync(
  `${packageRoot}/android/src/main/java/com/nitropush/sdk/BspatchJni.kt`,
  "utf8",
);
const androidCmake = readFileSync(
  `${packageRoot}/android/CMakeLists.txt`,
  "utf8",
);

test("signed native installs require the contextual schema-4 envelope", () => {
  for (const source of [ios, android]) {
    assert.match(source, /nitropush-release-v2/);
    assert.match(source, /releaseSignature/);
    assert.match(source, /releaseId/);
    assert.match(source, /projectId/);
    assert.match(source, /environment/);
    assert.match(source, /deploymentKeyHash/);
    assert.match(source, /otaVersion/);
    assert.match(source, /isMandatory/);
  }
  assert.match(ios, /guard schemaVersion == 4 else/);
  assert.match(android, /check\(schemaVersion == 4\)/);
  assert.doesNotMatch(ios, /guard schemaVersion == 3 else/);
  assert.doesNotMatch(android, /check\(schemaVersion == 3\)/);
});

test("native device proof is origin-scoped and redirects are rejected", () => {
  for (const source of [ios, android]) {
    assert.match(source, /x-nitropush-device-token/);
    assert.match(source, /nitropush\.deviceToken\./);
    assert.match(source, /sameOrigin/);
  }
  assert.match(ios, /willPerformHTTPRedirection[\s\S]*completionHandler\(nil\)/);
  assert.match(android, /instanceFollowRedirects = false/);
  for (const source of [iosAnalytics, androidAnalytics]) {
    assert.match(source, /x-nitropush-device-token/);
  }
  assert.match(iosAnalytics, /willPerformHTTPRedirection[\s\S]*completionHandler\(nil\)/);
  assert.match(androidAnalytics, /instanceFollowRedirects = false/);
});

test("native telemetry reuses a UUID event ID across network retries", () => {
  assert.match(ios, /eventId: UUID\(\)\.uuidString\.lowercased\(\)/);
  assert.match(android, /eventId = UUID\.randomUUID\(\)\.toString\(\)/);
  assert.match(iosAnalytics, /let eventId: String/);
  assert.match(iosAnalytics, /queue\.insert\(contentsOf: batch, at: 0\)/);
  assert.match(androidAnalytics, /obj\.put\("eventId", eventId\)/);
  assert.match(androidAnalytics, /queue\.addFirst\(batch\[i\]\)/);
});

test("deployment credentials use an origin-bound header instead of query strings", () => {
  for (const source of [ios, android]) {
    assert.match(source, /x-nitropush-deployment-key/);
  }
  assert.doesNotMatch(ios, /URLQueryItem\(name: "deploymentKey"/);
  assert.doesNotMatch(android, /"deploymentKey" to key/);
});

test("native filesystem operations validate release UUIDs and update-root containment", () => {
  assert.match(ios, /validateCanonicalUUID\(releaseId, fieldName: "releaseId"\)/);
  assert.match(ios, /lowercase canonical UUID/);
  assert.match(ios, /release directory escapes the update root/);
  assert.match(ios, /releaseDirectory\(for: pkg\.releaseId\)/);
  assert.match(android, /validateCanonicalUuid\(releaseId, "releaseId"\)/);
  assert.match(android, /lowercase canonical UUID/);
  assert.match(android, /release directory escapes the update root/);
  assert.match(android, /releaseDirectory\(applicationContext, pkg\.releaseId\)/);
  assert.doesNotMatch(android, /"nitropush\/\$releaseId"/);
});

test("native downloads enforce absolute byte ceilings without trusting declared sizes", () => {
  assert.match(ios, /maximumBytes: maximumBytes/);
  assert.match(ios, /totalBytesWritten > maximumBytes/);
  assert.match(android, /written > maximumBytes/);
  assert.match(android, /contentLengthLong > maximumBytes/);
  for (const source of [ios, android]) {
    assert.match(source, /maxReleaseBytes/);
    assert.match(source, /maxManifestBytes/);
  }
});

test("native diagnostics redact query strings and response bodies", () => {
  assert.match(ios, /responseBytes=\\\(body\.count\)/);
  assert.doesNotMatch(ios, /body=\\\(snippet\)/);
  assert.match(android, /responseBytes/);
  assert.doesNotMatch(android, /body=\$snippet/);
});

test("reconfiguration clears server-issued URL overrides", () => {
  assert.match(ios, /manifestUrlOverride\.removeAll\(\)/);
  assert.match(android, /manifestUrlOverride\.clear\(\)/);
});

test("Android bspatch loads the case-sensitive CMake library name", () => {
  const packageName = androidCmake.match(/set\s*\(PACKAGE_NAME\s+([^\s)]+)/)?.[1];
  const loadedLibrary = androidBspatchJni.match(/System\.loadLibrary\("([^"]+)"\)/)?.[1];

  assert.equal(loadedLibrary, packageName);
});
