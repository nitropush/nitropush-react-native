/**
 * `@nitropush/react-native` — React Native + Expo client for the NitroPush
 * over-the-air update server. Built on Nitrogen so the same hybrid objects
 * are callable from JS *and* from native host code without duplicating
 * implementation.
 *
 * Two-step usage:
 *
 * ```ts
 * import {
 *   configureWith,
 *   sync,
 *   InstallMode,
 *   SyncStatus,
 * } from '@nitropush/react-native';
 *
 * // 1. Build a client once at module scope
 * const client = configureWith({
 *   serverUrl: 'https://nitropush.example.com',
 *   deploymentKey: 'PROD-…',
 *   storageBaseUrl: 'https://nitropush-bundles.s3.amazonaws.com',
 * });
 *
 * // 2. Use it
 * useEffect(() => {
 *   client.notifyAppReady();
 *   sync(client, { installMode: InstallMode.ON_NEXT_RESUME }, (status) => {
 *     console.log('NitroPush', SyncStatus[status]);
 *   });
 * }, []);
 * ```
 *
 * Low-level flow on the package objects themselves:
 *
 * ```ts
 * const remote = await client.checkForUpdate();
 * if (remote) {
 *   const local = await remote.download((p) => console.log(p));
 *   await local.install(InstallMode.ON_NEXT_RESTART, 0);
 * }
 * ```
 */

export { configure, configureWith, sync } from "./codepush";

export { InstallMode, SyncStatus } from "./types";

export type {
  DownloadProgress,
  DownloadProgressCallback,
  NitroPushConfig,
  SyncOptions,
  SyncStatusChangedCallback,
  UpdateDialogOptions,
} from "./types";

export type {
  LocalPackage,
  NitroPush,
  NitroPushClient,
  RemotePackage,
} from "./specs/NitroPush.nitro";
