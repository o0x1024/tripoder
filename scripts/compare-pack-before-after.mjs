/** Side-by-side comparison of "before unpack" vs "after unpack" DEX files.
 *
 * Pairs files by name (classes.dex, classes2.dex, ...) between --before and --after
 * and reports, for each pair: sizes, SHA-256, byte-identity, differing-byte count,
 * first difference offset, Shannon entropy (encryption indicator), DEX header
 * self-consistency (Adler-32 checksum over bytes[12..], SHA-1 over bytes[32..])
 * and how many times the app's own class descriptor prefix occurs.
 *
 * Usage: node scripts/compare-pack-before-after.mjs \
 *          --before unpacked/netease-cloudmusic/before \
 *          --after  unpacked/netease-cloudmusic \
 *          --out    unpacked/netease-cloudmusic/compare.json
 */
import { readFile, readdir, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { createHash } from 'node:crypto';

const argv = process.argv.slice(2);
const opt = {};
for (let i = 0; i < argv.length; i += 2) opt[argv[i]] = argv[i + 1];

const beforeDir = resolve(opt['--before']);
const afterDir = resolve(opt['--after']);
const outPath = resolve(opt['--out'] ?? join(afterDir, 'compare.json'));

const dexOrder = (name) => (name === 'classes.dex' ? 1 : Number(name.match(/^classes(\d+)\.dex$/)?.[1] ?? 0));

function adler32(buf) {
  let a = 1;
  let b = 0;
  for (const byte of buf) {
    a = (a + byte) % 65521;
    b = (b + a) % 65521;
  }
  return ((b << 16) | a) >>> 0;
}

function shannon(buf) {
  const hist = new Uint32Array(256);
  for (const byte of buf) hist[byte] += 1;
  let h = 0;
  for (const count of hist) {
    if (!count) continue;
    const p = count / buf.length;
    h -= p * Math.log2(p);
  }
  return Math.round(h * 1000) / 1000;
}

const countOccurrences = (haystack, needle) => {
  let n = 0;
  let at = 0;
  for (;;) {
    const hit = haystack.indexOf(needle, at);
    if (hit < 0) return n;
    n += 1;
    at = hit + 1;
  }
};

function headerOf(bytes) {
  const u32 = (o) => bytes.readUInt32LE(o);
  return {
    magic: bytes.subarray(0, 8).toString('latin1').replace(/\0/g, '\\0'),
    declaredFileSize: u32(32),
    headerSize: u32(36),
    endianTag: u32(40),
    classCount: u32(96),
    methodCount: u32(88),
    stringCount: u32(56),
    checksumOk: bytes.length >= 44 && adler32(bytes.subarray(12)) === u32(8),
    signatureOk:
      bytes.length >= 44 &&
      createHash('sha1').update(bytes.subarray(32)).digest('hex') ===
        bytes.subarray(12, 32).toString('hex'),
  };
}

const beforeNames = (await readdir(beforeDir)).filter((f) => /^classes\d*\.dex$/.test(f));
const pairs = [];
for (const name of beforeNames.sort((x, y) => dexOrder(x) - dexOrder(y))) {
  const b = await readFile(join(beforeDir, name));
  const a = await readFile(join(afterDir, name));

  let differingBytes = 0;
  let firstDiff = -1;
  const span = Math.min(a.length, b.length);
  for (let i = 0; i < span; i += 1) {
    if (a[i] !== b[i]) {
      differingBytes += 1;
      if (firstDiff < 0) firstDiff = i;
    }
  }
  differingBytes += Math.abs(a.length - b.length);

  const probe = Buffer.from('Lcom/netease/', 'latin1');
  const sha256 = (buf) => createHash('sha256').update(buf).digest('hex');

  pairs.push({
    name,
    index: dexOrder(name),
    before: {
      bytes: b.length,
      sha256: sha256(b),
      entropy: shannon(b),
      appClassHits: countOccurrences(b, probe),
      header: headerOf(b),
    },
    after: {
      bytes: a.length,
      sha256: sha256(a),
      entropy: shannon(a),
      appClassHits: countOccurrences(a, probe),
      header: headerOf(a),
    },
    identical: differingBytes === 0,
    sameSize: a.length === b.length,
    differingBytes,
    diffRatio: Math.round((differingBytes / Math.max(a.length, b.length)) * 1e6) / 1e6,
    firstDiffOffset: firstDiff,
  });
}

const summary = {
  schema: 1,
  createdAt: new Date().toISOString(),
  beforeDir,
  afterDir,
  pairs: pairs.length,
  identicalFiles: pairs.filter((p) => p.identical).map((p) => p.name),
  allSameSize: pairs.every((p) => p.sameSize),
  beforeHeaderValid: pairs.filter((p) => p.before.header.checksumOk && p.before.header.signatureOk).length,
  afterHeaderValid: pairs.filter((p) => p.after.header.checksumOk && p.after.header.signatureOk).length,
  pairs_detail: pairs,
};
await writeFile(outPath, `${JSON.stringify(summary, null, 2)}\n`);

console.log(`pair  sizeBefore  sizeAfter   sameSize identical  diffBytes  diff%   beforeHdrOK afterHdrOK  entropyB entropyA  appClsB appClsA`);
for (const p of pairs) {
  printf(p);
}
function printf(p) {
  const pct = (p.diffRatio * 100).toFixed(2);
  console.log(
    `${p.name.padEnd(13)} ${String(p.before.bytes).padStart(9)} ${String(p.after.bytes).padStart(10)}  ${String(p.sameSize).padStart(8)} ${String(p.identical).padStart(9)} ${String(p.differingBytes).padStart(10)} ${pct.padStart(7)}% ${String(p.before.header.checksumOk && p.before.header.signatureOk).padStart(11)} ${String(p.after.header.checksumOk && p.after.header.signatureOk).padStart(10)}  ${String(p.before.entropy).padStart(8)} ${String(p.after.entropy).padStart(8)} ${String(p.before.appClassHits).padStart(7)} ${String(p.after.appClassHits).padStart(7)}`,
  );
}
console.log(
  JSON.stringify({
    pairs: summary.pairs,
    identical: summary.identicalFiles.length,
    sameSizeAll: summary.allSameSize,
    beforeHeaderValid: summary.beforeHeaderValid,
    afterHeaderValid: summary.afterHeaderValid,
    out: outPath,
  }),
);
