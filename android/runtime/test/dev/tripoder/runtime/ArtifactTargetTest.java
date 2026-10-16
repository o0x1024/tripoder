package dev.tripoder.runtime;

import java.nio.file.*;

/** Public installed APK inputs only; no application code is executed by this host check. */
public final class ArtifactTargetTest {
    public static void main(String[] args)throws Exception{
        Path work=Path.of(args[0]);Files.createDirectories(work);
        for(int i=1;i<args.length;i++){
            Path input=Path.of(args[i]),stage=Files.createTempDirectory(work,"target-");long start=System.nanoTime();
            Files.copy(input,stage.resolve("base.apk"));ArtifactSet set=ArtifactSet.materialize(stage.toFile());ArtifactSet verified=ArtifactSet.verify(stage.toFile(),set.descriptorSha);
            long nativeBytes=0,dexBytes=0;int dex=0;for(var file:verified.nativeFiles)nativeBytes+=file.size;
            for(var part:verified.parts)for(var file:part.entries)if(file.path.matches("classes(?:[2-9]|[1-9][0-9]+)?\\.dex")){dex++;dexBytes+=file.size;}
            System.out.println("{\"passed\":true,\"package\":\""+set.packageName+"\",\"version\":"+set.versionCode+",\"apkSha256\":\""+set.parts.get(0).sha256+"\",\"descriptorSha256\":\""+set.descriptorSha+"\",\"selectedAbi\":\"arm64-v8a\",\"nativeFiles\":"+set.nativeFiles.size()+",\"nativeBytes\":"+nativeBytes+",\"dexFiles\":"+dex+",\"dexBytes\":"+dexBytes+",\"elapsedMs\":"+(System.nanoTime()-start)/1000000+",\"runtimeExecutionVerified\":false}");
        }
    }
}
