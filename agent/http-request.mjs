import {argumentError} from './schema-validation.mjs';
export function validateHttpRequest(request){
  const text=request.url;let url;
  try{url=new URL(text);}catch{throw argumentError('/request/url','HTTP_URL_INVALID','absolute ASCII HTTP(S) URL');}
  const malformedPercent=[...text].some((char,index)=>char==='%'&&!/^[0-9a-fA-F]{2}$/.test(text.slice(index+1,index+3)));
  const authority=text.split('://')[1]?.split(/[/?#]/)[0]??'';
  if(!/^[\x21-\x7e]+$/.test(text)||/[<>"{}|\\^`]/.test(text)||malformedPercent||authority.includes('%')||authority.includes('@')||text.includes('#')||!['http:','https:'].includes(url.protocol)||!url.hostname||url.port==='0')
    throw argumentError('/request/url','HTTP_URL_INVALID','ASCII HTTP(S) URL with valid escapes, host, no userinfo or fragment');
  if(['GET','HEAD'].includes(request.method)&&Object.hasOwn(request,'body'))throw argumentError('/request/body','HTTP_BODY_NOT_ALLOWED','omit body for GET/HEAD');
  for(const [name,value] of Object.entries(request.headers??{})){const path='/request/headers/'+name.replaceAll('~','~0').replaceAll('/','~1');if(!/^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/.test(name)||/[\x00-\x08\x0a-\x1f\x7f]/.test(value))throw argumentError(path,'HTTP_HEADER_ENTRY_INVALID','HTTP token name and value without control characters');}
}
