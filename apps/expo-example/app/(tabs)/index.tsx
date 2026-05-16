import { useCallback, useEffect, useMemo, useState } from "react";
import { Platform, Pressable, StyleSheet, View } from "react-native";

import { ThemedText } from "@/components/themed-text";
import { ThemedView } from "@/components/themed-view";

import {
  configureWith,
  InstallMode,
  sync,
  SyncStatus,
  type DownloadProgress,
  type LocalPackage,
  type NitroPushClient,
} from "@nitropush/react-native";

/**
 * Android emulators see `localhost` as the emulator itself, not the host
 * machine — the host is reachable at `10.0.2.2`. iOS simulators share the
 * host's loopback so `localhost` works there. Auto-rewrite for dev so the
 * same `.env` works on both targets.
 */
function resolveServerUrl(raw: string): string {
  if (Platform.OS !== "android") return raw;
  return raw.replace(/\/\/(localhost|127\.0\.0\.1)(?=[:/]|$)/, "//10.0.2.2");
}

const NITROPUSH_SERVER_URL = resolveServerUrl(
  (process.env.EXPO_PUBLIC_NITROPUSH_SERVER_URL as string | undefined) ??
    "https://nitropush.example.com",
);
const DEPLOYMENT_KEY =
  (process.env.EXPO_PUBLIC_NITROPUSH_DEPLOYMENT_KEY as string | undefined) ??
  "PROD-CHANGEME";
const STORAGE_BASE_URL = resolveServerUrl(
  (process.env.EXPO_PUBLIC_NITROPUSH_STORAGE_BASE_URL as string | undefined) ??
    "https://cdn.nitropush.example.com",
);

// Build the client once at module scope. `configureWith` returns a
// `NitroPushClient` whose methods (`checkForUpdate`, `notifyAppReady`,
// `restartApp`, `getCurrentPackage`, …) drive the rest of the flow.
const client: NitroPushClient = configureWith({
  serverUrl: NITROPUSH_SERVER_URL,
  deploymentKey: DEPLOYMENT_KEY,
  storageBaseUrl: STORAGE_BASE_URL,
});

export default function HomeScreen() {
  // First-paint reads via the sync helper — avoids a microtask hop so the
  // metadata is available before the first frame paints.
  const [running, setRunning] = useState<LocalPackage | null>(() =>
    client.getUpdateMetadataSync(),
  );
  const [pending, setPending] = useState<LocalPackage | null>(null);
  const [statusLine, setStatusLine] = useState("idle");
  const [progress, setProgress] = useState<DownloadProgress | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    // Mark the current bundle as healthy so the next launch's pointer
    // sweep won't roll it back. Idempotent.
    client.notifyAppReady().catch((e) => setError(String(e)));

    // Pull async metadata too — covers the rare race where the native
    // singleton wasn't ready yet on the very first sync call.
    void Promise.all([client.getCurrentPackage(), client.getPendingPackage()])
      .then(([r, p]) => {
        if (r) setRunning(r);
        setPending(p);
      })
      .catch((e) => setError(String(e)));
  }, []);

  const meta = useMemo(() => running, [running]);

  const runSync = useCallback(async () => {
    setError(null);
    setProgress(null);
    await sync(
      client,
      {
        installMode: InstallMode.ON_NEXT_RESUME,
        mandatoryInstallMode: InstallMode.IMMEDIATE,
        minimumBackgroundDuration: 60,
      },
      (status, err) => {
        setStatusLine(SyncStatus[status]);
        if (err) setError(err.message);
      },
      setProgress,
    );

    // Refresh the pending pointer after the sync — UI gets the new label
    // for the "Apply pending" button without needing a remount.
    setPending(await client.getPendingPackage());
  }, []);

  const applyPending = useCallback(() => {
    client.restartApp(true).catch((e) => setError(String(e)));
  }, []);

  return (
    <ThemedView style={styles.root}>
      <ThemedText type="title">NitroPush demo</ThemedText>
      <ThemedText style={styles.subtitle}>API: {NITROPUSH_SERVER_URL}</ThemedText>
      <ThemedText style={styles.subtitle}>
        Storage: {STORAGE_BASE_URL}
      </ThemedText>
      <ThemedText style={styles.subtitle}>
        Version: {meta?.displayVersion ?? meta?.label ?? "binary bundle"}
      </ThemedText>

      <View style={styles.card}>
        <ThemedText style={styles.label}>Running</ThemedText>
        <ThemedText style={styles.value}>
          {running ? `${running.label} · ${running.appVersion}` : "binary bundle"}
        </ThemedText>

        <ThemedText style={styles.label}>Pending</ThemedText>
        <ThemedText style={styles.value}>
          {pending ? `${pending.label} · ${pending.appVersion}` : "—"}
        </ThemedText>

        <ThemedText style={styles.label}>Status</ThemedText>
        <ThemedText style={styles.value}>{statusLine}</ThemedText>

        <ThemedText style={styles.label}>Progress</ThemedText>
        <ThemedText style={styles.value}>
          {progress
            ? `${progress.receivedBytes.toFixed(0)} / ${progress.totalBytes.toFixed(0)} bytes`
            : "—"}
        </ThemedText>

        {error ? (
          <>
            <ThemedText style={styles.label}>Error</ThemedText>
            <ThemedText style={[styles.value, styles.error]}>{error}</ThemedText>
          </>
        ) : null}
      </View>

      <Pressable style={styles.button} onPress={runSync}>
        <ThemedText style={styles.buttonLabel}>Check for updates</ThemedText>
      </Pressable>

      <Pressable
        style={[styles.button, styles.secondaryButton, !pending && styles.disabledButton]}
        disabled={!pending}
        onPress={applyPending}
      >
        <ThemedText style={styles.buttonLabel}>Apply pending update</ThemedText>
      </Pressable>
    </ThemedView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, padding: 24, gap: 8 },
  subtitle: { opacity: 0.6, fontFamily: "Menlo", marginBottom: 4 },
  card: {
    padding: 16,
    borderRadius: 12,
    marginTop: 16,
    marginBottom: 16,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: "#7c8ab0",
    gap: 4,
  },
  label: {
    opacity: 0.6,
    fontSize: 11,
    textTransform: "uppercase",
    letterSpacing: 1,
    marginTop: 8,
  },
  value: { fontSize: 16, fontFamily: "Menlo" },
  error: { color: "#ff8a8a" },
  button: {
    backgroundColor: "#3b82f6",
    paddingVertical: 14,
    borderRadius: 12,
    alignItems: "center",
    marginBottom: 12,
  },
  secondaryButton: { backgroundColor: "#1f2a44" },
  disabledButton: { opacity: 0.5 },
  buttonLabel: { color: "#fff", fontSize: 16, fontWeight: "600" },
});
