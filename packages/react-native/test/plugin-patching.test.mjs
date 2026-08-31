import assert from "node:assert/strict";
import { generateKeyPairSync } from "node:crypto";
import test from "node:test";

import {
  patchAppDelegateSwift,
  patchExpoPodfile,
  patchMainApplicationKotlin,
  resolveDeploymentKey,
  validateBundlePublicKey,
} from "../plugin/build/index.js";

const expo57MainApplication = `package org.nitropush.validation

import android.app.Application
import expo.modules.ExpoReactHostFactory

class MainApplication : Application(), ReactApplication {
  override val reactHost: ReactHost by lazy {
    ExpoReactHostFactory.getDefaultReactHost(
      context = applicationContext,
      packageList = PackageList(this).packages
    )
  }

  override fun onCreate() {
    super.onCreate()
    loadReactNative(this)
  }
}
`;

test("Expo 57 ReactHost receives the active production bundle path", () => {
  const patched = patchMainApplicationKotlin(expo57MainApplication);

  assert.match(patched, /import com\.nitropush\.sdk\.NitroPushSdk/);
  assert.match(patched, /NitroPushSdk\.install\(this\)/);
  assert.match(
    patched,
    /jsBundleFilePath\s*=\s*if \(BuildConfig\.DEBUG\) null else NitroPushSdk\.shared\.activeBundleFile\(\),/,
  );
});

test("Expo 57 patch is idempotent", () => {
  const once = patchMainApplicationKotlin(expo57MainApplication);
  const twice = patchMainApplicationKotlin(once);

  assert.equal(twice, once);
});

const expo57Podfile = `post_install do |installer|
  react_native_post_install(
    installer,
    config[:reactNativePath],
    :mac_catalyst_enabled => false,
    :ccache_enabled => ccache_enabled?(podfile_properties),
  )
end
`;

test("iOS plugin enables C++ interop without rewriting umbrella headers", () => {
  const patched = patchExpoPodfile(expo57Podfile);

  assert.match(patched, /SWIFT_OBJC_INTEROP_MODE/);
  assert.match(patched, /Headers\/Private\/NitroModules/);
  assert.doesNotMatch(patched, /umbrella/i);
  assert.doesNotMatch(patched, /\.hpp/);
  assert.equal(patchExpoPodfile(patched), patched);
});

const expoAppDelegate = `import Expo

class AppDelegate: ExpoAppDelegate {
  override func bundleURL() -> URL? {
    return nil
  }
}
`;

test("iOS plugin never confirms an update from a native lifecycle callback", () => {
  const patched = patchAppDelegateSwift(expoAppDelegate);

  assert.doesNotMatch(patched, /notifyAppReady/);
  assert.doesNotMatch(patched, /applicationDidBecomeActive/);
});

test("plugin validates a canonical P-256 SPKI and rejects malformed/wrong-curve keys", () => {
  const p256 = generateKeyPairSync("ec", { namedCurve: "prime256v1" })
    .publicKey.export({ format: "der", type: "spki" })
    .toString("base64");
  const p384 = generateKeyPairSync("ec", { namedCurve: "secp384r1" })
    .publicKey.export({ format: "der", type: "spki" })
    .toString("base64");

  assert.doesNotThrow(() => validateBundlePublicKey(p256));
  assert.throws(() => validateBundlePublicKey("not base64"), /base64 DER/);
  assert.throws(() => validateBundlePublicKey(p384), /P-256/);
});

test("plugin resolves deployment credentials from a private build variable", () => {
  assert.deepEqual(
    resolveDeploymentKey(undefined, { NITROPUSH_DEPLOYMENT_KEY: "  test-key  " }),
    {
      deploymentKey: "test-key",
      deploymentKeyEnvVar: "NITROPUSH_DEPLOYMENT_KEY",
    },
  );
  assert.deepEqual(
    resolveDeploymentKey(
      { deploymentKeyEnvVar: "CUSTOM_NITROPUSH_KEY" },
      { CUSTOM_NITROPUSH_KEY: "custom-key" },
    ),
    {
      deploymentKey: "custom-key",
      deploymentKeyEnvVar: "CUSTOM_NITROPUSH_KEY",
    },
  );
  assert.equal(
    resolveDeploymentKey(
      { deploymentKey: "inline-key", deploymentKeyEnvVar: "CUSTOM_KEY" },
      { CUSTOM_KEY: "environment-key" },
    ).deploymentKey,
    "inline-key",
  );
  assert.throws(
    () => resolveDeploymentKey({ deploymentKeyEnvVar: "BAD-NAME" }, {}),
    /environment variable name/,
  );
});
