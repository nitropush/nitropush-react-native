import { NitroModules } from "react-native-nitro-modules";
import type {
  NitroPush,
  NitroPushClient,
  RemotePackage,
} from "./specs/NitroPush.nitro";
import {
  InstallMode,
  SyncStatus,
  type DownloadProgressCallback,
  type NitroPushConfig,
  type SyncOptions,
  type SyncStatusChangedCallback,
} from "./types";

let _factory: NitroPush | undefined;

function factory(): NitroPush {
  if (!_factory) {
    _factory = NitroModules.createHybridObject<NitroPush>("NitroPush");
  }
  return _factory;
}

/**
 * Build a `NitroPushClient` from native defaults — `NITROPUSH_SERVER_URL`,
 * `NITROPUSH_DEPLOYMENT_KEY`, and `NITROPUSH_STORAGE_BASE_URL` keys in
 * Info.plist (iOS) or AndroidManifest meta-data (Android).
 *
 * Throws if any required key is missing. Use `configureWith()` for
 * environment-driven config.
 */
export function configure(): NitroPushClient {
  return factory().configure();
}

/**
 * Build a `NitroPushClient` with explicit configuration. Replaces any
 * previously-applied config on the underlying singleton.
 *
 * @example
 * ```ts
 * const client = configureWith({
 *   serverUrl: process.env.EXPO_PUBLIC_NITROPUSH_SERVER_URL!,
 *   deploymentKey: process.env.EXPO_PUBLIC_NITROPUSH_DEPLOYMENT_KEY!,
 *   storageBaseUrl: process.env.EXPO_PUBLIC_NITROPUSH_STORAGE_BASE_URL!,
 * });
 * ```
 */
export function configureWith(config: NitroPushConfig): NitroPushClient {
  return factory().configureWith(config);
}

/**
 * High-level orchestration: `client.checkForUpdate` → optional `updateDialog`
 * → `remote.download(onProgress)` → `local.install()`. Mirrors `CodePush.sync()`.
 *
 * Sync coalesces concurrent calls per process — the second call resolves with
 * `SYNC_IN_PROGRESS` until the first completes.
 */
const _syncInFlight = new WeakSet<NitroPushClient>();

export async function sync(
  client: NitroPushClient,
  options: SyncOptions = {},
  onStatusChanged: SyncStatusChangedCallback = () => {},
  onProgress: DownloadProgressCallback = () => {},
): Promise<SyncStatus> {
  if (_syncInFlight.has(client)) {
    onStatusChanged(SyncStatus.SYNC_IN_PROGRESS);
    return SyncStatus.SYNC_IN_PROGRESS;
  }

  _syncInFlight.add(client);
  try {
    onStatusChanged(SyncStatus.CHECKING_FOR_UPDATE);
    const remote = await client.checkForUpdate(options.deploymentKey);

    if (!remote) {
      onStatusChanged(SyncStatus.UP_TO_DATE);
      return SyncStatus.UP_TO_DATE;
    }

    if (options.updateDialog) {
      onStatusChanged(SyncStatus.AWAITING_USER_ACTION);
      const accepted = await promptForUpdate(remote, options.updateDialog);
      if (!accepted) {
        onStatusChanged(SyncStatus.UPDATE_IGNORED);
        return SyncStatus.UPDATE_IGNORED;
      }
    }

    onStatusChanged(SyncStatus.DOWNLOADING_PACKAGE);
    const local = await remote.download(onProgress);

    onStatusChanged(SyncStatus.INSTALLING_UPDATE);
    const installMode = remote.isMandatory
      ? (options.mandatoryInstallMode ?? InstallMode.IMMEDIATE)
      : (options.installMode ?? InstallMode.ON_NEXT_RESTART);
    await local.install(installMode, options.minimumBackgroundDuration ?? 0);

    onStatusChanged(SyncStatus.UPDATE_INSTALLED);
    return SyncStatus.UPDATE_INSTALLED;
  } catch (err) {
    onStatusChanged(
      SyncStatus.UNKNOWN_ERROR,
      err instanceof Error ? err : new Error(String(err)),
    );
    return SyncStatus.UNKNOWN_ERROR;
  } finally {
    _syncInFlight.delete(client);
  }
}

async function promptForUpdate(
  remote: RemotePackage,
  dialog: Exclude<SyncOptions["updateDialog"], false | undefined>,
): Promise<boolean> {
  const rnModuleId = "react-native";
  const rn = (await import(/* @vite-ignore */ rnModuleId)) as {
    Alert: {
      alert: (
        title: string,
        message: string,
        buttons: Array<{
          text: string;
          style?: "cancel" | "default" | "destructive";
          onPress?: () => void;
        }>,
      ) => void;
    };
  };
  const { Alert } = rn;
  const title = dialog.title ?? "Update available";
  const baseMessage = remote.isMandatory
    ? (dialog.mandatoryUpdateMessage ?? "An update is required to continue.")
    : (dialog.optionalUpdateMessage ?? "An update is available.");
  const message =
    dialog.appendReleaseDescription && remote.description
      ? `${baseMessage}\n\n${dialog.descriptionPrefix ?? ""}${remote.description}`
      : baseMessage;

  return new Promise<boolean>((resolve) => {
    if (remote.isMandatory) {
      Alert.alert(title, message, [
        {
          text: dialog.mandatoryContinueButtonLabel ?? "Continue",
          onPress: () => resolve(true),
        },
      ]);
      return;
    }
    Alert.alert(title, message, [
      {
        text: dialog.optionalIgnoreButtonLabel ?? "Ignore",
        style: "cancel",
        onPress: () => resolve(false),
      },
      {
        text: dialog.optionalInstallButtonLabel ?? "Install",
        onPress: () => resolve(true),
      },
    ]);
  });
}

export { InstallMode, SyncStatus };
export type {
  DownloadProgressCallback,
  NitroPushConfig,
  SyncOptions,
  SyncStatusChangedCallback,
} from "./types";
export type {
  LocalPackage,
  NitroPushClient,
  RemotePackage,
} from "./specs/NitroPush.nitro";
