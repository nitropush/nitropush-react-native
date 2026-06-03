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
import { configureWith, sync, InstallMode, SyncStatus, type DownloadProgress } from '@nitropush/react-native';

/**
 * Delta bundle update test screen.
 *
 * Points at the local mock server (scripts/serve-delta-mock.mjs).
 * Run the server first:
 *   node scripts/serve-delta-mock.mjs
 *
 * On a simulator, localhost works directly.
 * On a physical device, replace localhost with your Mac's LAN IP.
 */

// ─── Change this to your Mac's LAN IP when testing on a physical device ───
const MOCK_SERVER = __DEV__ ? 'http://localhost:3333' : 'https://api.nitropush.org';

const SCENARIOS = [
  {
    id: 'delta',
    label: '✓ Delta path',
    description: 'enableDeltaUpdates: true\nServer offers delta (hash matches)',
    config: {
      serverUrl: MOCK_SERVER,
      deploymentKey: 'test-key',
      storageBaseUrl: `${MOCK_SERVER}/storage`,
      enableDeltaUpdates: true,
    },
  },
  {
    id: 'full',
    label: '⬇ Full bundle path',
    description: 'enableDeltaUpdates: false\nAlways downloads full bundle',
    config: {
      serverUrl: MOCK_SERVER,
      deploymentKey: 'test-key',
      storageBaseUrl: `${MOCK_SERVER}/storage`,
      enableDeltaUpdates: false,
    },
  },
  {
    id: 'corrupt',
    label: '💥 Corrupt patch → fallback',
    description: 'Run server with --corrupt-patch\nSDK should fall back to full bundle',
    config: {
      serverUrl: MOCK_SERVER,
      deploymentKey: 'test-key',
      storageBaseUrl: `${MOCK_SERVER}/storage`,
      enableDeltaUpdates: true,
    },
    serverFlag: '--corrupt-patch',
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
    addLog(`Server: ${scenario.config.serverUrl}`, 'dim');
    addLog(`enableDeltaUpdates: ${scenario.config.enableDeltaUpdates}`, 'dim');
    if (scenario.serverFlag) {
      addLog(`⚠  Start server with: node scripts/serve-delta-mock.mjs ${scenario.serverFlag}`, 'dim');
    }
    addLog('─'.repeat(36), 'dim');

    try {
      const client = configureWith(scenario.config);

      const status = await sync(
        client,
        { installMode: InstallMode.ON_NEXT_RESTART },
        (s) => {
          const labels: Partial<Record<SyncStatus, string>> = {
            [SyncStatus.CHECKING_FOR_UPDATE]: '🔍 Checking for update…',
            [SyncStatus.DOWNLOADING_PACKAGE]: '⬇  Downloading…',
            [SyncStatus.INSTALLING_UPDATE]:   '📦 Installing…',
            [SyncStatus.UPDATE_INSTALLED]:    '✅ Update installed',
            [SyncStatus.UP_TO_DATE]:          '✓  Already up to date',
            [SyncStatus.UNKNOWN_ERROR]:       '✗  Error',
          };
          const label = labels[s] ?? `Status(${s})`;
          addLog(label, s === SyncStatus.UNKNOWN_ERROR ? 'err' : 'info');
        },
        (p) => {
          setProgress(p);
          if (p.totalBytes > 0) {
            const pct = Math.round((p.receivedBytes / p.totalBytes) * 100);
            const kb = (p.receivedBytes / 1024).toFixed(1);
            const total = (p.totalBytes / 1024).toFixed(1);
            addLog(`  ${pct}%  ${kb} / ${total} KB`, 'dim');
          }
        },
      );

      addLog('─'.repeat(36), 'dim');
      addLog(`Final status: ${SyncStatus[status] ?? status}`, status === SyncStatus.UNKNOWN_ERROR ? 'err' : 'ok');
    } catch (e: any) {
      addLog(`Exception: ${e?.message ?? String(e)}`, 'err');
    } finally {
      setBusy(false);
      setProgress(null);
    }
  }

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <Text style={styles.title}>Delta Bundle Test</Text>
      <Text style={styles.subtitle}>
        Start the mock server, then tap a scenario:{'\n'}
        <Text style={styles.code}>node scripts/serve-delta-mock.mjs</Text>
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
        <Text style={styles.infoTitle}>What to look for</Text>
        <Text style={styles.infoText}>
          <Text style={styles.bold}>Delta path:</Text> download size should be{'\n'}
          much smaller than the full bundle.{'\n\n'}
          <Text style={styles.bold}>Full bundle path:</Text> download size should{'\n'}
          be the full bundle size.{'\n\n'}
          <Text style={styles.bold}>Corrupt patch:</Text> SDK should log a delta{'\n'}
          failure and fall back to full bundle.{'\n\n'}
          Server logs (in your terminal) show exactly{'\n'}
          which endpoints are hit.
        </Text>
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#0f0f13' },
  content: { padding: 20, paddingTop: 60, paddingBottom: 40 },
  title: { fontSize: 22, fontWeight: '700', color: '#f1f5f9', marginBottom: 4 },
  subtitle: { fontSize: 13, color: '#94a3b8', marginBottom: 24, lineHeight: 20 },
  code: { fontFamily: Platform.select({ ios: 'Menlo', android: 'monospace' }), color: '#a5b4fc' },
  button: {
    backgroundColor: '#1e1e2e',
    borderRadius: 12,
    padding: 16,
    marginBottom: 12,
    borderWidth: 1,
    borderColor: '#2d2d3f',
  },
  buttonDisabled: { opacity: 0.5 },
  buttonLabel: { fontSize: 15, fontWeight: '600', color: '#e2e8f0', marginBottom: 4 },
  buttonDesc: { fontSize: 12, color: '#64748b', lineHeight: 18, fontFamily: Platform.select({ ios: 'Menlo', android: 'monospace' }) },
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
  infoText: { fontSize: 12, color: '#64748b', lineHeight: 20 },
  bold: { color: '#94a3b8', fontWeight: '600' },
});
