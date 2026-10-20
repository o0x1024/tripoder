#!/usr/bin/env node
// Compatibility notice for scripts using the former pairing command.
process.stdout.write(JSON.stringify({state:'PAIRING_NOT_REQUIRED',message:'Start agent/server.mjs --serial DEVICE_SERIAL; no keys or session configuration file are needed.'})+'\n');
