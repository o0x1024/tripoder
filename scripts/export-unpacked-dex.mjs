/** Export COMPLETE dex artifacts of one process generation to local files.
 *
 * Drives the public stdio MCP server (agent/server.mjs) exactly like
 * agent/netease-acceptance.mjs does, but streams each 16 KiB chunk straight to
 * disk instead of into an agent transcript. Every file is verified against the
 * SHA-256 reported by the persisted capture receipt.
 *
 * Usage:
 *   node scripts/export-unpacked-dex.mjs \
 *     --serial d861a4fa \
 *     --project 62bc52dc-ce9b-448c-a3a7-b85118076761 \
 *     --process-generation 98332f38... \
 *     --out unpacked/netease-cloudmusic
 */
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { mkdir, open, readFile, writeFile, rename } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { createHash } from 'node:crypto';

const options = Object.fromEntries(
  process.argv.slice(2).reduce((out, key, i, args) => (i % 2 ? out : [...out, [key, args[i + 1]]]), []),
);
const serial = options['--serial'];
const project = options['--project'];
const generation = options['--process-generation'];
const outDirectory = resolve(options['--out'] ?? 'unpacked/export');
const packageName = options['--package'] ?? 'com.netease.cloudmusic';
const apkSha256 = options['--apk-sha256'] ?? '';
const versionCode = options['--version-code'] ?? '';
if (!serial || !project || !generation) throw Error('SERIAL_PROJECT_GENERATION_REQUIRED');

await mkdir(outDirectory, { recursive: true });

const child = spawn(process.execPath, [resolve('agent/server.mjs'), '--serial', serial], {
  stdio: ['pipe', 'pipe', 'pipe'],
});
let stderr = '';
child.stderr.on('data', (b) => (stderr += b));

const pending = new Map();
let sequence = 0;
createInterface({ input: child.stdout }).on('line', (line) => {
  let message;
  try {
    message = JSON.parse(line);
  } catch {
    return;
  }
  const item = pending.get(message.id);
  if (item) {
    clearTimeout(item.timer);
    pending.delete(message.id);
    item.resolve(message);
  }
});
child.on('exit', (code) => {
  for (const item of pending.values()) {
    clearTimeout(item.timer);
    item.reject(Error(`MCP_EXIT:${code}:${stderr.slice(0, 400)}`));
  }
  pending.clear();
});

async function rpc(method, params = {}, timeoutMs = 60000) {
  const id = ++sequence;
  const response = new Promise((res, rej) => {
    const timer = setTimeout(() => {
      pending.delete(id);
      rej(Error(`MCP_TIMEOUT:${method}`));
    }, timeoutMs);
    pending.set(id, { resolve: res, reject: rej, timer });
  });
  child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', id, method, params })}\n`);
  const value = await response;
  if (value.error) throw Error(JSON.stringify(value.error));
  return value.result;
}

async function tool(name, args = {}) {
  const value = await rpc('tools/call', { name, arguments: args });
  const receipt = JSON.parse(value.content[0].text);
  if (!receipt.ok) throw Error(`${name}:${JSON.stringify(receipt)}`);
  return receipt.result;
}

async function artifactRead(artifact, offset, length) {
  let lastError;
  for (let attempt = 0; attempt < 4; attempt += 1) {
    try {
      const part = await tool('artifact_read', {
        processGeneration: artifact.processGeneration,
        artifactId: artifact.artifactId,
        offset,
        length,
      });
      if (part.offset !== offset) throw Error(`OFFSET_MISMATCH:${part.offset}`);
      if (part.sha256 !== artifact.sha256) throw Error('RECEIPT_SHA_MISMATCH');
      return part;
    } catch (error) {
      lastError = error;
      await new Promise((r) => setTimeout(r, 250 * (attempt + 1)));
    }
  }
  throw lastError;
}

