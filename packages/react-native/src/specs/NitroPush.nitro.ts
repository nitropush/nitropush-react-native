import type { HybridObject } from "react-native-nitro-modules";
import type {
  DownloadProgress,
  InstallMode,
  NitroPushConfig,
} from "../types";

/**
 * Server-described update returned by `NitroPushClient.checkForUpdate()`.
 *
 * Owns its own `download()` method so callers progress through the
 * lifecycle on the package itself rather than round-tripping through the
 * client:
 *
 *     const remote = await client.checkForUpdate();
 *     if (remote != null) {
 *       const local = await remote.download((p) => console.log(p));
 *       await local.install(InstallMode.ON_NEXT_RESTART, 0);
 *     }
 */
export interface RemotePackage
  extends HybridObject<{ ios: "swift"; android: "kotlin" }> {
  readonly releaseId: string;
  /**
   * Packaging style for this release.
   * - `codepush`: tarball at `downloadObjectKey`.
   * - `expo`: manifest JSON at `downloadObjectKey` listing per-asset object keys.
   */
  readonly kind: string;
  readonly label: string;
  readonly packageHash: string;
  readonly packageSize: number;
  readonly appVersion: string;
  readonly otaVersion?: number;
  readonly displayVersion?: string;
  readonly platforms?: string[];
  readonly isMandatory: boolean;
  readonly description?: string;
  /** Bucket-relative storage key. Joined with `NitroPushConfig.storageBaseUrl`. */
  readonly downloadObjectKey: string;

  /**
   * Download this release into the app's update directory.
   *
   * `onProgress` fires with byte-progress events while bytes flow.
   * Resolves with the on-disk metadata as a `LocalPackage`.
   */
  download(
    onProgress: (progress: DownloadProgress) => void,
  ): Promise<LocalPackage>;
}

/**
 * Fully-installed bundle living in the app's update directory.
 *
 * Returned from `RemotePackage.download()` and from the client's inspection
 * methods (`getCurrentPackage`, `getPendingPackage`).
 */
export interface LocalPackage
  extends HybridObject<{ ios: "swift"; android: "kotlin" }> {
  readonly releaseId: string;
  readonly label: string;
  readonly packageHash: string;
  readonly packageSize: number;
  readonly appVersion: string;
  readonly otaVersion?: number;
  readonly displayVersion?: string;
  readonly platforms?: string[];
  readonly isMandatory: boolean;
  readonly description?: string;
  /** True when written to disk but not yet activated. */
  readonly isPending: boolean;
  /** True when this release was rolled back after a failed install. */
  readonly isFailedInstall: boolean;
  /** True on the first launch after a successful install. */
  readonly isFirstRun: boolean;
  /** Filesystem path to the unpacked bundle. */
  readonly bundlePath: string;

  /**
   * Activate this package according to `installMode`.
   *
   * Does **not** restart the app for `ON_NEXT_RESTART`, `ON_NEXT_RESUME`,
   * or `ON_NEXT_SUSPEND` — those defer the swap to a later lifecycle event.
   * `IMMEDIATE` reloads the React bridge synchronously.
   */
  install(installMode: InstallMode, minimumBackgroundDuration: number): Promise<void>;

  /**
   * Discard this package.
   *
   * - When `isPending` is true → drops the staged bundle (equivalent to
   *   `clearPendingUpdate`).
   * - When this is the active bundle → restores the previous bundle and
   *   reloads the React bridge.
   *
   * Throws when neither condition holds (e.g. a `LocalPackage` snapshot
   * that's no longer the pending or active bundle).
   */
  rollback(): Promise<void>;
}

/**
 * Configured client. Returned from `NitroPush.configure()` /
 * `NitroPush.configureWith(...)`. Holds all runtime operations.
 *
 * One client per `(serverUrl, deploymentKey, storageBaseUrl)` triple. Hold
 * onto it for the lifetime of the app — no per-call cost to creating one
 * (it just bumps a refcount on the underlying singleton), but keeping a
 * single reference makes ownership obvious.
 */
export interface NitroPushClient
  extends HybridObject<{ ios: "swift"; android: "kotlin" }> {
  /**
   * Ask the server whether a newer release is available for this app
   * version + deployment key. Resolves with `null` when up to date.
   */
  checkForUpdate(
    deploymentKeyOverride?: string,
  ): Promise<RemotePackage | null>;

  /** Mark the currently-running bundle as healthy. Idempotent. */
  notifyAppReady(): Promise<void>;

  /** Force a JS engine restart. */
  restartApp(onlyIfUpdateIsPending: boolean): Promise<void>;

  /** Snapshot of the active bundle (`null` if running the binary bundle). */
  getCurrentPackage(): Promise<LocalPackage | null>;

  /** Synchronous variant of `getCurrentPackage()`. */
  getUpdateMetadataSync(): LocalPackage | null;

  /** Snapshot of the bundle that will activate next, or `null`. */
  getPendingPackage(): Promise<LocalPackage | null>;

  /** Wipe all locally-stored bundle metadata + on-disk bundles. */
  clearUpdates(): Promise<void>;
}

/**
 * Singleton entrypoint. Created via
 * `NitroModules.createHybridObject<NitroPush>('NitroPush')` from JS, or
 * accessed as `NitroPushSdk.shared` from native host code.
 *
 * Two ways to obtain a client:
 *
 *     // 1. Read NITROPUSH_SERVER_URL / NITROPUSH_DEPLOYMENT_KEY /
 *     //    NITROPUSH_STORAGE_BASE_URL from Info.plist (iOS) or
 *     //    AndroidManifest meta-data (Android):
 *     const client = NitroPush.configure();
 *
 *     // 2. Pass options explicitly:
 *     const client = NitroPush.configureWith({
 *       serverUrl: '...',
 *       deploymentKey: '...',
 *       storageBaseUrl: '...',
 *     });
 */
export interface NitroPush
  extends HybridObject<{ ios: "swift"; android: "kotlin" }> {
  /**
   * Build a client using config from native sources (Info.plist on iOS,
   * AndroidManifest meta-data on Android). Throws if any required key is
   * missing.
   */
  configure(): NitroPushClient;

  /** Build a client with explicit configuration. */
  configureWith(config: NitroPushConfig): NitroPushClient;
}
