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

test("signed native installs reject schema-2 manifest downgrades", () => {
  assert.match(ios, /guard schemaVersion == 3 else/);
  assert.match(android, /check\(schemaVersion == 3\)/);
  assert.doesNotMatch(ios, /if schemaVersion >= 3/);
  assert.doesNotMatch(android, /if \(schemaVersion >= 3\)/);
});

test("native latest and manifest requests persist and send device proof", () => {
  for (const source of [ios, android]) {
    assert.match(source, /x-nitropush-device-token/);
    assert.match(source, /nitropush\.deviceToken\./);
  }
});
