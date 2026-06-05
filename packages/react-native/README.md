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

For iOS, run `pod install` after installing.

---

## Quick start

```ts
import { configure, sync, InstallMode, SyncStatus } from '@nitropush/react-native';
import { useEffect } from 'react';

// Reads serverUrl / deploymentKey / storageBaseUrl from native config
// (Info.plist on iOS, strings.xml on Android)
const client = configure();

export default function App() {
  useEffect(() => {
    client.notifyAppReady();
    sync(client, { installMode: InstallMode.ON_NEXT_RESUME }, (status) => {
      if (status === SyncStatus.UPDATE_INSTALLED) {
        console.log('Update installed — will apply on next resume');
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

## Key exports

| Export | Description |
|--------|-------------|
| `configure()` | Create a `NitroPushClient` using native config (plist / strings.xml) |
| `configureWith(config)` | Create a `NitroPushClient` with explicit JS-side config |
| `sync(client, options?, callback?)` | High-level check → download → install in one call |
| `InstallMode` | Enum: `IMMEDIATE`, `ON_NEXT_RESTART`, `ON_NEXT_RESUME`, `ON_NEXT_SUSPEND` |
| `SyncStatus` | Enum: `CHECKING_FOR_UPDATE`, `DOWNLOADING_PACKAGE`, `UPDATE_INSTALLED`, … |
| `NitroPushConfig` | Config shape for `configureWith` / `configure` |
| `SyncOptions` | Options for `sync()` (install mode, dialogs, rollback, …) |

---

## Documentation

Full guides, API reference, Expo integration, and the CLI reference are available at **[docs.nitropush.org](https://docs.nitropush.org)**.

- [Getting started](https://docs.nitropush.org)
- [Expo / managed workflow](https://docs.nitropush.org)
- [CLI reference](https://docs.nitropush.org)
- [Release channels & rollouts](https://docs.nitropush.org)

---

## License

MIT © [NitroPush](https://docs.nitropush.org)
