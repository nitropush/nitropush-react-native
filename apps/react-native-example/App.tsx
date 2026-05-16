/**
 * NitroPush demo for the bare React Native example — **native-driven**.
 *
 * Everything that touches the SDK is native:
 *
 *   ios/NitropushRNExample/AppDelegate.swift
 *     • configure() before React Native loads
 *     • detached check → download → install task on every cold start
 *     • notifyAppReady() in applicationDidBecomeActive
 *     • bundleURL() short-circuit to the active OTA bundle
 *
 *   android/.../MainApplication.kt
 *     • configure() before React Native loads
 *     • background-thread check → download → install on every cold start
 *     • activeBundleFile() handed to the React host
 *
 *   android/.../MainActivity.kt
 *     • notifyAppReady() in onResume
 *
 * This file (App.tsx) is purely a status display — it never calls any
 * mutating SDK method. It builds a JS-side client via `configure()`
 * (reading Info.plist / manifest meta-data the native side already
 * applied) and inspects state via `getUpdateMetadataSync` +
 * `getPendingUpdate`. User-controlled actions ("apply pending",
 * "rollback") are surfaced so the user can apply or discard a staged
 * bundle without waiting for the next cold start.
 *
 * @format
 */

import { useCallback, useMemo, useState } from 'react';
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
  configureWith,
  type LocalPackage,
  type NitroPushClient,
} from '@nitropush/react-native';

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
  // Reads NITROPUSH_* keys from Info.plist (iOS) / AndroidManifest
  // meta-data (Android). The native side already applied the same
  // config at launch — this client just gives JS a handle.
  const client: NitroPushClient = useMemo(() => configureWith({
    serverUrl: 'http://192.168.0.141:3003',
    storageBaseUrl: 'http://192.168.0.141:9001/nitrolift-bundles',
    deploymentKey: 'nl_test_gLaFtFCoG6M6v3WrTCT8yConLA0onKfb1HxU7k8EoxI'
  }), []);

  // First-paint reads via the sync helper — avoids a microtask hop and
  // gives us metadata before the first frame paints. Falls back to the
  // async helper afterwards in case the singleton wasn't ready yet on
  // the very first call (race with native bootstrap).
  const [running, setRunning] = useState<LocalPackage | null>(() =>
    client.getUpdateMetadataSync(),
  );
  const [pending, setPending] = useState<LocalPackage | null>(null);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const [r, p, u] = await Promise.all([
        client.getCurrentPackage(),
        client.getPendingPackage(),
        client.checkForUpdate(),
      ]);

      setPending(p);
      console.log('remote', u && u.label);
      console.log('running', r && r.label, 'pending', p && p.label);
      setError(null);
    } catch (e) {
      console.log(e);
      setError(String(e));
    }
  }, [client]);

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
      <Text style={styles.subtitle}>native-driven</Text>

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

      <Pressable style={styles.button} onPress={refresh}>
        <Text style={styles.buttonLabel}>Refresh</Text>
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
        configure / sync / notifyAppReady all run from native. JS just observes.
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