/** Write one artifact to `name`; returns a verified summary row. */
async function download(artifact, name, expectedSha) {
  const target = join(outDirectory, name);
  const file = await open(target, 'w', 0o644);
  const hash = createHash('sha256');
  let offset = 0;
  try {
    while (offset < artifact.size) {
      const part = await artifactRead(artifact, offset, 16384);
      const bytes = Buffer.from(part.data, 'base64');
      if (!bytes.length) throw Error('EMPTY_CHUNK');
      await file.write(bytes);
      hash.update(bytes);
      offset = part.nextOffset ?? offset + bytes.length;
    }
    await file.sync();
  } finally {
    await file.close();
  }
  const digest = hash.digest('hex');
  if (digest !== expectedSha) throw Error(`SHA256_MISMATCH:${name}:${digest}`);
  if (offset !== artifact.size) throw Error(`SIZE_MISMATCH:${name}:${offset}`);
  return { name, bytes: offset, sha256: digest };
}

const progress = (detail) => console.log(JSON.stringify(detail));

try {
  await rpc('initialize', {
    protocolVersion: '2025-03-26',
    capabilities: {},
    clientInfo: { name: 'unpacked-dex-export', version: '1' },
  });
  child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' })}\n`);

  // Dex identity in loader order, so names map to classes.dex / classes2.dex ...
  const dexRows = [];
  let cursor = '';
  do {
    const page = await tool('dex_list', { classLoaderId: '', cursor, limit: 64 });
    dexRows.push(...page.dexFiles);
    cursor = page.nextCursor ?? '';
  } while (cursor);

  const receipts = (await tool('artifacts_list', { processGeneration: generation })).artifacts;
  const complete = receipts.filter((a) => a.state === 'COMPLETE' && a.kind === 'dex');
  const failed = receipts.filter((a) => a.state === 'FAILED');
  progress({
    phase: 'discovered',
    dexObjects: dexRows.length,
    receipts: receipts.length,
    complete: complete.length,
    failed: failed.length,
  });

  const byDexId = new Map(complete.map((a) => [a.dexId, a]));
  const files = [];
  let totalBytes = 0;
  for (const [index, dex] of dexRows.entries()) {
    const artifact = byDexId.get(dex.dexId);
    const ordinal = index === 0 ? 'classes.dex' : `classes${index + 1}.dex`;
    if (!artifact) {
      files.push({ name: ordinal, dexId: dex.dexId, state: 'NO_COMPLETE_ARTIFACT', size: dex.size });
      progress({ phase: 'skip', name: ordinal, dexId: dex.dexId, reason: 'NO_COMPLETE_ARTIFACT' });
      continue;
    }
    const row = await download(artifact, ordinal, artifact.sha256);
    totalBytes += row.bytes;
    files.push({
      ...row,
      dexId: dex.dexId,
      artifactId: artifact.artifactId,
      classLoaderId: dex.classLoaderId,
      receiptSha256: artifact.sha256,
      source: artifact.source,
      consistency: artifact.consistency,
      triggerStage: artifact.triggerStage,
      state: 'COMPLETE',
      verified: true,
    });
    progress({ phase: 'file', name: ordinal, bytes: row.bytes, sha256: row.sha256 });
  }

  const manifest = {
    schema: 1,
    exportedAt: new Date().toISOString(),
    package: packageName,
    versionCode,
    apkSha256,
    project,
    processGeneration: generation,
    transport: 'stdio MCP tools/list artifact_read (16384 byte chunks)',
    tools: ['dex_list', 'artifacts_list', 'artifact_read'],
    directory: outDirectory,
    dexObjectsObserved: dexRows.length,
    artifactsComplete: complete.length,
    totalBytes,
    files,
  };
  const manifestPath = join(outDirectory, 'manifest.json');
  await writeFile(`${manifestPath}.tmp`, `${JSON.stringify(manifest, null, 2)}\n`);
  await rename(`${manifestPath}.tmp`, manifestPath);
  progress({ phase: 'complete', files: files.filter((f) => f.verified).length, totalBytes, manifestPath });
  if (failed.length) progress({ phase: 'failed-receipts', failed: failed.map((f) => f.artifactId) });
} catch (error) {
  progress({ phase: 'error', error: String(error) });
  process.exitCode = 1;
} finally {
  child.stdin.end();
}
