import React, { useState } from 'react';
import {
  View,
  Text,
  TouchableOpacity,
  ScrollView,
  StyleSheet,
  ActivityIndicator,
  Platform,
} from 'react-native';
import {
  configureWith,
  sync,
  InstallMode,
  SyncStatus,
  type DownloadProgress,
} from '@nitropush/react-native';

const SERVER_URL      = process.env.EXPO_PUBLIC_NITROPUSH_SERVER_URL      ?? '';
const DEPLOYMENT_KEY  = process.env.EXPO_PUBLIC_NITROPUSH_DEPLOYMENT_KEY  ?? '';
const STORAGE_BASE    = process.env.EXPO_PUBLIC_NITROPUSH_STORAGE_BASE_URL ?? '';

const BASE_CONFIG = {
  serverUrl:      SERVER_URL,
  deploymentKey:  DEPLOYMENT_KEY,
  storageBaseUrl: STORAGE_BASE,
};

const SCENARIOS = [
  {
    id: 'delta',
    label: '✓ Delta updates ON',
    description: 'enableDeltaUpdates: true\nDownloads patch when server offers one',
    config: { ...BASE_CONFIG, enableDeltaUpdates: true },
  },
  {
    id: 'full',
    label: '⬇  Delta updates OFF',
    description: 'enableDeltaUpdates: false\nAlways downloads full bundle',
    config: { ...BASE_CONFIG, enableDeltaUpdates: false },
  },
];

type LogEntry = { time: string; msg: string; type: 'info' | 'ok' | 'err' | 'dim' };

