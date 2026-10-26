export const MAX_APK_BYTES=2**31;
export const MAX_APK_SET_BYTES=2**32;

export function admitApkSize(size,total){
  if(!Number.isSafeInteger(size)||size<1||size>MAX_APK_BYTES)throw Error('APK_FILE_SIZE');
  if(!Number.isSafeInteger(total)||total<0||total+size>MAX_APK_SET_BYTES)throw Error('APK_SET_SIZE');
  return total+size;
}
