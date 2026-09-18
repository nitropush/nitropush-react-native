import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
const run = promisify(execFile);
const root = resolve(import.meta.dirname, '..');

test('Swift telemetry cancels an unbounded chunked response at its headers', { skip: process.platform !== 'darwin' && 'Requires the macOS native validation runner' }, async () => {
  const dir = mkdtempSync(join(tmpdir(), 'nitropush-telemetry-test-'));
  let responseBytes = 0, requests = 0, closed = false;
  const server = createServer((req, res) => {
    requests++;
    req.resume();
    res.writeHead(200, { 'content-type': 'application/json' }); res.flushHeaders();
    const timer = setInterval(() => { responseBytes += 65536; res.write(Buffer.alloc(65536)); }, 5);
    res.on('close', () => { closed = true; clearInterval(timer); });
  });
  try {
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    // Compile the unchanged transport on macOS; only UIKit device-label helpers
    // are omitted. The entire NlAnalytics implementation is exercised.
    const source = readFileSync(join(root, 'ios/NitroPushAnalytics.swift'), 'utf8').split('enum NlAnalyticsContext')[0].replace('import UIKit\n', '');
    writeFileSync(join(dir, 'Analytics.swift'), source);
    writeFileSync(join(dir, 'main.swift'), `import Foundation
let analytics = NlAnalytics(serverUrl: CommandLine.arguments[1], deploymentKey: "test-only", deviceToken: nil, flushAt: 1)
analytics.enqueue(NlAnalyticsEvent(eventId: "test", eventType: "app_started", clientUniqueId: "test", appVersion: "1", otaVersion: nil, releaseId: nil, platform: "ios", osVersion: nil, deviceModel: nil, occurredAt: "test", metadata: nil))
Thread.sleep(forTimeInterval: 1.5)
analytics.stop()
Thread.sleep(forTimeInterval: 0.1)
`);
    const binary = join(dir, 'analytics');
    await run('swiftc', [join(dir, 'Analytics.swift'), join(dir, 'main.swift'), '-o', binary], { timeout: 60_000 });
    await run(binary, [`http://127.0.0.1:${server.address().port}`], { timeout: 10_000 });
    assert.equal(requests, 1, '2xx headers count as success, not a cancellation retry');
    assert.equal(closed, true);
    assert(responseBytes < 1024 * 1024, `unexpected response draining: ${responseBytes}`);
  } finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); rmSync(dir, { recursive: true, force: true }); }
});
