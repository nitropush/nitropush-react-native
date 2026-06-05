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

## Delta bundle updates (experimental)

Delta updates send only the bytes that changed between the previous bundle and the new one — a bsdiff4 binary patch instead of the full bundle. A typical JS-only change compresses to 5–15% of the full bundle size.

### How it works end-to-end

```
  RELEASE TIME (CLI)                       UPDATE TIME (SDK)
  ─────────────────                        ─────────────────
  Previous bundle                          Device has bundle A cached
       │                                        │
       │  bsdiff A B → patch.bsdiff             │  update check sends
       ▼                                        │  ?currentBundleHash=<sha256-A>
  New bundle B ──────── upload ──────────────── │
  patch.bsdiff ─────── upload                   ▼
  manifest.json ──────────────────────────  server returns manifest
  { bundle: {                              { bundle: {
      sha256: "<sha256-B>",                    sha256: "<sha256-B>",
      objectKey: "bundles/B.hbc",             objectKey: "bundles/B.hbc",
      delta: {                                delta: {
        fromBundleHash: "<sha256-A>",           fromBundleHash: "<sha256-A>",
        patchObjectKey: "deltas/A-B.bsdiff",    patchObjectKey: "deltas/A-B.bsdiff",
        patchSha256: "<sha256-patch>",          patchSha256: "<sha256-patch>",
        patchSize: 42100,                       patchSize: 42100,
        algorithm: "bsdiff4"                    algorithm: "bsdiff4"
      }                                       }
    }                                       }
  }                                       }
                                               │
                                               │  SDK checks:
                                               │  enableDeltaUpdates == true
                                               │  delta.algorithm == "bsdiff4"
                                               │  activeBundleHash == delta.fromBundleHash
                                               │
                                        ┌──────▼──────┐
                                        │  conditions │
                                        │    met?     │
                                        └──────┬──────┘
                                          yes  │  no
                                    ┌──────────┴──────────┐
                                    ▼                      ▼
                             download patch          download full
                             verify sha256            bundle B
                             bspatch(A, patch) → B
                             verify output sha256
                             (fall back if mismatch)
```

The SDK **always falls back** to downloading the full bundle if the patch fails for any reason (hash mismatch, corrupt file, bspatch error). Clients that don't have bundle A cached also get the full bundle automatically.

### Enable on the CLI

Pass `--delta` when uploading a release. Requires `bsdiff` on PATH:

```bash
# macOS
brew install bsdiff

# Debian / Ubuntu
apt install bsdiff
```

```bash
nitropush release upload \
  --project <projectId> \
  --environment prod \
  --app-version 1.0.0 \
  --label 1.0.6 \
  --bundle-path ./dist-ios \
  --delta
```

The CLI fetches the previous release bundle, runs `bsdiff`, and uploads the patch alongside the full bundle. If the patch is larger than 80% of the full bundle it is discarded and only the full bundle is uploaded.

### Enable on the SDK

Both sides must opt in. Set `enableDeltaUpdates: true` in your config:

```ts
// Via native config (Info.plist / AndroidManifest — recommended)
// iOS Info.plist:
// <key>NITROPUSH_ENABLE_DELTA_UPDATES</key><true/>
// Android AndroidManifest.xml:
// <meta-data android:name="NITROPUSH_ENABLE_DELTA_UPDATES" android:value="true" />

// Or override at runtime:
const client = configureWith({
  serverUrl: "https://api.nitropush.org",
  deploymentKey: "YOUR_DEPLOYMENT_KEY",
  storageBaseUrl: "https://cdn.nitropush.org",
  enableDeltaUpdates: true,   // ← opt in
});
```

When enabled, every update check sends `?currentBundleHash=<sha256>` so the server knows which base bundle the device has. If a matching patch exists in the manifest, the SDK downloads the patch, verifies its SHA-256, applies it natively via `bspatch`, verifies the output hash matches the new bundle, and installs. On any failure it transparently retries with the full bundle.

> **Note:** Delta updates require both the CLI `--delta` flag at upload time and `enableDeltaUpdates: true` in the SDK. Either side alone does nothing.

→ [Delta updates docs](https://docs.nitropush.org/delta-updates)

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
- [Delta bundle updates](https://docs.nitropush.org/delta-updates)

---

## License

MIT © [NitroPush](https://nitropush.org)
