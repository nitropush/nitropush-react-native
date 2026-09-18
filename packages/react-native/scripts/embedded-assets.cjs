#!/usr/bin/env node
// Build-time only: inventory exact native bundler outputs, not project source paths.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const INDEX_NAME = 'nitropush-embedded-assets.json';
const MAX_ENTRIES = 10000;
const MAX_INDEX_BYTES = 2 * 1024 * 1024;
const MAX_TOTAL_BYTES = 128 * 1024 * 1024;
const DENSITIES = { ldpi: 120, mdpi: 160, hdpi: 240, xhdpi: 320, xxhdpi: 480, xxxhdpi: 640, nodpi: 0 };

function inventory(options) {
  if (!['ios', 'android', 'nativescript-ios', 'nativescript-android'].includes(options.platform)) throw new Error('Unsupported embedded inventory platform');
  const entries = [];
  let visited = 0, hashedBytes = 0, skipped = 0;
  const descriptorKeys = new Set();
  function walk(root, consume) {
    if (!root || !fs.existsSync(root)) return;
    root = path.resolve(root);
    function visit(directory, depth) {
      if (depth > 32) throw new Error('Embedded assets exceed maximum path depth');
      for (const entry of fs.readdirSync(directory, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
        if (++visited > 20000) throw new Error('Embedded assets exceed maximum directory entries');
        if (entry.name === INDEX_NAME || entry.name.startsWith('.')) continue;
        if (entry.isSymbolicLink()) throw new Error('Embedded assets must not contain symlinks');
        const file = path.join(directory, entry.name);
        if (entry.isDirectory()) visit(file, depth + 1);
        else if (entry.isFile()) consume(file, path.relative(root, file).split(path.sep).join('/'));
      }
    }
    if (fs.lstatSync(root).isSymbolicLink()) throw new Error('Embedded assets root must not be a symlink');
    visit(root, 0);
  }
  function add(file, location, bundle = false) {
    const size = fs.statSync(file).size;
    if (!Number.isSafeInteger(size) || size <= 0 || size > (bundle ? 64 : 16) * 1024 * 1024 || hashedBytes + size > MAX_TOTAL_BYTES) { skipped++; return; }
    if (entries.length >= MAX_ENTRIES) throw new Error('Embedded assets exceed maximum inventory entries');
    const key = JSON.stringify(location);
    if (descriptorKeys.has(key)) throw new Error('Duplicate embedded asset location');
    descriptorKeys.add(key);
    const digest = crypto.createHash('sha256');
    const buffer = Buffer.alloc(64 * 1024);
    const fd = fs.openSync(file, 'r');
    let read = 0;
    try {
      while (true) {
        const count = fs.readSync(fd, buffer, 0, buffer.length, null);
        if (count === 0) break;
        read += count;
        if (read > size) throw new Error('Embedded asset changed during hashing');
        digest.update(buffer.subarray(0, count));
      }
    } finally { fs.closeSync(fd); }
    if (read !== size) throw new Error('Embedded asset changed during hashing');
    hashedBytes += read;
    entries.push({ sha256: digest.digest('hex'), size, ...location });
  }
  if (options.platform.startsWith('nativescript-')) {
    if (!options.appRoot) throw new Error('--app-root is required');
    walk(options.appRoot, (file, relative) => add(file, { kind: 'file', path: `app/${relative}` }, /(?:^|\/)bundle\.js$/.test(relative)));
  } else if (options.platform === 'ios') {
    if (!options.bundleRoot) throw new Error('--bundle-root is required');
    for (const folder of ['assets', 'app']) walk(path.join(options.bundleRoot, folder), (file, relative) => add(file, { kind: 'file', path: `${folder}/${relative}` }));
    const bundle = path.join(options.bundleRoot, options.bundleName || 'main.jsbundle');
    if (fs.existsSync(bundle)) {
      if (!fs.lstatSync(bundle).isFile()) throw new Error('Embedded bundle must be a regular file');
      add(bundle, { kind: 'file', path: options.bundleName || 'main.jsbundle' }, true);
    }
  } else {
    if (!options.assetsRoot || !options.resourcesRoot) throw new Error('--assets-root and --resources-root are required');
    walk(options.assetsRoot, (file, relative) => add(file, { kind: 'file', path: relative }, relative === (options.bundleName || 'index.android.bundle')));
    walk(options.resourcesRoot, (file, relative) => {
      const parts = relative.split('/');
      if (parts.length !== 2) return;
      const [folder, filename] = parts;
      const match = /^(drawable|raw)(?:-([a-z0-9]+))?$/.exec(folder);
      if (!match || filename === 'keep.xml') return;
      const resourceName = filename.slice(0, filename.indexOf('.'));
      if (!/^[a-z_][a-z0-9_]*$/.test(resourceName)) return;
      const density = match[1] === 'raw' ? 0 : DENSITIES[match[2]];
      if (density === undefined) { skipped++; return; }
      add(file, { kind: 'resource', resourceType: match[1], resourceName, density });
    });
  }
  entries.sort((a, b) => a.sha256.localeCompare(b.sha256) || JSON.stringify(a).localeCompare(JSON.stringify(b)));
  const value = { schemaVersion: 1, platform: options.platform.endsWith('android') ? 'android' : 'ios', entries };
  const json = JSON.stringify(value) + '\n';
  if (Buffer.byteLength(json) > MAX_INDEX_BYTES) throw new Error('Embedded asset inventory exceeds maximum JSON bytes');
  return { value, json, skipped };
}

function writeInventory(options) {
  const result = inventory(options);
  const output = options.output || (options.appRoot ? path.join(options.appRoot, INDEX_NAME) : options.bundleRoot ? path.join(options.bundleRoot, INDEX_NAME) : path.join(options.assetsRoot, INDEX_NAME));
  fs.mkdirSync(path.dirname(output), { recursive: true });
  const temporary = `${output}.tmp-${process.pid}`;
  try { fs.writeFileSync(temporary, result.json); fs.renameSync(temporary, output); }
  finally { if (fs.existsSync(temporary)) fs.unlinkSync(temporary); }
  return { ...result, output };
}

function commandLine(argv) {
  const options = {};
  const names = { '--platform': 'platform', '--bundle-root': 'bundleRoot', '--assets-root': 'assetsRoot', '--resources-root': 'resourcesRoot', '--app-root': 'appRoot', '--bundle-name': 'bundleName', '--output': 'output' };
  for (let i = 0; i < argv.length; i += 2) {
    if (!names[argv[i]] || !argv[i + 1] || argv[i + 1].startsWith('--')) throw new Error('Expected supported named arguments with values');
    options[names[argv[i]]] = argv[i + 1];
  }
  return options;
}
module.exports = { INDEX_NAME, inventory, writeInventory, commandLine };
if (require.main === module) {
  try {
    const result = writeInventory(commandLine(process.argv.slice(2)));
    console.log(`[NitroPush] Embedded asset index: ${result.value.entries.length} reusable candidates, ${result.skipped} outside reuse limits`);
  } catch (error) { console.error(`[NitroPush] ${error.message}`); process.exitCode = 1; }
}
