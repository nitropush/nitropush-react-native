# @nitropush/expo-example

Expo SDK 54 project (RN 0.81, expo-router, new architecture) scaffolded
with `npx create-expo-app@latest --template default`, then wired to
`@nitropush/react-native` via the SDK's config plugin.

## What's NitroPush-specific

| File | What was added |
| ---- | -------------- |
| `package.json` | Workspace name `@nitropush/expo-example`, dep on `@nitropush/react-native` (workspace) + `react-native-nitro-modules` |
| `app.config.js` | Exposes only the non-secret native delta mode to the demo UI |
| `app.json` | `ios.bundleIdentifier`, `android.package`, and the non-secret `@nitropush/react-native` plugin settings |
| `plugins/with-nitropush-native.js` | Reads the deployment key only inside native config mods, keeping it out of Expo's public config |
| `lib/nitropush.ts` | Creates one hosted client with `configure()` at module scope |
| `app/(tabs)/index.tsx` | Demo screen — `notifyAppReady()`, `sync()` with status + progress, `restartApp(true)` |
| `app/(tabs)/delta-test.tsx` | Runs the one native delta/full mode compiled into the current binary without exposing credentials to JS |

The native side (AppDelegate / MainApplication patches) is injected at
`expo prebuild` time by the config plugin — there is no manual native
edit to make in this example.

## Run

```bash
yarn install                       # at the monorepo root, once
export NITROPUSH_DEPLOYMENT_KEY='<test-environment-key>'
yarn workspace @nitropush/expo-example ios    # prebuilds + runs iOS
yarn workspace @nitropush/expo-example android
```

Point the demo at a different environment by changing the private
`NITROPUSH_DEPLOYMENT_KEY` build variable and re-running prebuild. Never use an
`EXPO_PUBLIC_*` variable for a deployment credential: Expo would inline it into
the JavaScript bundle and source map.

Delta selection is native configuration, so test the modes in separate release
builds:

```bash
# Full-bundle control build (default)
NITROPUSH_ENABLE_DELTA_UPDATES=false npx expo prebuild --platform ios

# Delta-enabled build
NITROPUSH_ENABLE_DELTA_UPDATES=true npx expo prebuild --platform ios
```

For signed projects, include the full `bundlePublicKey` and set
`requireBundleSigning: true` so prebuild fails instead of shipping a binary
without the device trust root.
