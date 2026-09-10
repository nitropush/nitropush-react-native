import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, readFileSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

const root = resolve(import.meta.dirname, '..');
function integer(n) {
  n = BigInt(n);
  const bytes = Buffer.alloc(8);
  bytes.writeBigUInt64LE(n < 0n ? -n : n);
  if (n < 0n) bytes[7] |= 128;
  return bytes;
}
function compressed(bytes) {
  const result = spawnSync('bzip2', ['-c'], { input: bytes });
  assert.equal(result.status, 0, result.stderr?.toString());
  return result.stdout;
}
function patch(controls, diff, extra, size) {
  const c = compressed(Buffer.concat(controls.flat().map(integer)));
  const d = compressed(Buffer.from(diff));
  const e = compressed(Buffer.from(extra));
  return Buffer.concat([Buffer.from('BSDIFF40'), integer(c.length), integer(d.length), integer(size), c, d, e]);
}
for (const platform of ['ios', 'android/src/main/cpp']) {
  test(`${platform}: decoder rejects hostile lengths under ASan/UBSan`, () => {
    const dir = mkdtempSync(join(tmpdir(), 'nitropush-decoder-test-'));
    try {
      const binary = join(dir, 'decoder');
      const compile = spawnSync('clang', ['-std=c11', '-D_DARWIN_C_SOURCE', '-fsanitize=address,undefined', '-fno-sanitize-recover=all', '-g',
        '-I', join(root, platform, 'bspatch'), join(root, 'test/native/bspatch_driver.c'),
        join(root, platform, 'bspatch/bspatch.c'), '-lbz2', '-o', binary], { encoding: 'utf8' });
      assert.equal(compile.status, 0, compile.stderr);
      const base = join(dir, 'base');
      writeFileSync(base, 'abc');
      let count = 0;
      function run(bytes, size, expected) {
        const input = join(dir, `patch-${count}`), output = join(dir, `output-${count++}`);
        writeFileSync(input, bytes);
        const result = spawnSync(binary, [base, input, output, String(size)], { encoding: 'utf8', timeout: 5000 });
        assert.equal(result.signal, null, result.stderr);
        assert.equal(result.error, undefined);
        assert.doesNotMatch(result.stderr, /Sanitizer|runtime error/);
        if (expected !== undefined) {
          assert.equal(result.status, 0, result.stderr);
          assert.equal(readFileSync(output, 'utf8'), expected);
        } else {
          assert.notEqual(result.status, 0);
          assert.equal(existsSync(output), false, 'rejected input must not produce output');
        }
      }
      run(patch([[3, 0, 0]], [0, 0, 0], [], 3), 3, 'abc');
      run(patch([[0, 0, 2], [1, 0, -3], [1, 0, 0]], [0, 0], [], 2), 2, 'ca');
      run(patch([[-1, 1, 0]], [], [120], 1), 1);
      run(patch([[0, -1, 0]], [], [], 1), 1);
      run(patch([[2147483648n, 0, 0]], [], [], 1), 1);
      run(patch([[0, 0, 9223372036854775807n], [1, 0, 1]], [0], [], 1), 1);
      run(patch([[1, 0, 0]], [0], [], 1), 2);
      run(patch([], [], [], 9223372036854775807n), 9223372036854775807n);
      const malformed = patch([[1, 0, 0]], [0], [], 1);
      integer(9223372036854775807n).copy(malformed, 8);
      run(malformed, 1);
      const crossing = patch([[1, 0, 0]], [0], [], 1);
      integer(1).copy(crossing, 8);
      run(crossing, 1);
      run(patch([[1, 0, 0]], [0, 1], [], 1), 1); // unconsumed diff output
    } finally { rmSync(dir, { recursive: true, force: true }); }
  });
}
