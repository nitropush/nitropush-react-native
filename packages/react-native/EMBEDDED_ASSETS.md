# Embedded asset reuse

The native downloader checks, in order: verified content-hash cache, the native binary's embedded-asset inventory, a compatible delta, then a full download. A local candidate is copied in bounded chunks and checked against the requested SHA-256 and exact size before reuse. Reused files do not make an asset delivery request or emit delta-applied/failed telemetry. The signed manifest, release-size limit, anti-replay checks and rollback sequence still apply.

This requires a new native build. An older binary or one without the inventory safely falls back to normal downloads. No binary resources are uploaded by this feature, and there is no device-side bundle/APK directory scan.

## Expo

The NitroPush config plugin adds inventory generation after the existing iOS Metro/Hermes bundle phase. Re-run `expo prebuild` as part of your usual native rebuild workflow to adopt it. It preserves the existing bundle command, private build configuration and failure exit status. Debug builds that skip bundling also skip inventory generation.

On Android, the autolinked NitroPush library adds an inventory action to React Native's `createBundle*JsAndAssets` tasks. It writes `nitropush-embedded-assets.json` into that task's generated assets directory before Android merges the assets. No separate app Gradle edit is required. The integration has been exercised against Expo SDK 54 / React Native 0.81.5 / Gradle 8.14.3.

## Bare React Native

Android uses the same automatic Gradle integration. For iOS, append the following to the **existing** "Bundle React Native code and images" build phase, immediately after the React Native bundle command. Do not add a separate phase that could run before bundling:

```sh
NITROPUSH_BUNDLE_STATUS=$?
if [ "$NITROPUSH_BUNDLE_STATUS" -ne 0 ]; then exit "$NITROPUSH_BUNDLE_STATUS"; fi
if [ "${SKIP_BUNDLING:-0}" != "1" ]; then
  if [ -z "${NODE_BINARY:-}" ]; then
    if [ -f "$PODS_ROOT/../.xcode.env" ]; then . "$PODS_ROOT/../.xcode.env"; fi
    if [ -f "$PODS_ROOT/../.xcode.env.local" ]; then . "$PODS_ROOT/../.xcode.env.local"; fi
  fi
  NITROPUSH_NODE_BINARY="${NODE_BINARY:-node}"
  NITROPUSH_INDEX_SCRIPT="$("$NITROPUSH_NODE_BINARY" --print "require.resolve('@nitropush/react-native/scripts/embedded-assets.cjs')")"
  "$NITROPUSH_NODE_BINARY" "$NITROPUSH_INDEX_SCRIPT" --platform ios \
    --bundle-root "$CONFIGURATION_BUILD_DIR/$UNLOCALIZED_RESOURCES_FOLDER_PATH"
fi
```

The supported generator can also be run against explicit build outputs, before packaging. It writes an inventory only; it does not build, publish or upload anything:

```sh
node node_modules/@nitropush/react-native/scripts/embedded-assets.cjs \
  --platform android --assets-root /path/to/generated/assets \
  --resources-root /path/to/generated/res --bundle-name index.android.bundle

node node_modules/@nitropush/react-native/scripts/embedded-assets.cjs \
  --platform ios --bundle-root /path/to/YourApp.app --bundle-name main.jsbundle
```

Use the final Metro/Hermes output directories, not project-source asset directories. The optional `--output` changes only the inventory's output path; it remains your responsibility to package it at the native bundle/assets root as `nitropush-embedded-assets.json`.

## NativeScript

The existing `@nitropush/nativescript/scripts/prepare.cjs` after-prepare hook automatically inventories the generated `app/` tree and writes `app/nitropush-embedded-assets.json`. The CLI excludes this binary-owned metadata from OTA uploads. Build the workspace NativeScript package before preparing the app so the shared native sources and generator are synchronized.

For a custom NativeScript packaging pipeline, the supported command is:

```sh
node node_modules/@nitropush/nativescript/scripts/embedded-assets.cjs \
  --platform nativescript-ios --app-root /path/to/generated/app
```

Use `nativescript-android` for Android. Preserve the resulting file inside the packaged `app/` directory.

## Byte identity and limits

The index contains hashes, sizes and native resource locations, not copies of the assets. Android drawable entries retain their density; the runtime resolves the packaged resource path and reads the matching entry from the base or split APK. iOS uses final bundle-relative Metro paths. NativeScript reads the immutable bundled app files, not a mutable extracted/active OTA tree.

Reuse requires **identical bytes**. PNG crunching, asset-catalog compilation/thinning, resource rewriting or removed density variants can make a source unavailable or change it. Such files are downloaded normally; the SDK does not treat a matching resource name as proof and does not reconstruct/re-encode images. Asset-catalog-only images are not indexed as loose files. Retaining exact originals as extra native resources would increase binary size and is not done automatically.

Inventory generation is bounded to 20,000 visited entries, depth 32, 10,000 indexed files, 128 MiB of content hashing and 2 MiB of JSON. Files outside per-file or aggregate reuse byte limits are skipped, with a count in the generator result. Runtime loading is bounded to the same JSON/file limits and at most eight locations per hash and 32 APK paths. Invalid/missing inventory entries are misses, never permission to skip integrity checks.

The updated client also advertises asset-proxy support on device-authenticated HTTPS manifest requests. Only exact configured-API `/api/sdk/asset` URLs with the requested hash and a single ticket receive device proof. CDN URLs receive no device credentials; redirects are rejected. Local reuse produces no asset transfer to meter. Server-side billing activation and pricing are separate from this SDK feature.
