/**
 * Expo config plugin for `@nitropush/react-native`.
 *
 * Wires the bundle-loader hooks the SDK needs at native level so Expo apps
 * get over-the-air updates without anyone hand-editing AppDelegate.swift /
 * MainApplication.kt.
 *
 * What it does at `expo prebuild` time:
 *
 *   ▸ AppDelegate.swift       inject `import NitroPush`
 *                             inject `configure()` + background update-check
 *                             `Task.detached` (when serverUrl + deploymentKey
 *                             are provided as plugin props)
 *                             prepend `NitroPushSdk.shared.activeBundleURL()`
 *                             check inside `bundleURL()` — guarded by
 *                             `#if !DEBUG` so dev builds keep loading from
 *                             Metro
 *                             JS must call `notifyAppReady()` only after a
 *                             successful first render; native lifecycle
 *                             callbacks are intentionally never injected
 *
 *   ▸ MainApplication.kt      inject `import com.nitropush.nitrosdk.NitroPushSdk`
 *                             call `NitroPushSdk.install(this)` after
 *                             `super.onCreate()`
 *                             override `getJSBundleFile()` on the
 *                             `reactNativeHost` to delegate to the SDK
 *                             (returns `super.getJSBundleFile()` when
 *                             `BuildConfig.DEBUG` so dev builds keep
 *                             loading from Metro)
 *
 *   ▸ Podfile                 opt the app target into Swift/C++ interop.
 *                             NitroPush relies on Nitrogen's generated
 *                             public/private header split and never rewrites
 *                             CocoaPods umbrella headers.
 *
 * Every AppDelegate / MainApplication injection is wrapped in tagged
 * `// @generated begin … / // @generated end` markers via `mergeContents`,
 * so the patches are:
 *   - visible to anyone reading the native files,
 *   - idempotent (re-running prebuild is a no-op),
 *   - reversible with `removeContents` if you ever need to bail out.
 *
 * Usage in `app.json` / `app.config.ts`:
 *
 *   {
 *     "expo": {
 *       "plugins": [
 *         ["@nitropush/react-native", {
 *           "serverUrl": "https://nitropush.example.com",
 *           "deploymentKeyEnvVar": "NITROPUSH_DEPLOYMENT_KEY",
 *           "storageBaseUrl": "https://cdn.example.com/bundles",
 *           "bundlePublicKey": "BASE64_DER_PUBLIC_KEY",
 *           "requireBundleSigning": true,
 *         }]
 *       ]
 *     }
 *   }
 *
 * `deploymentKeyEnvVar` defaults to `NITROPUSH_DEPLOYMENT_KEY`. Resolving the
 * value inside the plugin keeps it out of Expo's public config and JS bundle.
 * `serverUrl` / `storageBaseUrl` are optional for hosted apps.
 *
 * Network security (ATS / NSExceptionDomains on iOS, network_security_config
 * on Android) is the host app's responsibility and is intentionally NOT
 * managed by this plugin. Use your own config plugin or manually edit
 * Info.plist / network_security_config.xml.
 */

