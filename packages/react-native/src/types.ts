/**
 * When a downloaded update should be applied to the running app.
 *
 * Mirrors `react-native-code-push`'s `InstallMode` so existing CodePush
 * integrations port over with minimal churn.
 */
export enum InstallMode {
  /**
   * Restart the app the moment installation completes. Use sparingly —
   * yanks the user out of whatever they're doing.
   */
  IMMEDIATE = 0,
  /**
   * Apply the update on the next cold launch. Safe default for non-mandatory
   * releases.
   */
  ON_NEXT_RESTART = 1,
  /**
   * Apply when the app next moves from background → foreground. Pair with
   * `minimumBackgroundDuration` to avoid flickering during quick app switches.
   */
  ON_NEXT_RESUME = 2,
  /**
   * Apply when the app next moves to the background, so the *following* resume
   * sees the new bundle. Lower disruption than `ON_NEXT_RESUME` for apps that
   * background frequently.
   */
  ON_NEXT_SUSPEND = 3,
}

/**
 * Status emitted during a `sync()` lifecycle. Consumers map these to UI
 * state (spinners, toasts, error dialogs).
 *
 * Order roughly matches the runtime sequence: check → download → install.
 */
export enum SyncStatus {
  /** Querying the server for a newer release. */
  CHECKING_FOR_UPDATE = 0,
  /** An update was found but `sync()` is paused waiting for `updateDialog` user input. */
  AWAITING_USER_ACTION = 1,
  /** Bundle bytes are being downloaded. Pair with the `downloadProgress` callback. */
  DOWNLOADING_PACKAGE = 2,
  /** Bundle is being unpacked + written to the app's persistent update directory. */
  INSTALLING_UPDATE = 3,
  /** Server responded but no newer release is available for this app/env/runtime. */
  UP_TO_DATE = 4,
  /** A new bundle has been installed. The actual *apply* depends on the install mode. */
  UPDATE_INSTALLED = 5,
  /** User declined an update via the prompt. */
  UPDATE_IGNORED = 6,
  /** Sync failed; check the second argument of the `syncStatusChanged` callback for the error. */
  UNKNOWN_ERROR = 7,
  /** A sync was already in flight when this one was requested. */
  SYNC_IN_PROGRESS = 8,
}

/**
 * Bytes-progress event emitted during `DOWNLOADING_PACKAGE`.
 */
export interface DownloadProgress {
  readonly receivedBytes: number;
  readonly totalBytes: number;
}

/** Options for the `updateDialog` flow inside `sync()`. */
export interface UpdateDialogOptions {
  /** Title used for non-mandatory updates. */
  optionalUpdateMessage?: string;
  /** Title used for mandatory updates. */
  mandatoryUpdateMessage?: string;
  /** Confirm-button label for non-mandatory updates. Default: `Install`. */
  optionalInstallButtonLabel?: string;
  /** Cancel-button label for non-mandatory updates. Default: `Ignore`. */
  optionalIgnoreButtonLabel?: string;
  /** Confirm-button label for mandatory updates. Default: `Continue`. */
  mandatoryContinueButtonLabel?: string;
  /** Title shown above all variants. Default: `Update available`. */
  title?: string;
  /** When true, the release description is appended to the message. */
  appendReleaseDescription?: boolean;
  /** Fixed prefix for the release description, e.g. `"Description: "`. */
  descriptionPrefix?: string;
}

/**
 * Tunable knobs for `sync()`. All fields are optional; sensible defaults match
 * `react-native-code-push`.
 */
export interface SyncOptions {
  /** Where the new bundle should activate. Default: `ON_NEXT_RESTART`. */
  installMode?: InstallMode;
  /** Override of `installMode` for mandatory releases. Default: `IMMEDIATE`. */
  mandatoryInstallMode?: InstallMode;
  /**
   * Minimum seconds the app must remain backgrounded before a pending update
   * activates on resume. Helps avoid flicker during fast app-switching.
   * Default: `0`.
   */
  minimumBackgroundDuration?: number;
  /** When set, prompts the user before installing. Pass `false` to disable. */
  updateDialog?: UpdateDialogOptions | false;
  /** Override the deployment key configured in the native module. */
  deploymentKey?: string;
}

/** Callback signature for `sync()` status transitions. */
export type SyncStatusChangedCallback = (
  status: SyncStatus,
  error?: Error,
) => void;

/** Callback signature for download progress events. */
export type DownloadProgressCallback = (progress: DownloadProgress) => void;

/**
 * Static native config required at startup. Wire this in the host app's
 * native code (Swift/Kotlin) or via JS `configure()` before any `sync()` call.
 */
export interface NitroPushConfig {
  /** NitroPush admin server URL, e.g. `https://nitropush.example.com`. */
  serverUrl: string;
  /** Deployment / environment key (the `key` of the `environments` row). */
  deploymentKey: string;
  /**
   * Public base URL for the bundle/asset storage (e.g. an S3 bucket or
   * CDN). Every `objectKey` returned by the server / written into the
   * Expo manifest is joined with this base at fetch time. Trailing slash
   * optional. Example: `https://nitropush-bundles.s3.amazonaws.com` or
   * `http://10.0.2.2:9000/nitropush-bundles`.
   */
  storageBaseUrl: string;
  /** Native app version, e.g. `1.4.0`. Falls back to the binary's `CFBundleShortVersionString` / Android `versionName`. */
  appVersion?: string;
  /** Optional unique device id used for deterministic rollout bucketing. */
  clientUniqueId?: string;
}
