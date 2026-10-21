/** Validate exported unpacked DEX files without external tools.
 *
 * For every *.dex in the target directory it parses the DEX header, checks:
 *   - magic == "dex\n035\0"
 *   - declared file_size == on-disk size
 *   - header checksum == Adler-32 over bytes[12..]
 *   - header SHA-1 signature == SHA-1 over bytes[32..]
 *   - whole-file SHA-256 matches manifest.json (artifact receipt hash)
 * and reports class/method/string counts. It also checks that class descriptors
 * observed live in the guest process appear in the exported bytes.
 *
 * Usage: node scripts/verify-unpacked-dex.mjs --dir unpacked/netease-cloudmusic \
 *          --live-class com.aegis.sdk.AegisNative --live-class com.BV.LinearGradient.LinearGradientView
 */
import { readFile, readdir, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { createHash } from 'node:crypto';

const argv = process.argv.slice(2);
const options = { '--live-class': [] };
for (let i = 0; i < argv.length; i += 1) {
  const key = argv[i];
  if (key === '--live-class') options[key].push(argv[i + 1]);
  else options[key] = argv[i + 1];
}
const directory = resolve(options['--dir'] ?? 'unpacked');
const manifest = JSON.parse(await readFile(join(directory, 'manifest.json'), 'utf8'));

function adler32(buffer) {
  let a = 1;
  let b = 0;
  for (const byte of buffer) {
    a = (a + byte) % 65521;
    b = (b + a) % 65521;
  }
  return ((b << 16) | a) >>> 0;
}

const rows = [];
for (const name of (await readdir(directory)).filter((f) => f.endsWith('.dex')).sort((x, y) =>
  x.localeCompare(y, undefined, { numeric: true }),
)) {
  const bytes = await readFile(join(directory, name));
  const u32 = (o) => bytes.readUInt32LE(o);
  const header = {
    magic: bytes.subarray(0, 8).toString('latin1'),
    checksum: u32(8),
    signature: bytes.subarray(12, 32).toString('hex'),
    fileSize: u32(32),
    headerSize: u32(36),
    endianTag: u32(40),
    stringIdsSize: u32(56),
    methodIdsSize: u32(88),
    classDefsSize: u32(96),
  };
  const manifestRow = manifest.files.find((f) => f.name === name);
  const sha256 = createHash('sha256').update(bytes).digest('hex');
  const row = {
    name,
    onDiskSize: bytes.length,
    declaredFileSize: header.fileSize,
    dexVersion: header.magic.startsWith('dex\n035') ? '035' : header.magic,
    endianTag: header.endianTag === 0x12345678 ? 'little' : `0x${header.endianTag.toString(16)}`,
    headerSize: header.headerSize,
    classes: header.classDefsSize,
    methods: header.methodIdsSize,
    strings: header.stringIdsSize,
    checksumVerified: adler32(bytes.subarray(12)) === header.checksum,
    signatureVerified:
      createHash('sha1').update(bytes.subarray(32)).digest('hex') === header.signature,
    sha256,
    matchesReceipt: manifestRow?.sha256 === sha256,
    matchesManifestSize: manifestRow?.bytes === bytes.length,
  };
  row.valid =
    row.dexVersion === '035' &&
    row.onDiskSize === row.declaredFileSize &&
    row.headerSize === 112 &&
    row.endianTag === 'little' &&
    row.checksumVerified &&
    row.signatureVerified &&
    row.matchesReceipt &&
    row.matchesManifestSize;
  rows.push(row);
}

const liveChecks = [];
for (const className of options['--live-class']) {
  const descriptor = `L${className.replaceAll('.', '/')};`;
  const needle = Buffer.from(descriptor, 'latin1');
  const found = [];
  for (const name of rows.map((r) => r.name)) {
    const bytes = await readFile(join(directory, name));
    if (bytes.includes(needle)) found.push(name);
  }
  liveChecks.push({ className, descriptor, foundIn: found, present: found.length > 0 });
}

const summary = {
  schema: 1,
  verifiedAt: new Date().toISOString(),
  directory,
  files: rows.length,
  allValid: rows.every((r) => r.valid),
  totalBytes: rows.reduce((sum, r) => sum + r.onDiskSize, 0),
  totalClasses: rows.reduce((sum, r) => sum + r.classes, 0),
  totalMethods: rows.reduce((sum, r) => sum + r.methods, 0),
  manifestPackage: manifest.package,
  manifestVersionCode: manifest.versionCode,
  manifestApkSha256: manifest.apkSha256,
  manifestProcessGeneration: manifest.processGeneration,
  rows,
  liveClassCrossCheck: liveChecks,
};
await writeFile(join(directory, 'verification.json'), `${JSON.stringify(summary, null, 2)}\n`);

for (const row of rows) {
  console.log(`${row.valid ? 'OK  ' : 'FAIL'} ${row.name}  size=${row.onDiskSize} dex=${row.dexVersion} endian=${row.endianTag} classes=${row.classes} methods=${row.methods} checksum=${row.checksumVerified} sha1=${row.signatureVerified} receiptSha256=${row.matchesReceipt}`);
}
for (const check of liveChecks) {
  console.log(`${check.present ? 'OK  ' : 'FAIL'} live-class ${check.className} -> ${check.foundIn.join(',') || 'NOT_FOUND'}`);
}
console.log(JSON.stringify({ allValid: summary.allValid, files: summary.files, totalBytes: summary.totalBytes, totalClasses: summary.totalClasses, totalMethods: summary.totalMethods, liveClassesAllPresent: liveChecks.every((c) => c.present) }));
if (!summary.allValid) process.exitCode = 1;
