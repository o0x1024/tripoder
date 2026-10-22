import {writeFile} from 'node:fs/promises';
import {contract} from './tools.mjs';
if(process.argv.length!==3)throw Error('Usage: export-contract.mjs OUTPUT');
await writeFile(process.argv[2],JSON.stringify({protocolVersion:'2025-11-25',entries:contract}));
