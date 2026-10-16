package dev.tripoder.runtime;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

/** Absolute database paths use the same four owned roots as the native storage policy. */
final class RuntimeDatabasePath {
    static File resolve(File root,File canonicalRoot,String packageName,String name){
        if(name==null||name.indexOf('\0')>=0)throw new IllegalArgumentException("INVALID_DATABASE_PATH");
        Path requested=Path.of(name);
        if(!requested.isAbsolute()||!requested.normalize().equals(requested))throw new SecurityException("DATABASE_PATH_NOT_NORMALIZED");
        for(Path prefix:List.of(root.toPath(),canonicalRoot.toPath(),Path.of("/data/data",packageName),Path.of("/data/user/0",packageName))){
            if(requested.startsWith(prefix)&&requested.getNameCount()>prefix.getNameCount()){
                File selected=root.toPath().resolve(prefix.relativize(requested)).toFile();
                RuntimeStorage.name(selected.getName());return selected;
            }
        }
        throw new SecurityException("DATABASE_PATH_OUTSIDE_INSTALL");
    }
    private RuntimeDatabasePath(){}
}
