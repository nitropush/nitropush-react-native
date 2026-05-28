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
 *                             inject `applicationDidBecomeActive` override
 *                             that calls `NitroPushSdk.shared.notifyAppReady()`
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
 *   ▸ NitroModules.podspec    patch the 33 missing `public_header_files`
 *                             entries that upstream omits — required for
 *                             Xcode 26+ (see scripts/patch-nitro-modules.js
 *                             for full context)
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
 *           "deploymentKey": "nl_live_...",
 *           "storageBaseUrl": "https://cdn.example.com/bundles",
 *         }]
 *       ]
 *     }
 *   }
 *
 * `serverUrl` / `deploymentKey` / `storageBaseUrl` are optional: if omitted
 * the native configure+update-check block is skipped and you call
 * `configure()` from JS instead.
 *
 * Network security (ATS / NSExceptionDomains on iOS, network_security_config
 * on Android) is the host app's responsibility and is intentionally NOT
 * managed by this plugin. Use your own config plugin or manually edit
 * Info.plist / network_security_config.xml.
 */

import {
    type ConfigPlugin,
    createRunOncePlugin,
    withAppDelegate,
    withDangerousMod,
    withMainApplication,
  } from "@expo/config-plugins";
  import { mergeContents } from "@expo/config-plugins/build/utils/generateCode";
  import * as fs from "fs";
  import * as path from "path";
  
  const PKG_NAME = "@nitropush/react-native";
  const PKG_VERSION = "0.1.0";
  
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
    deploymentKey?: string;
    /**
     * Object-storage base URL for bundle downloads (e.g. your MinIO / S3
     * bucket URL). Required when `serverUrl` is set.
     */
    storageBaseUrl?: string;
  }
  
  const withNitroPush: ConfigPlugin<NitroPushPluginProps | void> = (
    config,
    props,
  ) => {
    const opts: Required<NitroPushPluginProps> = {
      ios: props?.ios ?? true,
      android: props?.android ?? true,
      serverUrl: props?.serverUrl ?? "",
      deploymentKey: props?.deploymentKey ?? "",
      storageBaseUrl: props?.storageBaseUrl ?? "",
    };
  
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
          },
        );
        return cfg;
      });

      // Podfile post_install umbrella patch (Xcode 26): wraps the
      // nitrogen-generated C++ `.hpp` imports in `#ifdef __cplusplus`.
      // Idempotent via the `// @generated` tags mergeContents writes
      // inside patchExpoPodfile, so re-running prebuild is a no-op.
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
  
  // ─── iOS: Podfile post_install umbrella patch ────────────────────────────────
  
  const TAG_IOS_PODFILE_UMBRELLA = "nitropush-ios-podfile-umbrella-patch";
  
  /**
   * The Ruby snippet injected into the generated Podfile's post_install block.
   *
   * It rewrites `Pods/Target Support Files/NitroPush/NitroPush-umbrella.h`
   * so the nitrogen-generated C++ `.hpp` `#imports` are wrapped in
   * `#ifdef __cplusplus`. Without this, Xcode 26 validates the umbrella in
   * pure ObjC mode and the `namespace margelo::nitro …` declarations fail
   * with "unknown type name 'namespace'". Swift's C++ interop
   * (`SWIFT_OBJC_INTEROP_MODE = objcxx`) still compiles the umbrella in ObjC++,
   * so it picks up the guarded imports and resolves the `margelo::nitro::…`
   * types referenced by the nitrogen-generated Swift typealiases.
   *
   * @internal Exported for unit testing.
   */
  export const NITROPUSH_PODFILE_UMBRELLA_SNIPPET = [
    "    # NitroPush — Xcode 26 strict-modular-headers fix.",
    "    nitropush_umbrella = File.join(",
    "      installer.sandbox.root.to_s,",
    "      'Target Support Files/NitroPush/NitroPush-umbrella.h'",
    "    )",
    "    if File.exist?(nitropush_umbrella)",
    "      nitropush_content = File.read(nitropush_umbrella)",
    "      nitropush_hpp = nitropush_content.scan(/^#import\\s+\"[^\"]+\\.hpp\"\\s*$/).join(\"\\n\")",
    "      unless nitropush_hpp.empty? || nitropush_content.include?('#ifdef __cplusplus')",
    "        nitropush_guarded = \"#ifdef __cplusplus\\n#{nitropush_hpp}\\n#endif\"",
    "        nitropush_patched = nitropush_content.gsub(/^#import\\s+\"[^\"]+\\.hpp\"\\s*\\n/, '')",
    "                                             .sub(/(FOUNDATION_EXPORT double)/, \"#{nitropush_guarded}\\n\\n\\\\1\")",
    "        File.write(nitropush_umbrella, nitropush_patched)",
    "      end",
    "    end",
  ].join("\n");
  
  /**
   * Injects {@link NITROPUSH_PODFILE_UMBRELLA_SNIPPET} into the Expo-generated
   * Podfile's existing `post_install do |installer|` block, just after the
   * `react_native_post_install(...)` call.
   *
   * Idempotent via the `// @generated begin nitropush-ios-podfile-umbrella-patch`
   * markers that `mergeContents` writes around the injected snippet.
   *
   * @internal Exported for unit testing.
   */
  export function patchExpoPodfile(contents: string): string {
    // Anchor on the Expo-specific last argument of `react_native_post_install`.
    // The next line is the call's closing `)`, so offset 2 lands AFTER the
    // call ends but still inside the surrounding `post_install do |installer|`
    // block — exactly where we want the umbrella patch to run.
    const expoAnchor = mergeContents({
      src: contents,
      newSrc: NITROPUSH_PODFILE_UMBRELLA_SNIPPET,
      anchor:
        /:ccache_enabled\s*=>\s*ccache_enabled\?\(podfile_properties\),?\s*$/m,
      offset: 2,
      tag: TAG_IOS_PODFILE_UMBRELLA,
      comment: "#",
    });
    if (expoAnchor.didMerge || expoAnchor.didClear) return expoAnchor.contents;
  
    // Bare RN template (no Expo ccache helper): anchor on the last common
    // argument of react_native_post_install. Offset 2 lands past the `)`.
    const rnAnchor = mergeContents({
      src: contents,
      newSrc: NITROPUSH_PODFILE_UMBRELLA_SNIPPET,
      anchor: /:mac_catalyst_enabled\s*=>\s*(?:true|false),?\s*$/m,
      offset: 2,
      tag: TAG_IOS_PODFILE_UMBRELLA,
      comment: "#",
    });
    return rnAnchor.contents;
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
   *   4. `applicationDidBecomeActive` is overridden to call `notifyAppReady()`.
   *
   * @internal Exported for unit testing.
   */
  export function patchAppDelegateSwift(
    contents: string,
    opts: {
      serverUrl?: string;
      deploymentKey?: string;
      storageBaseUrl?: string;
    } = {},
  ): string {
    let src = contents;
  
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
    if (opts.serverUrl && opts.deploymentKey) {
      const configLines: string[] = [
        "    do {",
        "      try NitroPushSdk.shared.configure(NlConfig(",
        `        serverUrl:      ${JSON.stringify(opts.serverUrl)},`,
        `        deploymentKey:  ${JSON.stringify(opts.deploymentKey)},`,
      ];
      if (opts.storageBaseUrl) {
        configLines.push(`        storageBaseUrl: ${JSON.stringify(opts.storageBaseUrl)}`);
      }
      configLines.push(
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
      );
  
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
  
    // 4. notifyAppReady() in applicationDidBecomeActive.
    //    Anchor: the `// Linking API` comment that follows didFinishLaunchingWithOptions
    //    in the standard Expo AppDelegate template.
    try {
      src = mergeContents({
        src,
        newSrc: [
          "",
          "  public override func applicationDidBecomeActive(_ application: UIApplication) {",
          "    super.applicationDidBecomeActive(application)",
          "    NitroPushSdk.shared.notifyAppReady()",
          "  }",
          "",
        ].join("\n"),
        anchor: /\/\/ Linking API/m,
        offset: 0,
        tag: TAG_IOS_NOTIFY_APP_READY,
        comment: "//",
      }).contents;
    } catch {
      // Expo template may not have the `// Linking API` comment — fall back to
      // anchoring on the `return super.application(...)` line inside
      // didFinishLaunchingWithOptions and inserting two lines after it (past the
      // closing `}`).
      try {
        src = mergeContents({
          src,
          newSrc: [
            "",
            "  public override func applicationDidBecomeActive(_ application: UIApplication) {",
            "    super.applicationDidBecomeActive(application)",
            "    NitroPushSdk.shared.notifyAppReady()",
            "  }",
            "",
          ].join("\n"),
          anchor:
            /return super\.application\(\s*application\s*,\s*didFinishLaunchingWithOptions\s*:/m,
          offset: 2,
          tag: TAG_IOS_NOTIFY_APP_READY,
          comment: "//",
        }).contents;
      } catch {
        console.warn(
          "[@nitropush/react-native] AppDelegate.swift: could not inject applicationDidBecomeActive. " +
            "Add `NitroPushSdk.shared.notifyAppReady()` manually in that callback.",
        );
      }
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
  
    try {
      src = mergeContents({
        src,
        newSrc:
          "      override fun getJSBundleFile(): String? =\n" +
          "        if (BuildConfig.DEBUG) super.getJSBundleFile()\n" +
          "        else NitroPushSdk.shared.activeBundleFile() ?: super.getJSBundleFile()",
        anchor: /object\s*:\s*DefaultReactNativeHost\s*\([^)]*\)\s*\{\s*$/m,
        offset: 1,
        tag: TAG_ANDROID_BUNDLE_FILE,
        comment: "//",
      }).contents;
    } catch {
      console.warn(
        "[@nitropush/react-native] MainApplication.kt has no DefaultReactNativeHost block; " +
          "skipping getJSBundleFile injection.",
      );
    }
  
    return src;
  }
  
  // ─────────────────────────────────────────────────────────────────────────────
  
  export default createRunOncePlugin(withNitroPush, PKG_NAME, PKG_VERSION);
  