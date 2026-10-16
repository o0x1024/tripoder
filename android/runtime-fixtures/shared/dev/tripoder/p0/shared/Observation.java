package dev.tripoder.p0.shared;
import android.app.Activity;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

public final class Observation {
    public static JSONObject read(Activity a)throws Exception{
        ProbeApplication app=(ProbeApplication)a.getApplication();
        int title=a.getResources().getIdentifier("title","string",a.getPackageName());
        String asset;try(var in=a.getAssets().open("owned.txt")){asset=new String(in.readAllBytes(),StandardCharsets.UTF_8);}
        return new JSONObject().put("package",a.getPackageName()).put("applicationPackage",app.getPackageName()).put("applicationClass",app.getClass().getName())
            .put("activityClass",a.getClass().getName()).put("sameGuestLoader",a.getClass().getClassLoader()==app.getClass().getClassLoader())
            .put("title",a.getString(title)).put("asset",asset.trim()).put("javaFiles",a.getFilesDir().toString()).put("applicationFiles",app.getFilesDir().toString())
            .put("nativeResult",app.nativeResult).put("starts",app.starts).put("pid",android.os.Process.myPid()).put("uid",android.os.Process.myUid());
    }
}
