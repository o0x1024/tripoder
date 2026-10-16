import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
public class LegacyCopyCheck {
  static Method validate,copy;
  static int checks;
  static void call(Method m,Path s,Path d)throws Exception {
    try { m.invoke(null,s.toFile(),d.toFile()); }
    catch(InvocationTargetException e) { if(e.getCause() instanceof Exception x) throw x; throw e; }
  }
  static void check(boolean v,String name) { if(!v)throw new AssertionError(name);checks++; }
  static void denied(Path s,Path d,String expected)throws Exception {
    try {call(validate,s,d);throw new AssertionError("accepted:"+expected);}
    catch(java.io.IOException e){check(e.getMessage().contains(expected),"expected "+expected+", got "+e);}
  }
  public static void main(String[] args)throws Exception {
    Class<?> c=Class.forName("dev.tripoder.hooklab.ProjectStore");
    validate=c.getDeclaredMethod("validateLegacyCopy",java.io.File.class,java.io.File.class);validate.setAccessible(true);
    copy=c.getDeclaredMethod("copyLegacy",java.io.File.class,java.io.File.class);copy.setAccessible(true);
    Path root=Files.createTempDirectory("tripoder-legacy-copy-synthetic-");
    Path source=Files.createDirectory(root.resolve("source")),dest=root.resolve("destination");
    byte[] bytes={0,1,2,(byte)255};Files.write(source.resolve("data.bin"),bytes);
    Files.createDirectory(source.resolve("nested"));Files.writeString(source.resolve("nested/state.txt"),"state");
    call(validate,source,dest);call(copy,source,dest);
    check(Arrays.equals(bytes,Files.readAllBytes(dest.resolve("data.bin"))),"binary copy preserved");
    check(Files.readString(dest.resolve("nested/state.txt")).equals("state"),"nested copy preserved");
    call(validate,source,dest);call(copy,source,dest);
    check(Arrays.equals(bytes,Files.readAllBytes(dest.resolve("data.bin"))),"repeat equal content unchanged");
    Files.writeString(dest.resolve("data.bin"),"existing");
    denied(source,dest,"LEGACY_DESTINATION_CONFLICT");
    check(Files.readString(dest.resolve("data.bin")).equals("existing"),"conflict destination unchanged");
    check(Arrays.equals(bytes,Files.readAllBytes(source.resolve("data.bin"))),"conflict source unchanged");
    Path large=Files.createDirectory(root.resolve("large")),largeDest=root.resolve("large-dest");
    Files.write(large.resolve("over-limit.bin"),new byte[65537]);
    denied(large,largeDest,"LEGACY_FILE_TOO_LARGE");check(!Files.exists(largeDest),"oversize preflight writes nothing");
    Path links=Files.createDirectory(root.resolve("links")),linkDest=root.resolve("link-dest");
    Files.createSymbolicLink(links.resolve("outside"),source);
    denied(links,linkDest,"LEGACY_SYMLINK_UNSUPPORTED");check(!Files.exists(linkDest),"symlink preflight writes nothing");
    Path missing=root.resolve("missing");denied(missing,root.resolve("missing-dest"),"LEGACY_SOURCE_NOT_DIRECTORY");
    Path deniedDir=Files.createDirectory(root.resolve("unreadable"));
    var permissions=Files.getPosixFilePermissions(deniedDir);
    try {Files.setPosixFilePermissions(deniedDir,Set.of());denied(deniedDir,root.resolve("unreadable-dest"),"LEGACY_DIRECTORY_READ_FAILED");}
    finally {Files.setPosixFilePermissions(deniedDir,permissions);}
    System.out.println("PASS "+checks+" synthetic legacy file assertions; no user project path used");
  }
}
