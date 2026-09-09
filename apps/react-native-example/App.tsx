/**
 * NitroPush demo for the bare React Native example.
 *
 * Native code selects the active OTA bundle. JavaScript configures the SDK
 * at module scope and, critically, calls notifyAppReady only after React's
 * first successful render. A native foreground callback must never confirm
 * an update because it cannot prove the JavaScript bundle rendered.
 *
 * @format
 */

import { useCallback, useEffect, useState } from 'react';
import {
  Pressable,
  StatusBar,
  StyleSheet,
  Text,
  useColorScheme,
  View,
} from 'react-native';
import { SafeAreaProvider, useSafeAreaInsets } from 'react-native-safe-area-context';

import {
  configure,
  sync,
  InstallMode,
  type LocalPackage,
  type NitroPushClient,
} from '@nitropush/react-native';

// Reads NITROPUSH_* from Info.plist / AndroidManifest. Module scope ensures
// configuration completes before any component calls another SDK API.
const client: NitroPushClient = configure();

function App() {
  const isDarkMode = useColorScheme() === 'dark';

  return (
    <SafeAreaProvider>
      <StatusBar barStyle={isDarkMode ? 'light-content' : 'dark-content'} />
      <Demo />
    </SafeAreaProvider>
  );
}

function Demo() {
  const insets = useSafeAreaInsets();
  // First-paint reads via the sync helper — avoids a microtask hop and
  // gives us metadata before the first frame paints. Falls back to the
  // async helper afterwards in case the singleton wasn't ready yet on
  // the very first call (race with native bootstrap).
  const [running, setRunning] = useState<LocalPackage | null>(() =>
    client.getUpdateMetadataSync(),
  );
  const [pending, setPending] = useState<LocalPackage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    // This is the rollback health boundary: it runs only after React has
    // mounted this screen successfully.
    client.notifyAppReady().catch((e) => setError(String(e)));
  }, []);

  const refresh = useCallback(async () => {
    setBusy(true);
    setError(null);
    try {
      await sync(client, { installMode: InstallMode.ON_NEXT_RESTART }, (_status, failure) => {
        if (failure) setError(failure.message);
      });
      const [r, p] = await Promise.all([
        client.getCurrentPackage(),
        client.getPendingPackage(),
      ]);

      setPending(p);
      setRunning(r);
      console.log('running', r && r.label, 'pending', p && p.label);
    } catch (e) {
      console.log(e);
      setError(String(e));
    } finally {
      setBusy(false);
    }
  }, []);

  const rollbackPending = useCallback(async () => {
    if (!pending) return;
    try {
      await pending.rollback();
      setPending(null);
      setError(null);
    } catch (e) {
      setError(String(e));
    }
  }, [pending]);

  return (
    <View style={[styles.root, { paddingTop: insets.top + 24 }]}>
      <Text style={styles.title}>NitroPush demo</Text>
      <Text style={styles.subtitle}>native-driven · Embedded v1</Text>

      <View style={styles.card}>
        <Text style={styles.label}>Running</Text>
        <Text style={styles.value}>
          {running ? `${running.label} · ${running.appVersion}` : 'binary bundle'}
        </Text>

        <Text style={styles.label}>Pending</Text>
        <Text style={styles.value}>
          {pending ? `${pending.label} · ${pending.appVersion}` : '—'}
        </Text>

        {error ? (
          <>
            <Text style={styles.label}>Error</Text>
            <Text style={[styles.value, styles.error]}>{error}</Text>
          </>
        ) : null}
      </View>

      <Pressable style={styles.button} disabled={busy} onPress={refresh}>
        <Text style={styles.buttonLabel}>{busy ? 'Working…' : 'Refresh'}</Text>
      </Pressable>

      <Pressable
        style={[styles.button, styles.secondaryButton, !pending && styles.disabledButton]}
        disabled={!pending}
        onPress={() => client.restartApp(true)}>
        <Text style={styles.buttonLabel}>Apply pending update</Text>
      </Pressable>

      <Pressable
        style={[styles.button, styles.secondaryButton, !pending && styles.disabledButton]}
        disabled={!pending}
        onPress={rollbackPending}>
        <Text style={styles.buttonLabel}>Rollback pending</Text>
      </Pressable>

      <Text style={styles.hint}>
        notifyAppReady runs only after this React screen mounts successfully.
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, padding: 24, backgroundColor: '#0b1020' },
  title: { color: '#fff', fontSize: 24, fontWeight: '600' },
  subtitle: { color: '#7c8ab0', marginBottom: 24, fontFamily: 'Menlo' },
  card: {
    backgroundColor: '#141a30',
    padding: 16,
    borderRadius: 12,
    marginBottom: 24,
  },
  label: {
    color: '#7c8ab0',
    fontSize: 11,
    textTransform: 'uppercase',
    letterSpacing: 1,
    marginTop: 8,
  },
  value: { color: '#fff', fontSize: 16, fontFamily: 'Menlo', marginTop: 2 },
  error: { color: '#ff8a8a' },
  button: {
    backgroundColor: '#3b82f6',
    paddingVertical: 14,
    borderRadius: 12,
    alignItems: 'center',
    marginBottom: 12,
  },
  secondaryButton: { backgroundColor: '#1f2a44' },
  disabledButton: { opacity: 0.5 },
  buttonLabel: { color: '#fff', fontSize: 16, fontWeight: '600' },
  hint: {
    color: '#7c8ab0',
    fontSize: 12,
    marginTop: 16,
    fontFamily: 'Menlo',
    lineHeight: 18,
  },
});

export default App;
