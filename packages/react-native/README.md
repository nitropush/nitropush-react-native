# @nitropush/react-native

NitroPush React Native SDK — a [Nitro Module](https://nitro.margelo.com)
exposing the `NitroPush` HybridObject. Scaffolded with
`npx create-nitro-module@latest` and renamed to the NitroPush identifiers.

## Identifiers

| Layer                  | Name                |
|------------------------|---------------------|
| TS HybridObject        | `NitroPush`         |
| iOS / Android impl     | `HybridNitroPushSdk`|
| iOS pod / Android lib  | `NitroPush`         |
| C++ namespace          | `nitropush`         |
| Workspace package      | `@nitropush/react-native` |

## Layout

```
react-native/
├── src/
│   ├── specs/NitroPush.nitro.ts   # Nitrogen-consumed contract
│   └── index.ts                    # JS façade
├── ios/
│   └── HybridNitroPushSdk.swift    # Extends HybridNitroPushSpec
├── android/
│   └── src/main/java/com/nitropush/
│       ├── HybridNitroPushSdk.kt   # Extends HybridNitroPushSpec
│       └── NitroPushPackage.kt     # RN package boot
├── nitro.json                      # Nitrogen module config
├── NitroPush.podspec               # CocoaPod entry
└── post-script.js                  # Trims `margelo/nitro/` from generated CPP
```

## Generating native bindings

```bash
yarn workspace @nitropush/react-native codegen
```

Runs Nitrogen against `src/specs/NitroPush.nitro.ts` and writes the
generated Swift / Kotlin / C++ bridge into `nitrogen/generated/`. Re-run
whenever you change the spec.

## Using from the host app

```ts
import { NitroPush } from '@nitropush/react-native'

NitroPush.sum(1, 2) // → 3
```

The current spec is the starter from `create-nitro-module` (`sum`).
Replace it with the real NitroPush surface (configure, checkForUpdate,
downloadUpdate, etc.) as you build out the module — see
`packages/native/src/specs/NitroPush.nitro.ts` for the existing
CodePush-style API the original module exposes.
