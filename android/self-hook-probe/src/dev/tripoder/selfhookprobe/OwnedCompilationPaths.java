package dev.tripoder.selfhookprobe;

/** Exact code-file identity derived from the owned APK actually mapped here. */
final class OwnedCompilationPaths {
    static boolean aot(String path,String maps) {
        for(String line:maps.split("\n")){
            String[] fields=line.trim().split("\\s+",6);
            if(fields.length!=6||!fields[1].startsWith("r")||fields[4].equals("0"))continue;
            String apk=fields[5];
            if(!apk.matches("/data/app/[^/\\s]+/dev\\.tripoder\\.selfhookprobe-[^/\\s]+/base\\.apk"))continue;
            String adjacent=apk.substring(0,apk.lastIndexOf('/'))+"/oat/arm64/base.odex";
            String cache="/data/dalvik-cache/arm64/"+apk.substring(1).replace('/','@')+"@classes.dex";
            if(path.equals(adjacent)||path.equals(cache))return true;
        }
        return false;
    }
}
