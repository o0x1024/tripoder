package dev.tripoder.runtime;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Integration test: input APKs are compiled and cryptographically signed by SDK tools. */
public final class ArtifactSetTest {
    private static int checks;
    private static final List<String> names=new ArrayList<>();
    private static Path fixtures,work;
    private interface Attempt {void run()throws Exception;}
    private static void check(String name,boolean value){if(!value)throw new AssertionError(name);checks++;names.add(name);}
    private static void rejects(String name,String reason,Attempt attempt)throws Exception{
        try{attempt.run();throw new AssertionError(name+" accepted");}catch(IOException|IllegalArgumentException e){check(name,e.getMessage().contains(reason));}
    }
    private static Path stage(String... inputs)throws Exception{
        Path out=Files.createTempDirectory(work,"stage-");for(String input:inputs)Files.copy(fixtures.resolve(input+".apk"),out.resolve(input+".apk"));return out;
    }
    private static void writable(Path path)throws Exception{Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(Files.isDirectory(path)?"rwx------":"rw-------"));}
    public static void main(String[] args)throws Exception{
        fixtures=Path.of(args[0]);work=Path.of(args[1]);Files.createDirectories(work);
        ArtifactSet javaSet=ArtifactSet.materialize(stage("base").toFile());
        check("pure-java-apk",javaSet.nativeFiles.isEmpty()&&javaSet.parts.size()==1);
        check("cryptographic-signer",javaSet.signerSha.matches("[0-9a-f]{64}"));
        check("all-component-kinds",javaSet.components.size()==5);
        check("application-process-inherited",javaSet.processes.contains("dev.tripoder.set:app")&&javaSet.processes.contains("dev.tripoder.set:remote"));
        check("manifest-intent-filter-preserved",javaSet.components.stream().anyMatch(c->c.declaration.children.stream().anyMatch(e->e.name.equals("intent-filter"))));
        check("descriptor-stable",ArtifactSet.verify(javaSet.directory,javaSet.descriptorSha).descriptorJson.equals(javaSet.descriptorJson));
        ArtifactSet typed=ArtifactSet.materialize(stage("typed").toFile());
        ApkManifest.Element typedApp=typed.parts.get(0).manifest.application;
        check("float-type-and-bits-preserved",typedApp.attributes.get("android:maxAspectRatio").equals("@typed:0x04:0x4019999a"));
        check("compiler-string-and-color-types-preserved",typedApp.children.get(0).attributes.get("android:value").equals("12dp")&&typedApp.children.get(1).attributes.get("android:value").equals("50%")&&typedApp.children.get(2).attributes.get("android:value").equals("@typed:0x1c:0xff123456"));
        check("typed-descriptor-roundtrip",ArtifactSet.verify(typed.directory,typed.descriptorSha).descriptorJson.equals(typed.descriptorJson));
        rejects("typed-value-not-identity","IDENTITY",()->ApkManifest.text(typedApp.android("maxAspectRatio",null),"IDENTITY"));
        ArtifactSet repeated=ArtifactSet.materialize(stage("repeated").toFile());
        check("raw-repeated-declarations-retained",repeated.baseManifest.components.size()==4);
        check("identical-activity-unified-provider-authorities-retained",repeated.components.size()==3&&repeated.components.get(1).authorities.equals("dev.tripoder.first")&&repeated.components.get(2).authorities.equals("dev.tripoder.second"));
        ArtifactSet conflict=ArtifactSet.materialize(stage("conflicting-activity").toFile());
        check("last-component-identity-effective",conflict.components.size()==3&&Boolean.TRUE.equals(conflict.components.get(0).exported)&&conflict.components.get(0).process.equals("dev.tripoder.set:last"));
        check("raw-conflicting-declarations-retained",conflict.baseManifest.components.size()==6);
        check("duplicate-filters-retained",ArtifactSet.manifestFilters(conflict.components.get(0)).size()==2);
        check("superseded-process-not-required",!conflict.processes.contains("dev.tripoder.set:old"));
        check("last-service-and-receiver-effective",Boolean.TRUE.equals(conflict.components.get(1).enabled)&&Boolean.TRUE.equals(conflict.components.get(2).exported));
        check("duplicate-component-descriptor-roundtrip",ArtifactSet.verify(conflict.directory,conflict.descriptorSha).descriptorSha.equals(conflict.descriptorSha));
        ArtifactSet baseHint=ArtifactSet.materialize(stage("base-split-hint").toFile());
        check("base-split-hint-not-a-dependency",baseHint.parts.size()==1&&baseHint.baseManifest.usesSplits.equals(List.of("video")));
        ArtifactSet flags=ArtifactSet.materialize(stage("resource-flags").toFile());
        check("resource-flags-remain-unresolved-metadata",flags.components.get(0).enabled==null&&flags.components.get(0).exported==null&&flags.components.get(0).attributes.get("android:enabled").startsWith("@0x"));
        check("resource-flag-descriptor-roundtrip",ArtifactSet.verify(flags.directory,flags.descriptorSha).descriptorJson.equals(flags.descriptorJson));
        // macOS requires owner write for renaming a directory; production publication uses Os.rename.
        Path moved=work.resolve("published");writable(javaSet.directory.toPath());Files.move(javaSet.directory.toPath(),moved);ArtifactSet.seal(moved,true);
        check("publish-rename-stable",ArtifactSet.verify(moved.toFile(),javaSet.descriptorSha).descriptorSha.equals(javaSet.descriptorSha));
        ArtifactSet splits=ArtifactSet.materialize(stage("base","feature","config").toFile());
        check("base-feature-config",splits.parts.size()==3&&splits.parts.get(0).manifest.splitName.isEmpty()&&splits.parts.get(1).manifest.splitName.equals("feature"));
        check("split-process-inherited",splits.components.stream().anyMatch(c->c.name.equals("dev.tripoder.set.Feature")&&c.process.equals("dev.tripoder.set:app")));
        ArtifactSet nativeSet=ArtifactSet.materialize(stage("native").toFile());
        check("arm64-library-hashed",nativeSet.nativeFiles.size()==1&&nativeSet.files.size()==3);
        check("all-zip-files-hashed",nativeSet.parts.get(0).entries.stream().anyMatch(e->e.path.equals("AndroidManifest.xml"))&&nativeSet.parts.get(0).entries.stream().anyMatch(e->e.path.startsWith("lib/arm64-v8a/")));
        ArtifactSet mixed=ArtifactSet.materialize(stage("mixedabi").toFile());
        check("mixed-abi-selects-arm64-with-literal-dollar-name",mixed.nativeFiles.size()==1&&mixed.nativeFiles.get(0).path.equals("lib/libapp.$cso.so")&&!new File(mixed.directory,"lib/libunused.so").exists());
        check("unselected-abi-is-still-hashed",mixed.parts.get(0).entries.stream().anyMatch(e->e.path.equals("lib/armeabi-v7a/libunused.so")&&e.sha256.length()==64));
        ArtifactSet opaque=ArtifactSet.materialize(stage("opaque-native-resource").toFile());
        check("native-directory-opaque-resource-preserved",Files.readString(opaque.directory.toPath().resolve("lib/libpacked.so")).equals("owned compressed-data fixture"));
        ArtifactSet resources=ArtifactSet.materialize(stage("non-native-lib-resources").toFile());
        check("lib-resources-not-native-abi-requirement",resources.nativeFiles.isEmpty());
        check("all-lib-resources-authenticated",resources.parts.get(0).entries.stream().filter(e->e.path.startsWith("lib/")&&e.sha256.length()==64).count()==3);
        check("lib-resources-verify-roundtrip",ArtifactSet.verify(resources.directory,resources.descriptorSha).descriptorSha.equals(resources.descriptorSha));
        ArtifactSet nativeResources=ArtifactSet.materialize(stage("native-with-resources").toFile());
        check("only-platform-native-name-extracted",nativeResources.nativeFiles.size()==1&&nativeResources.nativeFiles.get(0).path.equals("lib/libowned.so")&&!new File(nativeResources.directory,"lib/test.so").exists());
        rejects("missing-base","BASE_MISSING",()->ArtifactSet.materialize(stage("feature").toFile()));
        rejects("duplicate-base","DUPLICATE_SPLIT",()->ArtifactSet.materialize(stage("base","basecopy").toFile()));
        rejects("package-mismatch","APK_SET_IDENTITY_MISMATCH",()->ArtifactSet.materialize(stage("base","wrongpackage").toFile()));
        rejects("version-mismatch","APK_SET_IDENTITY_MISMATCH",()->ArtifactSet.materialize(stage("base","wrongversion").toFile()));
        rejects("signer-mismatch","APK_SET_SIGNER_MISMATCH",()->ArtifactSet.materialize(stage("base","wrongsigner").toFile()));
        rejects("missing-split-dependency","SPLIT_DEPENDENCY_MISSING",()->ArtifactSet.materialize(stage("base","dependent").toFile()));
        rejects("missing-config-target","SPLIT_DEPENDENCY_MISSING",()->ArtifactSet.materialize(stage("base","config").toFile()));
        rejects("split-cycle","SPLIT_DEPENDENCY_CYCLE",()->ArtifactSet.materialize(stage("base","cyclea","cycleb").toFile()));
        rejects("required-split","REQUIRED_SPLIT_MISSING",()->ArtifactSet.materialize(stage("required").toFile()));
        rejects("wrong-abi","ABI_OR_LIBRARY_UNSUPPORTED",()->ArtifactSet.materialize(stage("wrongabi").toFile()));
        ArtifactSet foreignElf=ArtifactSet.materialize(stage("fakeelf").toFile());
        check("foreign-abi-native-straggler-preserved",Files.size(foreignElf.directory.toPath().resolve("lib/libbad.so"))==20);
        rejects("unsigned-apk","APK_SIGNATURE_INVALID",()->ArtifactSet.materialize(stage("unsigned").toFile()));
        rejects("tampered-signature","APK_SIGNATURE_INVALID",()->ArtifactSet.materialize(stage("tampered").toFile()));
        rejects("zip-traversal","UNSAFE_ZIP_PATH",()->ArtifactSet.materialize(stage("ziptraversal").toFile()));
        rejects("zip-symlink","ZIP_LINK_OR_SPECIAL_FILE",()->ArtifactSet.materialize(stage("zipsymlink").toFile()));
        rejects("zip-duplicate","ZIP_DUPLICATE_ENTRY",()->ArtifactSet.materialize(stage("zipduplicate").toFile()));
        rejects("expanded-size-limit","ZIP_ENTRY_SIZE",()->ArtifactSet.materialize(stage("zipbomb").toFile()));
        rejects("wrong-descriptor-digest","DESCRIPTOR_CORRUPT",()->ArtifactSet.verify(moved.toFile(),"0".repeat(64)));
        Path linked=Files.createTempDirectory(work,"link-input-");Files.createSymbolicLink(linked.resolve("base.apk"),fixtures.resolve("base.apk"));
        rejects("staged-symlink","ARTIFACT_SYMLINK",()->ArtifactSet.materialize(linked.toFile()));
        writable(nativeSet.directory.toPath());Path lib=nativeSet.directory.toPath().resolve(nativeSet.nativeFiles.get(0).path);writable(lib);Files.write(lib,new byte[]{1});ArtifactSet.seal(lib,false);ArtifactSet.seal(nativeSet.directory.toPath(),true);
        rejects("recovery-native-corruption","ARTIFACT_FILE_CORRUPT",()->ArtifactSet.verify(nativeSet.directory,nativeSet.descriptorSha));
        writable(moved);Path base=moved.resolve("base.apk");Files.delete(base);Files.createSymbolicLink(base,fixtures.resolve("base.apk"));ArtifactSet.seal(moved,true);
        rejects("recovery-apk-symlink","ARTIFACT_SYMLINK",()->ArtifactSet.verify(moved.toFile(),javaSet.descriptorSha));
        Path root=splits.directory.toPath();writable(root);Files.write(root.resolve("extra"),new byte[]{1});ArtifactSet.seal(root.resolve("extra"),false);ArtifactSet.seal(root,true);
        rejects("recovery-extra-file","STAGE_FILE_UNSUPPORTED",()->ArtifactSet.verify(splits.directory,splits.descriptorSha));
        ArtifactSet dirty=ArtifactSet.materialize(stage("base").toFile());writable(dirty.base.toPath());
        rejects("recovery-writable-file","ARTIFACT_NOT_IMMUTABLE",()->ArtifactSet.verify(dirty.directory,dirty.descriptorSha));
        ArtifactSet changed=ArtifactSet.materialize(stage("base").toFile());Path desc=changed.directory.toPath().resolve(ArtifactSet.DESCRIPTOR);writable(desc);Files.writeString(desc,"{}");ArtifactSet.seal(desc,false);
        rejects("recovery-descriptor-corruption","DESCRIPTOR_CORRUPT",()->ArtifactSet.verify(changed.directory,changed.descriptorSha));
        // ART drops a writable oat/odex/vdex sidecar next to the on-disk guest DEX on first run; re-verification must
        // tolerate that derived cache instead of bricking the relaunch with ARTIFACT_NOT_IMMUTABLE/FILE_SET_MISMATCH.
        ArtifactSet opt=ArtifactSet.materialize(stage("base").toFile());Path optRoot=opt.directory.toPath();writable(optRoot);
        Path oat=optRoot.resolve("oat").resolve("arm64");Files.createDirectories(oat);Files.write(oat.resolve("base.odex"),new byte[]{7});Files.write(oat.resolve("base.vdex"),new byte[]{8});ArtifactSet.seal(optRoot,true);
        check("tolerates-art-oat-sidecar",ArtifactSet.verify(opt.directory,opt.descriptorSha).descriptorSha.equals(opt.descriptorSha));
        System.out.println("{\"passed\":true,\"assertions\":"+checks+",\"checks\":[\""+String.join("\",\"",names)+"\"]}");
    }
}
