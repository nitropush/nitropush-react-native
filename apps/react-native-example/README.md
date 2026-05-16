# @nitropush/react-native-example

Bare React Native 0.85 project scaffolded with
`npx @react-native-community/cli@latest init`, then wired to
`@nitropush/react-native` with manual native patches (since there's no
config plugin in bare RN).

## What's NitroPush-specific

| File | What was added |
| ---- | -------------- |
| `package.json` | Workspace name `@nitropush/react-native-example`, dep on `@nitropush/react-native` + `react-native-nitro-modules` |
| `App.tsx` | Demo screen — `configure()`, `notifyAppReady()`, `sync()` with status + progress, `restartApp(true)` |
| `ios/NitropushRNExample/AppDelegate.swift` | `import NitroPushSDK` + `bundleURL()` short-circuits to `HybridNitroPush.shared.activeBundleURL()` when an OTA bundle is active |
| `android/app/src/main/java/com/nitropushrnexample/MainApplication.kt` | `import com.nitropush.nitrosdk.HybridNitroPush`, `HybridNitroPush.install(this)` in `onCreate()`, `jsBundleFilePath = HybridNitroPush.shared.activeBundleFile()` on `getDefaultReactHost(...)` |

## Run

```bash
yarn install                                          # at the monorepo root, once
cd apps/react-native-example/ios && bundle install && bundle exec pod install && cd -
yarn workspace @nitropush/react-native-example ios
yarn workspace @nitropush/react-native-example android
```

Point the demo at your dev server by editing the `NITROPUSH_SERVER_URL`
and `DEPLOYMENT_KEY` constants at the top of `App.tsx`.
