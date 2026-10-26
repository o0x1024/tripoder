package dev.tripoder.selfhook;

import android.content.pm.SharedLibraryInfo;
import java.lang.reflect.Method;
import java.util.List;

/** Exact fixed boot members for the physical PM's unversioned platform libraries. */
public final class SharedLibraryFrameworkAccess {
    private static Method paths,dependencies,nativeType;
    private static java.lang.reflect.Field applicationLibraries;
    private static synchronized void prepare(){
        if(paths!=null)return;
        try{Method p=FixedFrameworkMembers.method(62,"getAllCodePaths","()Ljava/util/List;",false),d=FixedFrameworkMembers.method(62,"getDependencies","()Ljava/util/List;",false);nativeType=FixedFrameworkMembers.method(62,"isNative","()Z",false);applicationLibraries=FixedFrameworkMembers.field(53,4,"sharedLibraryInfos","Ljava/util/List;",false);paths=p;dependencies=d;}
        catch(Exception error){throw new IllegalStateException("SHARED_LIBRARY_MEMBERS_UNAVAILABLE",error);}
    }
    private static void require(SharedLibraryInfo info){if(info==null||(info.getType()!=SharedLibraryInfo.TYPE_BUILTIN&&info.getType()!=SharedLibraryInfo.TYPE_DYNAMIC)||info.getLongVersion()!=-1)throw new SecurityException("SHARED_LIBRARY_PROFILE_UNSUPPORTED");prepare();}
    @SuppressWarnings("unchecked") public static List<String> paths(SharedLibraryInfo info){require(info);List<String> values=(List<String>)FixedFrameworkMembers.call(paths,info);return values==null?List.of():List.copyOf(values);}
    @SuppressWarnings("unchecked") public static List<SharedLibraryInfo> dependencies(SharedLibraryInfo info){require(info);List<SharedLibraryInfo> result=(List<SharedLibraryInfo>)FixedFrameworkMembers.call(dependencies,info);return result==null?List.of():List.copyOf(result);}
    public static boolean isNative(SharedLibraryInfo info){require(info);return (Boolean)FixedFrameworkMembers.call(nativeType,info);}
    public static void project(android.content.pm.ApplicationInfo info,List<SharedLibraryInfo> libraries){prepare();FixedFrameworkMembers.set(applicationLibraries,java.util.Objects.requireNonNull(info),new java.util.ArrayList<>(libraries));}
    private SharedLibraryFrameworkAccess(){}
}
