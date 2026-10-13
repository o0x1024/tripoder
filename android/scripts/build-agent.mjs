import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = fileURLToPath(new URL('../../', import.meta.url));
await build({
  absWorkingDir: root,
  entryPoints: ['android/agent/index.ts'],
  outfile: path.join(root, 'android/app/src/main/jniLibs/arm64-v8a/libagent.js.so'),
  bundle: true, format: 'iife', platform: 'neutral', target: 'es2022',
  logLevel: 'info',
});
