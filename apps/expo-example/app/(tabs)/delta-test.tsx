import Constants from "expo-constants";
import React, { useState } from "react";
import {
  ActivityIndicator,
  Platform,
  ScrollView,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from "react-native";
import {
  InstallMode,
  sync,
  SyncStatus,
  type DownloadProgress,
} from "@nitropush/react-native";

import { nitropushClient as client } from "@/lib/nitropush";

const DELTA_ENABLED =
  Constants.expoConfig?.extra?.nitropushDeltaUpdates === true;

type LogEntry = {
  time: string;
  msg: string;
  type: "info" | "ok" | "err" | "dim";
};

export default function DeltaTestScreen() {
  const [busy, setBusy] = useState(false);
  const [progress, setProgress] = useState<DownloadProgress | null>(null);
  const [log, setLog] = useState<LogEntry[]>([]);

  function addLog(msg: string, type: LogEntry["type"] = "info") {
    const time = new Date().toISOString().slice(11, 23);
    setLog((previous) => [...previous, { time, msg, type }]);
  }

  async function runConfiguredScenario() {
    setLog([]);
    setProgress(null);
    setBusy(true);

    addLog(
      DELTA_ENABLED ? "Native delta mode: ON" : "Native delta mode: OFF",
      "dim",
    );
    addLog("Credential source: native configuration", "dim");
    addLog("─".repeat(36), "dim");

    try {
      const status = await sync(
        client,
        { installMode: InstallMode.ON_NEXT_RESTART },
        (nextStatus, error) => {
          const labels: Partial<Record<SyncStatus, string>> = {
            [SyncStatus.CHECKING_FOR_UPDATE]: "🔍 Checking…",
            [SyncStatus.DOWNLOADING_PACKAGE]: "⬇  Downloading…",
            [SyncStatus.INSTALLING_UPDATE]: "📦 Installing…",
            [SyncStatus.UPDATE_INSTALLED]: "✅ Update installed",
            [SyncStatus.UP_TO_DATE]: "✓  Already up to date",
            [SyncStatus.UNKNOWN_ERROR]: "✗  Error",
          };
          addLog(
            labels[nextStatus] ?? `Status(${nextStatus})`,
            nextStatus === SyncStatus.UNKNOWN_ERROR ? "err" : "info",
          );
          if (error) addLog(error.message, "err");
        },
        (nextProgress) => {
          setProgress(nextProgress);
          if (nextProgress.totalBytes > 0) {
            const percentage = Math.round(
              (nextProgress.receivedBytes / nextProgress.totalBytes) * 100,
            );
            const receivedKb = (nextProgress.receivedBytes / 1024).toFixed(1);
            const totalKb = (nextProgress.totalBytes / 1024).toFixed(1);
            addLog(
              `  ${percentage}%  ${receivedKb} / ${totalKb} KB`,
              "dim",
            );
          }
        },
      );

      addLog("─".repeat(36), "dim");
      addLog(
        `Final: ${SyncStatus[status] ?? status}`,
        status === SyncStatus.UNKNOWN_ERROR ? "err" : "ok",
      );
    } catch (error) {
      addLog(
        `Exception: ${error instanceof Error ? error.message : String(error)}`,
        "err",
      );
    } finally {
      setBusy(false);
      setProgress(null);
    }
  }

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <Text style={styles.title}>Delta Updates — Native Test</Text>
      <Text style={styles.subtitle}>
        This binary was built with delta updates {DELTA_ENABLED ? "ON" : "OFF"}.
        The deployment credential remains in Info.plist / AndroidManifest and is
        never copied into JavaScript.
      </Text>

      <TouchableOpacity
        style={[styles.button, busy && styles.buttonDisabled]}
        onPress={() => !busy && runConfiguredScenario()}
        disabled={busy}
      >
        <Text style={styles.buttonLabel}>
          {DELTA_ENABLED ? "Run delta-enabled sync" : "Run full-bundle sync"}
        </Text>
        <Text style={styles.buttonDesc}>
          {DELTA_ENABLED
            ? "Sends the active bundle hash and accepts an exact matching patch"
            : "Omits the active bundle hash and always downloads the full bundle"}
        </Text>
      </TouchableOpacity>

      {busy && (
        <View style={styles.progressRow}>
          <ActivityIndicator size="small" color="#6366f1" />
          {progress && progress.totalBytes > 0 && (
            <Text style={styles.progressText}>
              {(progress.receivedBytes / 1024).toFixed(1)} /{" "}
              {(progress.totalBytes / 1024).toFixed(1)} KB
            </Text>
          )}
        </View>
      )}

      {log.length > 0 && (
        <View style={styles.logBox}>
          {log.map((entry, index) => (
            <Text
              key={`${entry.time}-${index}`}
              style={[styles.logLine, styles[`log_${entry.type}`]]}
            >
              <Text style={styles.logTime}>{entry.time}  </Text>
              {entry.msg}
            </Text>
          ))}
        </View>
      )}

      <View style={styles.infoBox}>
        <Text style={styles.infoTitle}>Switching test mode</Text>
        <Text style={styles.infoText}>
          Rebuild native configuration with{"\n"}
          <Text style={styles.code}>
            NITROPUSH_ENABLE_DELTA_UPDATES=true npx expo prebuild --platform ios
          </Text>
          {"\n\n"}
          Use false for the full-bundle control build. Publish and install a full
          baseline first, then publish a changed bundle and run the delta build
          from that baseline.
        </Text>
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: "#0f0f13" },
  content: { padding: 20, paddingTop: 60, paddingBottom: 40 },
  title: {
    fontSize: 22,
    fontWeight: "700",
    color: "#f1f5f9",
    marginBottom: 8,
  },
  subtitle: {
    fontSize: 13,
    color: "#94a3b8",
    marginBottom: 24,
    lineHeight: 20,
  },
  code: {
    fontFamily: Platform.select({ ios: "Menlo", android: "monospace" }),
    color: "#a5b4fc",
  },
  button: {
    backgroundColor: "#1e1e2e",
    borderRadius: 12,
    padding: 16,
    marginBottom: 12,
    borderWidth: 1,
    borderColor: "#2d2d3f",
  },
  buttonDisabled: { opacity: 0.5 },
  buttonLabel: {
    fontSize: 15,
    fontWeight: "600",
    color: "#e2e8f0",
    marginBottom: 4,
  },
  buttonDesc: {
    fontSize: 12,
    color: "#64748b",
    lineHeight: 18,
    fontFamily: Platform.select({ ios: "Menlo", android: "monospace" }),
  },
  progressRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: 10,
    marginBottom: 12,
  },
  progressText: { fontSize: 13, color: "#94a3b8" },
  logBox: {
    backgroundColor: "#0a0a0f",
    borderRadius: 10,
    padding: 12,
    marginBottom: 20,
    borderWidth: 1,
    borderColor: "#1e1e2e",
  },
  logLine: {
    fontSize: 11,
    lineHeight: 18,
    fontFamily: Platform.select({ ios: "Menlo", android: "monospace" }),
  },
  logTime: { color: "#475569" },
  log_info: { color: "#cbd5e1" },
  log_ok: { color: "#4ade80" },
  log_err: { color: "#f87171" },
  log_dim: { color: "#475569" },
  infoBox: {
    backgroundColor: "#131320",
    borderRadius: 10,
    padding: 14,
    borderWidth: 1,
    borderColor: "#1e1e2e",
  },
  infoTitle: {
    fontSize: 13,
    fontWeight: "600",
    color: "#94a3b8",
    marginBottom: 8,
  },
  infoText: { fontSize: 12, color: "#64748b", lineHeight: 20 },
});