import {
    AndroidConfig,
    type ConfigPlugin,
    createRunOncePlugin,
    withAndroidManifest,
    withAppDelegate,
    withDangerousMod,
    withInfoPlist,
    withMainApplication,
  } from "@expo/config-plugins";
  import { mergeContents, removeContents } from "@expo/config-plugins/build/utils/generateCode";
  import { createPublicKey } from "crypto";
  import * as fs from "fs";
  import * as path from "path";
  
  const PKG_NAME = "@nitropush/react-native";
  const PKG_VERSION = "0.1.0";

  /** Validate the exact public-key representation consumed by CryptoKit/JCA. */
  export function validateBundlePublicKey(value: string): void {
    if (!/^[A-Za-z0-9+/]+={0,2}$/.test(value) || value.length % 4 !== 0) {
      throw new Error(
        "[@nitropush/react-native] bundlePublicKey must be canonical base64 DER.",
      );
    }
    try {
      const der = Buffer.from(value, "base64");
      if (der.length === 0 || der.toString("base64") !== value) throw new Error("non-canonical");
      const key = createPublicKey({ key: der, format: "der", type: "spki" });
      const details = key.asymmetricKeyDetails as { namedCurve?: string } | undefined;
      if (key.asymmetricKeyType !== "ec" || details?.namedCurve !== "prime256v1") {
        throw new Error("wrong curve");
      }
    } catch {
      throw new Error(
        "[@nitropush/react-native] bundlePublicKey must be a base64 DER SubjectPublicKeyInfo P-256 public key.",
      );
    }
  }
  
  /**
   * Optional knobs. The defaults match what 99 % of apps want; you should only
   * set `ios` / `android` to `false` if you're patching the native files
   * yourself.
   */
  export interface NitroPushPluginProps {
    /** Inject the iOS bundle-URL override into `AppDelegate.swift`. Default: `true`. */
    ios?: boolean;
    /** Inject the Android `HybridNitroPush.install` + `getJSBundleFile` overrides. Default: `true`. */
    android?: boolean;
    /**
     * NitroPush server URL. When provided together with `deploymentKey`, the
     * plugin injects the native `configure()` + background update-check
     * `Task.detached` into `AppDelegate.swift`. If omitted, call
     * `configure()` from JS instead.
     */
    serverUrl?: string;
    /** Deployment key for the target environment. Required when `serverUrl` is set. */
    /**
     * Deployment credential written into native configuration. Prefer
     * `deploymentKeyEnvVar`; an inline value is retained for compatibility but
     * becomes part of Expo's public config.
     */
    deploymentKey?: string;
    /**
     * Name of the private build environment variable containing the
     * deployment credential. Defaults to `NITROPUSH_DEPLOYMENT_KEY`.
     */
    deploymentKeyEnvVar?: string;
    /**
     * Object-storage base URL for bundle downloads (e.g. your MinIO / S3
     * bucket URL). Injected into Info.plist / AndroidManifest.
     */
    storageBaseUrl?: string;
    /**
     * Base64-encoded DER SPKI public key used to verify bundle signatures.
     * Injected as `NITROPUSH_BUNDLE_PUBLIC_KEY` in Info.plist / AndroidManifest.
     * Only required when your releases are signed (`--signing-key` on upload).
     */
    bundlePublicKey?: string;
    /**
     * Fail prebuild when `bundlePublicKey` is omitted. Use this for projects
     * whose dashboard signing key is enabled so a build can never silently
     * ship without on-device verification. Default: `false` for unsigned
     * projects and backward compatibility.
     */
    requireBundleSigning?: boolean;
    /**
     * Enable experimental bsdiff4 Delta Updates. The SDK still downloads the
     * full bundle whenever no compatible, verified patch is available.
     */
    enableDeltaUpdates?: boolean;
    /**
     * Inject a native `configure()` + background update-check `Task.detached`
     * into `AppDelegate.swift`. Only needed for **bare React Native** apps that
     * want the SDK initialised before the JS engine starts.
     *
     * In **Expo** apps leave this `false` (the default): the JS-side no-arg
     * `configure()` call reads `NITROPUSH_*` from Info.plist instead, which the
     * plugin already injects via `withInfoPlist`.
     *
     * Default: `false`.
     */
    nativeConfigure?: boolean;
  }
  
  export function resolveDeploymentKey(
    props: Pick<NitroPushPluginProps, "deploymentKey" | "deploymentKeyEnvVar"> | void,
    environment: NodeJS.ProcessEnv = process.env,
  ): { deploymentKey: string; deploymentKeyEnvVar: string } {
    const deploymentKeyEnvVar =
      props?.deploymentKeyEnvVar?.trim() || "NITROPUSH_DEPLOYMENT_KEY";
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(deploymentKeyEnvVar)) {
      throw new Error(
        "[@nitropush/react-native] deploymentKeyEnvVar must be an environment variable name.",
      );
    }

    return {
      deploymentKey:
        props?.deploymentKey?.trim() ||
        environment[deploymentKeyEnvVar]?.trim() ||
        "",
      deploymentKeyEnvVar,
    };
  }

  const withNitroPush: ConfigPlugin<NitroPushPluginProps | void> = (
    config,
    props,
  ) => {
    const resolvedDeployment = resolveDeploymentKey(props);
    const opts: Required<NitroPushPluginProps> = {
      ios: props?.ios ?? true,
      android: props?.android ?? true,
      serverUrl: props?.serverUrl ?? "",
      deploymentKey: resolvedDeployment.deploymentKey,
      deploymentKeyEnvVar: resolvedDeployment.deploymentKeyEnvVar,
      storageBaseUrl: props?.storageBaseUrl ?? "",
      bundlePublicKey: props?.bundlePublicKey ?? "",
      requireBundleSigning: props?.requireBundleSigning ?? false,
      enableDeltaUpdates: props?.enableDeltaUpdates ?? false,
      nativeConfigure: props?.nativeConfigure ?? false,
    };

    if (opts.requireBundleSigning && !opts.bundlePublicKey) {
      throw new Error(
        "[@nitropush/react-native] requireBundleSigning=true requires the full base64 DER P-256 bundlePublicKey.",
      );
    }
    if (opts.bundlePublicKey) validateBundlePublicKey(opts.bundlePublicKey);
    if (opts.bundlePublicKey && !opts.deploymentKey) {
      throw new Error(
        "[@nitropush/react-native] bundlePublicKey requires deploymentKey so it can be injected into the native app.",
      );
    }

    // ── iOS Info.plist keys ────────────────────────────────────────────────
    // The no-arg `configure()` call reads NITROPUSH_* from Info.plist; inject
    // them whenever the plugin has values for them.
    if (opts.ios) {
      config = withInfoPlist(config, (cfg) => {
        const managedKeys = [
          "NITROPUSH_DEPLOYMENT_KEY",
          "NITROPUSH_SERVER_URL",
          "NITROPUSH_STORAGE_BASE_URL",
          "NITROPUSH_BUNDLE_PUBLIC_KEY",
          "NITROPUSH_ENABLE_DELTA_UPDATES",
        ];
        for (const key of managedKeys) delete cfg.modResults[key];
        if (opts.deploymentKey) cfg.modResults["NITROPUSH_DEPLOYMENT_KEY"] = opts.deploymentKey;
        if (opts.deploymentKey && opts.serverUrl) cfg.modResults["NITROPUSH_SERVER_URL"] = opts.serverUrl;
        if (opts.deploymentKey && opts.storageBaseUrl) cfg.modResults["NITROPUSH_STORAGE_BASE_URL"] = opts.storageBaseUrl;
        if (opts.deploymentKey && opts.bundlePublicKey) cfg.modResults["NITROPUSH_BUNDLE_PUBLIC_KEY"] = opts.bundlePublicKey;
        if (opts.enableDeltaUpdates) cfg.modResults["NITROPUSH_ENABLE_DELTA_UPDATES"] = true;
        return cfg;
      });
    }

    // ── Android <meta-data> ───────────────────────────────────────────────
    // Mirror of the iOS keys; the SDK reads them from AndroidManifest <application>.
    if (opts.android) {
      config = withAndroidManifest(config, (cfg) => {
        const mainApp = AndroidConfig.Manifest.getMainApplication(cfg.modResults);
        if (!mainApp) return cfg;
        const MANAGED_KEYS = [
          "NITROPUSH_DEPLOYMENT_KEY",
          "NITROPUSH_SERVER_URL",
          "NITROPUSH_STORAGE_BASE_URL",
          "NITROPUSH_BUNDLE_PUBLIC_KEY",
          "NITROPUSH_ENABLE_DELTA_UPDATES",
        ];
        // Remove stale entries first (idempotent re-runs).
        mainApp["meta-data"] = (mainApp["meta-data"] ?? []).filter(
          (m) => !MANAGED_KEYS.includes(m.$["android:name"]),
        );
        const entries: [string, string][] = [];
        if (opts.deploymentKey) entries.push(["NITROPUSH_DEPLOYMENT_KEY", opts.deploymentKey]);
        if (opts.deploymentKey && opts.serverUrl) entries.push(["NITROPUSH_SERVER_URL", opts.serverUrl]);
        if (opts.deploymentKey && opts.storageBaseUrl) entries.push(["NITROPUSH_STORAGE_BASE_URL", opts.storageBaseUrl]);
        if (opts.deploymentKey && opts.bundlePublicKey) entries.push(["NITROPUSH_BUNDLE_PUBLIC_KEY", opts.bundlePublicKey]);
        if (opts.enableDeltaUpdates) entries.push(["NITROPUSH_ENABLE_DELTA_UPDATES", "true"]);
        for (const [name, value] of entries) {
          mainApp["meta-data"].push({ $: { "android:name": name, "android:value": value } });
        }
        return cfg;
      });
    }

    if (opts.ios) {
      config = withAppDelegate(config, (cfg) => {
        if (cfg.modResults.language !== "swift") {
          // Older Obj-C templates aren't supported by the auto-patcher.
          // Manual instructions live in docs/nitro-module.md.
          return cfg;
        }
        cfg.modResults.contents = patchAppDelegateSwift(
          cfg.modResults.contents,
          {
            serverUrl: opts.serverUrl || undefined,
            deploymentKey: opts.deploymentKey || undefined,
            storageBaseUrl: opts.storageBaseUrl || undefined,
            bundlePublicKey: opts.bundlePublicKey || undefined,
            nativeConfigure: opts.nativeConfigure,
          },
        );
        return cfg;
      });

      // The NitroPush Swift module uses C++ interop, so the consuming app
      // target must opt in as well. Nitrogen owns the pod header layout;
      // this plugin deliberately does not rewrite umbrella headers.
      config = withDangerousMod(config, [
        "ios",
        (cfg) => {
          const podfilePath = path.join(
            cfg.modRequest.platformProjectRoot,
            "Podfile",
          );
          if (fs.existsSync(podfilePath)) {
            const original = fs.readFileSync(podfilePath, "utf8");
            const patched = patchExpoPodfile(original);
            if (patched !== original) {
              fs.writeFileSync(podfilePath, patched);
            }
          }
          return cfg;
        },
      ]);
    }

    if (opts.android) {
      config = withMainApplication(config, (cfg) => {
        if (cfg.modResults.language !== "kt") {
          return cfg;
        }
        cfg.modResults.contents = patchMainApplicationKotlin(
          cfg.modResults.contents,
        );
        return cfg;
      });
    }
  
    return config;
  }; 
  
  // ─── iOS: Podfile Swift/C++ interop settings ─────────────────────────────────

  const TAG_IOS_PODFILE_NITROMODULES_CXX = "nitropush-ios-podfile-nitromodules-cxx";
  
  /**
   * Ruby snippet that enables Swift/C++ interop and makes NitroModules' own
   * private headers visible while Expo validates its modular public headers.
   * Nothing under Pods is rewritten; Nitrogen remains the owner of generated
   * header visibility.
   *
   * @internal Exported for unit testing.
   */
  export const NITROMODULES_PODFILE_CXX_SNIPPET = [
    "    # NitroModules + NitroPush — Xcode 26 C++ interop chain fix.",
    "    # Step 1: C++ build settings on the NitroModules and NitroPush pod targets.",
    "    installer.pods_project.targets.each do |target|",
    "      if ['NitroModules', 'NitroPush'].include?(target.name)",
    "        target.build_configurations.each do |config|",
    "          config.build_settings['CLANG_CXX_LANGUAGE_STANDARD'] = 'c++20'",
    "          config.build_settings['SWIFT_OBJC_INTEROP_MODE'] = 'objcxx'",
    "          config.build_settings['CLANG_ALLOW_NON_MODULAR_INCLUDES_IN_FRAMEWORK_MODULES'] = 'YES'",
    "        end",
    "      end",
    "    end",
    "    # Step 2: The main app target must also opt into C++ interop because",
    "    #   NitroPush's .swiftmodule is tagged 'built with C++ interop' and Swift",
    "    #   refuses to import it from a non-C++-interop compilation unit.",
    "    installer.aggregate_targets.each do |agg|",
    "      agg.user_project.targets.each do |target|",
    "        target.build_configurations.each do |config|",
    "          config.build_settings['SWIFT_OBJC_INTEROP_MODE'] = 'objcxx'",
    "          header_paths = Array(config.build_settings['HEADER_SEARCH_PATHS'] || '$(inherited)')",
    "          nitro_private_headers = '$(PODS_ROOT)/Headers/Private/NitroModules'",
    "          header_paths << nitro_private_headers unless header_paths.include?(nitro_private_headers)",
    "          config.build_settings['HEADER_SEARCH_PATHS'] = header_paths",
    "        end",
    "      end",
    "      agg.user_project.save",
    "    end",
  ].join("\n");
  
  /**
   * Injects the Swift/C++ interop settings into the generated Podfile's
   * existing `post_install do |installer|` block.
   *
   * Idempotent via the generated mod markers
   * markers that `mergeContents` writes around the injected snippet.
   *
   * @internal Exported for unit testing.
   */
  export function patchExpoPodfile(contents: string): string {
    // Anchor on the Expo-specific last argument of `react_native_post_install`.
    // The next line is the call's closing `)`, so offset 2 lands AFTER the
    // call ends but still inside the surrounding `post_install do |installer|`
    // block — exactly where we want both patches to run.
    const cxxExpo = mergeContents({
      src: contents,
      newSrc: NITROMODULES_PODFILE_CXX_SNIPPET,
      anchor:
        /:ccache_enabled\s*=>\s*ccache_enabled\?\(podfile_properties\),?\s*$/m,
      offset: 2,
      tag: TAG_IOS_PODFILE_NITROMODULES_CXX,
      comment: "#",
    });
    if (cxxExpo.didMerge || cxxExpo.didClear) return cxxExpo.contents;

    // Bare RN template (no Expo ccache helper): anchor on the last common
    // argument of react_native_post_install. Offset 2 lands past the `)`.
    const cxxRn = mergeContents({
      src: contents,
      newSrc: NITROMODULES_PODFILE_CXX_SNIPPET,
      anchor: /:mac_catalyst_enabled\s*=>\s*(?:true|false),?\s*$/m,
      offset: 2,
      tag: TAG_IOS_PODFILE_NITROMODULES_CXX,
      comment: "#",
    });
    return cxxRn.contents;
  }
  
  
  
  // ─── iOS: AppDelegate patching ────────────────────────────────────────────────
  
  const TAG_IOS_IMPORT = "nitropush-ios-import";
  const TAG_IOS_CONFIGURE = "nitropush-ios-configure";
  const TAG_IOS_BUNDLE_URL = "nitropush-ios-bundle-url";
  const TAG_IOS_NOTIFY_APP_READY = "nitropush-ios-notify-app-ready";
  
  /**
   * Patches `AppDelegate.swift` so:
   *   1. `NitroPush` is imported.
   *   2. `configure()` + a background update-check task are injected into
   *      `application(_:didFinishLaunchingWithOptions:)` (only when
   *      serverUrl + deploymentKey are provided).
   *   3. React Native's bundle loader checks the SDK's active bundle before
   *      falling back to the binary bundle (`bundleURL()` override).
   *
   * The plugin deliberately does not call `notifyAppReady()`: only JS can
   * prove the new bundle rendered successfully. It also removes the legacy
   * generated lifecycle callback when upgrading an already-prebuilt app.
   *
   * @internal Exported for unit testing.
   */
  export function patchAppDelegateSwift(
    contents: string,
    opts: {
      serverUrl?: string;
      deploymentKey?: string;
      storageBaseUrl?: string;
      bundlePublicKey?: string;
      nativeConfigure?: boolean;
    } = {},
  ): string {
    let src = contents;

    // Older plugin versions confirmed an OTA merely because UIKit became
    // active, before React rendered. Remove that generated block during the
    // next prebuild so rollback remains armed until JS explicitly confirms.
    src = removeContents({ src, tag: TAG_IOS_NOTIFY_APP_READY }).contents;
  
    // 1. import NitroPush — after the first import statement.
    src = mergeContents({
      src,
      newSrc: "import NitroPush",
      anchor: /^import .+$/m,
      offset: 1,
      tag: TAG_IOS_IMPORT,
      comment: "//",
    }).contents;
  
    // 2. configure() + background update-check — before the RN factory setup.
    // Only injected when nativeConfigure=true (bare-RN pattern).
    // Expo apps skip this; JS calls the no-arg configure() which reads from Info.plist.
    if (opts.nativeConfigure && opts.deploymentKey) {
      // Build NPConfig args — only include URL overrides when non-default.
      const DEFAULT_SERVER = "https://api.nitropush.org";
      const DEFAULT_CDN    = "https://cdn.nitropush.org";
      const configArgs: string[] = [
        `        deploymentKey:  ${JSON.stringify(opts.deploymentKey)}`,
      ];
      if (opts.serverUrl && opts.serverUrl !== DEFAULT_SERVER) {
        configArgs.push(`        serverUrl:      ${JSON.stringify(opts.serverUrl)}`);
      }
      if (opts.storageBaseUrl && opts.storageBaseUrl !== DEFAULT_CDN) {
        configArgs.push(`        storageBaseUrl: ${JSON.stringify(opts.storageBaseUrl)}`);
      }
      if (opts.bundlePublicKey) {
        configArgs.push(`        bundlePublicKey: ${JSON.stringify(opts.bundlePublicKey)}`);
      }
      const configLines: string[] = [
        "    do {",
        `      try NitroPushSdk.shared.configure(NPConfig(`,
        ...configArgs.map((a, i) => a + (i < configArgs.length - 1 ? "," : "")),
        "      ))",
        "    } catch {",
        '      NSLog("[NitroPush] configure failed: %@", "\\(error)")',
        "    }",
        "    Task.detached(priority: .background) {",
        "      do {",
        "        guard let remote = try await NitroPushSdk.shared.checkForUpdate() else { return }",
        "        let local = try await NitroPushSdk.shared.downloadUpdate(remote)",
        "        try await NitroPushSdk.shared.installUpdate(",
        "          pkg: local, installMode: .onNextRestart, minimumBackgroundDuration: 0)",
        "      } catch {",
        '        NSLog("[NitroPush] background sync failed: %@", "\\(error)")',
        "      }",
        "    }",
      ];
  
      try {
        src = mergeContents({
          src,
          newSrc: configLines.join("\n"),
          anchor: /let delegate = ReactNativeDelegate\(\)/m,
          offset: 0,
          tag: TAG_IOS_CONFIGURE,
          comment: "//",
        }).contents;
      } catch {
        console.warn(
          "[@nitropush/react-native] AppDelegate.swift: could not locate `let delegate = ReactNativeDelegate()`. " +
            "Skipping native configure injection — call configure() from JS instead.",
        );
      }
    }
  
    // 3. activeBundleURL() short-circuit at the top of bundleURL().
    try {
      src = mergeContents({
        src,
        newSrc:
          "#if !DEBUG\n" +
          "    if let nitropushBundleURL = NitroPushSdk.shared.activeBundleURL() { return nitropushBundleURL }\n" +
          "#endif",
        anchor: /override\s+func\s+bundleURL\s*\(\s*\)\s*->\s*URL\?\s*\{\s*$/m,
        offset: 1,
        tag: TAG_IOS_BUNDLE_URL,
        comment: "//",
      }).contents;
    } catch {
      console.warn(
        "[@nitropush/react-native] AppDelegate.swift has no bundleURL() override; " +
          "skipping iOS bundle-URL injection. See docs/nitro-module.md for the " +
          "manual snippet.",
      );
    }
  
    return src;
  }

  // ─── Android: MainApplication patching ───────────────────────────────────────
  
  const TAG_ANDROID_IMPORT = "nitropush-android-import";
  const TAG_ANDROID_INSTALL = "nitropush-android-install";
  const TAG_ANDROID_BUNDLE_FILE = "nitropush-android-bundle-file";
  
  /**
   * Patches `MainApplication.kt` so the SDK gets initialized at app start
   * AND so React Native picks up the SDK's active bundle file. Same
   * `// @generated` discipline as the iOS patch.
   *
   * @internal Exported for unit testing.
   */
  export function patchMainApplicationKotlin(contents: string): string {
    let src = contents;
  
    src = mergeContents({
      src,
      newSrc: "import com.nitropush.sdk.NitroPushSdk",
      anchor: /^import .+$/m,
      offset: 1,
      tag: TAG_ANDROID_IMPORT,
      comment: "//",
    }).contents;
  
    try {
      src = mergeContents({
        src,
        newSrc: "    NitroPushSdk.install(this)",
        anchor: /super\.onCreate\(\)\s*$/m,
        offset: 1,
        tag: TAG_ANDROID_INSTALL,
        comment: "//",
      }).contents;
    } catch {
      console.warn(
        "[@nitropush/react-native] MainApplication.kt has no super.onCreate() call; " +
          "skipping NitroPushSdk.install injection.",
      );
    }
  
    const legacyHostAnchor =
      /object\s*:\s*DefaultReactNativeHost\s*\([^)]*\)\s*\{\s*$/m;
    const expoReactHostAnchor =
      /ExpoReactHostFactory\.getDefaultReactHost\s*\(\s*$/m;

    try {
      if (legacyHostAnchor.test(src)) {
        src = mergeContents({
          src,
          newSrc:
            "      override fun getJSBundleFile(): String? =\n" +
            "        if (BuildConfig.DEBUG) super.getJSBundleFile()\n" +
            "        else NitroPushSdk.shared.activeBundleFile() ?: super.getJSBundleFile()",
          anchor: legacyHostAnchor,
          offset: 1,
          tag: TAG_ANDROID_BUNDLE_FILE,
          comment: "//",
        }).contents;
      } else if (expoReactHostAnchor.test(src)) {
        // Expo SDK 57+ creates a ReactHost directly. Its factory exposes the
        // production bundle path as a named argument, so inject NitroPush
        // there while leaving debug builds on Metro.
        src = mergeContents({
          src,
          newSrc:
            "      jsBundleFilePath =\n" +
            "        if (BuildConfig.DEBUG) null else NitroPushSdk.shared.activeBundleFile(),",
          anchor: expoReactHostAnchor,
          offset: 1,
          tag: TAG_ANDROID_BUNDLE_FILE,
          comment: "//",
        }).contents;
      } else {
        throw new Error("unsupported React host template");
      }
    } catch {
      console.warn(
        "[@nitropush/react-native] MainApplication.kt has no supported React host block; " +
          "skipping active bundle injection.",
      );
    }
  
    return src;
  }
  
  // ─────────────────────────────────────────────────────────────────────────────

  export default createRunOncePlugin(withNitroPush, PKG_NAME, PKG_VERSION);
