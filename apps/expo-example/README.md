# @nitropush/expo-example

Expo SDK 54 project (RN 0.81, expo-router, new architecture) scaffolded
with `npx create-expo-app@latest --template default`, then wired to
`@nitropush/react-native` via the SDK's config plugin.

## What's NitroPush-specific

| File | What was added |
| ---- | -------------- |
| `package.json` | Workspace name `@nitropush/expo-example`, dep on `@nitropush/react-native` (workspace) + `react-native-nitro-modules` |
| `app.json` | `ios.bundleIdentifier`, `android.package`, `plugins[…]` entry for `@nitropush/react-native` (`{ ios: true, android: true }`), `extra.nitropushServerUrl` / `extra.nitropushDeploymentKey` |
| `app/(tabs)/index.tsx` | Demo screen — `configure()`, `notifyAppReady()`, `sync()` with status + progress, `restartApp(true)` |

The native side (AppDelegate / MainApplication patches) is injected at
`expo prebuild` time by the config plugin — there is no manual native
edit to make in this example.

## Run

```bash
yarn install                       # at the monorepo root, once
yarn workspace @nitropush/expo-example ios    # prebuilds + runs iOS
yarn workspace @nitropush/expo-example android
```

Point the demo at your dev server by editing `extra.nitropushServerUrl`
and `extra.nitropushDeploymentKey` in `app.json` (or set them via EAS
secrets in production).
