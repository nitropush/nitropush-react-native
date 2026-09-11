import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

test("published archive contains every runtime entrypoint and native source", () => {
  const workDir = mkdtempSync(join(tmpdir(), "nitropush-rn-pack-"));
  const archive = join(workDir, "react-native.tgz");

  try {
    execFileSync("yarn", ["pack", "--out", archive], {
      cwd: process.cwd(),
      stdio: "pipe",
    });

    const entries = new Set(
      execFileSync("tar", ["-tzf", archive], { encoding: "utf8" })
        .trim()
        .split("\n"),
    );

    const required = [
      "package/app.plugin.js",
      "package/plugin/build/index.js",
      "package/src/index.ts",
      "package/NitroPush.podspec",
      "package/android/src/main/AndroidManifest.xml",
      "package/android/src/main/java/com/nitropush/sdk/NitroPushSdk.kt",
      "package/android/src/main/cpp/bspatch/bspatch.c",
      "package/ios/bspatch/bspatch.c",
      "package/ios/NPSequenceLedger.swift",
      "package/android/src/main/java/com/nitropush/sdk/NPSequenceLedger.kt",
      "package/ios/NPContentHashCache.swift",
      "package/android/src/main/java/com/nitropush/sdk/NPContentHashCache.kt",
      "package/ios/NPEmbeddedAssets.swift",
      "package/android/src/main/java/com/nitropush/sdk/NPEmbeddedAssets.kt",
      "package/ios/NPAssetDeliveryURL.swift",
      "package/android/src/main/java/com/nitropush/sdk/NPAssetDeliveryURL.kt",
      "package/scripts/embedded-assets.cjs",
      "package/android/embedded-assets.gradle",
      "package/nitrogen/generated/android/NitroPushOnLoad.cpp",
      "package/nitrogen/generated/ios/NitroPushAutolinking.swift",
      "package/nitrogen/generated/shared/c++/HybridNitroPushSpec.cpp",
    ];

    for (const entry of required) {
      assert.ok(entries.has(entry), `missing required package file: ${entry}`);
    }
    for (const file of ['package/ios/NitroPushSdk.swift', 'package/android/src/main/java/com/nitropush/sdk/NitroPushSdk.kt']) {
      const source = execFileSync('tar', ['-xOzf', archive, file], { encoding: 'utf8' });
      assert.match(source, /cannot overwrite a referenced release/);
      assert.match(source, /validateSequenceReceipt/);
    }
  } finally {
    rmSync(workDir, { recursive: true, force: true });
  }
});
