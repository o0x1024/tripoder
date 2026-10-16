#!/usr/bin/env node
import { generateKeyPairSync, randomUUID } from 'node:crypto';
import { mkdir, writeFile, readFile, rename, chmod } from 'node:fs/promises';
import { dirname, isAbsolute } from 'node:path';
import { Transport, envelope, fingerprint } from './transport.mjs';

const args=Object.fromEntries(Array.from({length:(process.argv.length-2)/2},(_,i)=>[process.argv[2+i*2],process.argv[3+i*2]]));
if(Object.keys(args).sort().join()!==['--config','--device-public-key-file','--serial'].sort().join()||!isAbsolute(args['--config']))throw Error('Usage: pair.mjs --serial SERIAL --device-public-key-file PATH --config ABSOLUTE_PRIVATE_PATH');
const publicDevice=(await readFile(args['--device-public-key-file'],'utf8')).trim();
fingerprint(publicDevice);
const {privateKey,publicKey}=generateKeyPairSync('ec',{namedCurve:'prime256v1'});
const config={protocol:1,serial:args['--serial'],name:'Sentinel Tripoder MCP',privateKey:privateKey.export({format:'pem',type:'pkcs8'}),publicKey:publicKey.export({format:'der',type:'spki'}).toString('base64'),devicePublicKey:publicDevice,session:''};
const path=args['--config'];await mkdir(dirname(path),{recursive:true,mode:0o700});
await writeFile(path,JSON.stringify(config,null,2)+'\n',{mode:0o600,flag:'wx'});
const transport=new Transport(config);const nonce=randomUUID();
const payload=JSON.stringify({protocol:1,nonce,name:config.name,publicKey:config.publicKey});
const request=envelope(config,'pair',nonce,payload);
try{
  await transport.open();process.stderr.write(`Client fingerprint: ${fingerprint(config.publicKey)}\nApprove this client on the phone for the selected owned process.\n`);
  const deadline=Date.now()+110000;
  while(Date.now()<deadline){
    const response=await transport.send(request);if(!response.ok)throw Error(response.error);
    if(response.state==='PAIRED'){
      config.session=response.session;const temporary=path+'.paired';await writeFile(temporary,JSON.stringify(config,null,2)+'\n',{mode:0o600,flag:'wx'});await rename(temporary,path);await chmod(path,0o600);
      process.stdout.write(JSON.stringify({protocol:1,state:'PAIRED',session:config.session,project:response.project,clientFingerprint:fingerprint(config.publicKey),deviceFingerprint:fingerprint(publicDevice)})+'\n');break;
    }
    if(response.state!=='PENDING_PHONE_APPROVAL')throw Error('PAIR_STATE_UNSUPPORTED');
    await new Promise(resolve=>setTimeout(resolve,700));
  }
  if(!config.session)throw Error('PAIR_APPROVAL_TIMEOUT');
}finally{await transport.close();}
