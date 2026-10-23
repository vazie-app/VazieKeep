// Packs dist/<target> into a zip that can be sideloaded or uploaded to a store, with the licence texts
// of what the build carries. Plain Node: the zip is written by hand so the build needs no extra tool, and
// it is reproducible (sorted entries, a fixed timestamp).
import { crc32, deflateRawSync } from 'node:zlib';
import { existsSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('..', import.meta.url));
const target = process.env.KEEP_BROWSER_TARGET ?? 'chromium';
const dist = join(root, 'dist', target);
if (!existsSync(join(dist, 'manifest.json'))) {
  throw new Error(
    `Nothing to pack: run "pnpm run build" first (${dist}/manifest.json is missing).`,
  );
}
const manifest = JSON.parse(readFileSync(join(dist, 'manifest.json'), 'utf8'));

function walk(dir) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? walk(path) : [path];
  });
}

const files = new Map();
for (const path of walk(dist)) {
  if (path.endsWith('.map')) continue;
  files.set(relative(dist, path).split(sep).join('/'), readFileSync(path));
}

const repo = join(root, '..', '..');
const add = (name, path) => {
  files.set(name, readFileSync(path));
};
add('licenses/THIRD_PARTY_NOTICES.md', join(root, 'THIRD_PARTY_NOTICES.md'));
add('licenses/LICENSE', join(repo, 'LICENSE'));
add('licenses/preact-LICENSE', join(root, 'node_modules', 'preact', 'LICENSE'));
add('licenses/OFL-Geologica.txt', join(repo, 'shared', 'fonts', 'OFL-Geologica.txt'));
add('licenses/OFL-MartianMono.txt', join(repo, 'shared', 'fonts', 'OFL-MartianMono.txt'));
for (const line of readFileSync(join(root, 'wasm-crates.txt'), 'utf8').split('\n')) {
  if (line.trim() === '') continue;
  const [name, version] = line.split(' ');
  const dir = join(repo, 'third-party', 'rust-licenses', `${name}-${version}`);
  if (!existsSync(dir))
    throw new Error(`No licence texts for ${name} ${version} in third-party/rust-licenses`);
  for (const file of readdirSync(dir))
    add(`licenses/rust/${name}-${version}/${file}`, join(dir, file));
}

const DOS_TIME = 0;
const DOS_DATE = ((2026 - 1980) << 9) | (1 << 5) | 1;
const locals = [];
const centrals = [];
let offset = 0;
for (const name of [...files.keys()].sort()) {
  const data = files.get(name);
  const packed = deflateRawSync(data, { level: 9 });
  const nameBytes = Buffer.from(name, 'utf8');
  const crc = crc32(data);
  const header = Buffer.alloc(30);
  header.writeUInt32LE(0x04034b50, 0);
  header.writeUInt16LE(20, 4);
  header.writeUInt16LE(0x0800, 6);
  header.writeUInt16LE(8, 8);
  header.writeUInt16LE(DOS_TIME, 10);
  header.writeUInt16LE(DOS_DATE, 12);
  header.writeUInt32LE(crc, 14);
  header.writeUInt32LE(packed.length, 18);
  header.writeUInt32LE(data.length, 22);
  header.writeUInt16LE(nameBytes.length, 26);
  locals.push(header, nameBytes, packed);
  const central = Buffer.alloc(46);
  central.writeUInt32LE(0x02014b50, 0);
  central.writeUInt16LE(20, 4);
  central.writeUInt16LE(20, 6);
  central.writeUInt16LE(0x0800, 8);
  central.writeUInt16LE(8, 10);
  central.writeUInt16LE(DOS_TIME, 12);
  central.writeUInt16LE(DOS_DATE, 14);
  central.writeUInt32LE(crc, 16);
  central.writeUInt32LE(packed.length, 20);
  central.writeUInt32LE(data.length, 24);
  central.writeUInt16LE(nameBytes.length, 28);
  central.writeUInt32LE(offset, 42);
  centrals.push(central, nameBytes);
  offset += header.length + nameBytes.length + packed.length;
}
const directory = Buffer.concat(centrals);
const end = Buffer.alloc(22);
end.writeUInt32LE(0x06054b50, 0);
end.writeUInt16LE(files.size, 8);
end.writeUInt16LE(files.size, 10);
end.writeUInt32LE(directory.length, 12);
end.writeUInt32LE(offset, 16);
const out = join(root, 'dist', `vazie-keep-${manifest.version}-${target}.zip`);
writeFileSync(out, Buffer.concat([...locals, directory, end]));
console.log(`${out} (${files.size} files)`);