export default function DeltaTestScreen() {
  const [busy, setBusy] = useState(false);
  const [progress, setProgress] = useState<DownloadProgress | null>(null);
  const [log, setLog] = useState<LogEntry[]>([]);

  function addLog(msg: string, type: LogEntry['type'] = 'info') {
    const time = new Date().toISOString().slice(11, 23);
    setLog((prev) => [...prev, { time, msg, type }]);
  }

  async function runScenario(scenario: typeof SCENARIOS[0]) {
    setLog([]);
    setProgress(null);
    setBusy(true);

    addLog(`Scenario: ${scenario.label}`, 'dim');
    addLog(`Server: ${SERVER_URL}`, 'dim');
    addLog(`Key: ${DEPLOYMENT_KEY.slice(0, 24)}…`, 'dim');
    addLog(`enableDeltaUpdates: ${scenario.config.enableDeltaUpdates}`, 'dim');
    addLog('─'.repeat(36), 'dim');

    try {
      const client = configureWith(scenario.config);

      const status = await sync(
        client,
        { installMode: InstallMode.ON_NEXT_RESTART },
        (s) => {
          const labels: Partial<Record<SyncStatus, string>> = {
            [SyncStatus.CHECKING_FOR_UPDATE]: '🔍 Checking…',
            [SyncStatus.DOWNLOADING_PACKAGE]: '⬇  Downloading…',
            [SyncStatus.INSTALLING_UPDATE]:   '📦 Installing…',
            [SyncStatus.UPDATE_INSTALLED]:    '✅ Update installed',
            [SyncStatus.UP_TO_DATE]:          '✓  Already up to date',
            [SyncStatus.UNKNOWN_ERROR]:       '✗  Error',
          };
          addLog(labels[s] ?? `Status(${s})`, s === SyncStatus.UNKNOWN_ERROR ? 'err' : 'info');
        },
        (p) => {
          setProgress(p);
          if (p.totalBytes > 0) {
            const pct = Math.round((p.receivedBytes / p.totalBytes) * 100);
            const kb  = (p.receivedBytes / 1024).toFixed(1);
            const tot = (p.totalBytes   / 1024).toFixed(1);
            addLog(`  ${pct}%  ${kb} / ${tot} KB`, 'dim');
          }
        },
      );

      addLog('─'.repeat(36), 'dim');
      addLog(`Final: ${SyncStatus[status] ?? status}`, status === SyncStatus.UNKNOWN_ERROR ? 'err' : 'ok');
    } catch (e: any) {
      addLog(`Exception: ${e?.message ?? String(e)}`, 'err');
    } finally {
      setBusy(false);
      setProgress(null);
    }
  }

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <Text style={styles.title}>Delta Updates — Live Test</Text>
      <Text style={styles.subtitle}>
        {'Tap a scenario to sync against the live server.\n'}
        <Text style={styles.code}>
          {SERVER_URL || '(EXPO_PUBLIC_NITROPUSH_SERVER_URL not set)'}
        </Text>
      </Text>

      {SCENARIOS.map((s) => (
        <TouchableOpacity
          key={s.id}
          style={[styles.button, busy && styles.buttonDisabled]}
          onPress={() => !busy && runScenario(s)}
          disabled={busy}
        >
          <Text style={styles.buttonLabel}>{s.label}</Text>
          <Text style={styles.buttonDesc}>{s.description}</Text>
        </TouchableOpacity>
      ))}

      {busy && (
        <View style={styles.progressRow}>
          <ActivityIndicator size="small" color="#6366f1" />
          {progress && progress.totalBytes > 0 && (
            <Text style={styles.progressText}>
              {(progress.receivedBytes / 1024).toFixed(1)} /{' '}
              {(progress.totalBytes / 1024).toFixed(1)} KB
            </Text>
          )}
        </View>
      )}

      {log.length > 0 && (
        <View style={styles.logBox}>
          {log.map((entry, i) => (
            <Text key={i} style={[styles.logLine, styles[`log_${entry.type}`]]}>
              <Text style={styles.logTime}>{entry.time}  </Text>
              {entry.msg}
            </Text>
          ))}
        </View>
      )}

      <View style={styles.infoBox}>
        <Text style={styles.infoTitle}>How to publish a delta release</Text>
        <Text style={styles.infoText}>
          {'1. Export the app twice (two different builds)\n'}
          {'2. Run the CLI with --delta:\n\n'}
          <Text style={styles.code}>
            {'npx nitropush release upload \\\n'}
            {'  --project <id> --environment test \\\n'}
            {'  --label v1.0.1 --bundle-path ./dist \\\n'}
            {'  --delta\n\n'}
          </Text>
          {'3. Check the CLI output for savings %\n'}
          {'4. Tap "Delta ON" above — download size\n'}
          {'   should be much smaller than full bundle.'}
        </Text>
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#0f0f13' },
  content:   { padding: 20, paddingTop: 60, paddingBottom: 40 },
  title:     { fontSize: 22, fontWeight: '700', color: '#f1f5f9', marginBottom: 4 },
  subtitle:  { fontSize: 13, color: '#94a3b8', marginBottom: 24, lineHeight: 20 },
  code:      { fontFamily: Platform.select({ ios: 'Menlo', android: 'monospace' }), color: '#a5b4fc' },
  button: {
    backgroundColor: '#1e1e2e',
    borderRadius: 12,
    padding: 16,
    marginBottom: 12,
    borderWidth: 1,
    borderColor: '#2d2d3f',
  },
  buttonDisabled: { opacity: 0.5 },
  buttonLabel:    { fontSize: 15, fontWeight: '600', color: '#e2e8f0', marginBottom: 4 },
  buttonDesc: {
    fontSize: 12,
    color: '#64748b',
    lineHeight: 18,
    fontFamily: Platform.select({ ios: 'Menlo', android: 'monospace' }),
  },
  progressRow: { flexDirection: 'row', alignItems: 'center', gap: 10, marginBottom: 12 },
  progressText: { fontSize: 13, color: '#94a3b8' },
  logBox: {
    backgroundColor: '#0a0a0f',
    borderRadius: 10,
    padding: 12,
    marginBottom: 20,
    borderWidth: 1,
    borderColor: '#1e1e2e',
  },
  logLine: { fontSize: 11, lineHeight: 18, fontFamily: Platform.select({ ios: 'Menlo', android: 'monospace' }) },
  logTime: { color: '#475569' },
  log_info: { color: '#cbd5e1' },
  log_ok:   { color: '#4ade80' },
  log_err:  { color: '#f87171' },
  log_dim:  { color: '#475569' },
  infoBox: {
    backgroundColor: '#131320',
    borderRadius: 10,
    padding: 14,
    borderWidth: 1,
    borderColor: '#1e1e2e',
  },
  infoTitle: { fontSize: 13, fontWeight: '600', color: '#94a3b8', marginBottom: 8 },
  infoText:  { fontSize: 12, color: '#64748b', lineHeight: 20 },
});
