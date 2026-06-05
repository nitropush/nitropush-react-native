# @nitropush/react-native

React Native SDK for [NitroPush](https://docs.nitropush.org) — over-the-air (OTA) JavaScript bundle updates for React Native and Expo apps.

Built on [Nitro Modules](https://nitro.margelo.com) for zero-bridge native performance on both iOS and Android.

**[→ Full documentation at docs.nitropush.org](https://docs.nitropush.org)**

---

## Installation

```sh
npm install @nitropush/react-native react-native-nitro-modules
# or
yarn add @nitropush/react-native react-native-nitro-modules
```

Then run `pod install` for iOS:

```sh
cd ios && pod install
```

---

## Native setup

### Android — `MainApplication.kt`

Call `NitroPushSdk.install(this)` as the very first line of `onCreate`:

```kotlin
import com.nitropush.sdk.NitroPushSdk

class MainApplication : Application(), ReactApplication {
  override fun onCreate() {
    NitroPushSdk.install(this)   // ← must be first
    super.onCreate()
    // ...
  }
}
```

Add your deployment key (and any optional overrides) as `<meta-data>` inside `<application>` in `AndroidManifest.xml`:

```xml
<application ...>

  <!-- Required -->
  <meta-data android:name="NITROPUSH_DEPLOYMENT_KEY"
             android:value="YOUR_DEPLOYMENT_KEY" />

  <!-- Optional — defaults shown -->
  <meta-data android:name="NITROPUSH_SERVER_URL"
             android:value="https://api.nitropush.org" />
  <meta-data android:name="NITROPUSH_STORAGE_BASE_URL"
             android:value="https://cdn.nitropush.org" />

</application>
```

### iOS — `AppDelegate.swift`

Call `NitroPushSdk.install(self)` before the React bridge starts:

```swift
import NitroPushNative

@UIApplicationMain
class AppDelegate: RCTAppDelegate {
  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    NitroPushSdk.install(self)   // ← must be before super
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }
}
```

Add your deployment key to `Info.plist`:

```xml
<!-- Required -->
<key>NITROPUSH_DEPLOYMENT_KEY</key>
<string>YOUR_DEPLOYMENT_KEY</string>

<!-- Optional — defaults shown -->
<key>NITROPUSH_SERVER_URL</key>
<string>https://api.nitropush.org</string>

<key>NITROPUSH_STORAGE_BASE_URL</key>
<string>https://cdn.nitropush.org</string>
```

> All plist / manifest keys and their defaults are documented at [docs.nitropush.org/native-setup](https://docs.nitropush.org/native-setup).

---

## Quick start

```ts
import { configure, sync, InstallMode, SyncStatus } from "@nitropush/react-native";
import { useEffect } from "react";

// Reads NITROPUSH_DEPLOYMENT_KEY (and optional overrides) from
// Info.plist on iOS or AndroidManifest meta-data on Android.
const client = configure();

export default function App() {
  useEffect(() => {
    client.notifyAppReady();
    sync(client, { installMode: InstallMode.ON_NEXT_RESUME }, (status) => {
      if (status === SyncStatus.UPDATE_INSTALLED) {
        console.log("Update installed — will apply on next resume");
      }
    });
  }, []);

  // ...
}
```

---

## Low-level API

```ts
const remote = await client.checkForUpdate();

if (remote) {
  const local = await remote.download((progress) => {
    console.log(`${progress.receivedBytes} / ${progress.totalBytes} bytes`);
  });
  await local.install(InstallMode.ON_NEXT_RESTART, 0);
}
```

---

## Explicit JS config

Use `configureWith` when you need to override the native config at runtime (e.g. pointing at a staging server from a debug build):

```ts
import { configureWith } from "@nitropush/react-native";

const client = configureWith({
  serverUrl: "https://api.nitropush.org",
  deploymentKey: "YOUR_DEPLOYMENT_KEY",
  storageBaseUrl: "https://cdn.nitropush.org",
});
```

---

## Key exports

| Export | Description |
|--------|-------------|
| `configure()` | Create a client — reads config from Info.plist / AndroidManifest |
| `configureWith(config)` | Create a client with explicit JS-side config |
| `sync(client, options?, callback?)` | High-level check → download → install in one call |
| `InstallMode` | `IMMEDIATE` · `ON_NEXT_RESTART` · `ON_NEXT_RESUME` · `ON_NEXT_SUSPEND` |
| `SyncStatus` | `CHECKING_FOR_UPDATE` · `DOWNLOADING_PACKAGE` · `UPDATE_INSTALLED` · … |
| `NitroPushConfig` | Config shape for `configureWith` |
| `SyncOptions` | Options for `sync()` (install mode, dialogs, rollback, …) |

---

## Documentation

- [Getting started](https://docs.nitropush.org/getting-started)
- [Native setup (iOS & Android)](https://docs.nitropush.org/native-setup)
- [Expo / managed workflow](https://docs.nitropush.org/expo)
- [SDK API reference](https://docs.nitropush.org/sdk)
- [CLI reference](https://docs.nitropush.org/cli)
- [Release channels & rollouts](https://docs.nitropush.org/rollouts)
- [Bundle signing](https://docs.nitropush.org/bundle-signing)

---

## License

MIT © [NitroPush](https://nitropush.org)
