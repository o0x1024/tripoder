package dev.tripoder.providerpeer;

import android.app.Activity;
import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import org.json.JSONObject;

/** Two separately authenticated guest packages; no native install or real app data. */
public final class Main extends Activity {
    static final String TARGET="dev.tripoder.closurefixture";
    static final Uri OPEN=Uri.parse("content://"+TARGET+".peer-open/owned");
    private static boolean contains(Throwable error,String code){for(Throwable value=error;value!=null;value=value.getCause())if(value.getMessage()!=null&&value.getMessage().contains(code))return true;return false;}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);TextView view=new TextView(this);view.setText("RUNNING_OWNED_PEER_CHECKS");setContentView(view);
        new Thread(()->{
            JSONObject checks=new JSONObject(),report=new JSONObject();
            try{
                boolean denied=false;try(ContentProviderClient ignored=getContentResolver().acquireContentProviderClient(Uri.parse("content://"+TARGET+".documents"))){}
                catch(Exception error){denied=contains(error,"PROVIDER_NOT_EXPORTED");}
                checks.put("real-peer-private-acquisition-denied",denied);
                try(Cursor cursor=getContentResolver().query(OPEN,null,null,null,null)){
                    checks.put("real-peer-open-query-and-cursor-use-authenticated-caller",cursor!=null&&cursor.moveToFirst()&&getPackageName().equals(cursor.getString(0))&&"OWNED_OPEN_QUERY".equals(cursor.getString(1)));
                    checks.put("real-peer-cursor-forces-remote-requery",cursor!=null&&cursor.requery());
                }
                denied=false;try(Cursor ignored=getContentResolver().query(Uri.parse("content://"+TARGET+".peer-protected/owned"),null,null,null,null)){}
                catch(Exception error){denied=contains(error,"PROVIDER_PERMISSION_UNSUPPORTED");}
                checks.put("real-peer-permission-protected-query-denied",denied);
                denied=false;try(Cursor ignored=getContentResolver().query(Uri.parse("content://"+TARGET+".peer-path/private/owned"),null,null,null,null)){}
                catch(Exception error){denied=contains(error,"PROVIDER_PATH_PERMISSION_UNSUPPORTED");}
                checks.put("real-peer-path-permission-protected-query-denied",denied);
                for(boolean reverse:new boolean[]{false,true}){
                    java.util.ArrayList<ContentProviderOperation> operations=new java.util.ArrayList<>();
                    Uri allowed=Uri.parse("content://"+TARGET+".peer-path/public/owned"),blocked=Uri.parse("content://"+TARGET+".peer-path/private/owned");
                    operations.add(ContentProviderOperation.newAssertQuery(reverse?blocked:allowed).withExpectedCount(1).build());
                    operations.add(ContentProviderOperation.newAssertQuery(reverse?allowed:blocked).withExpectedCount(1).build());
                    denied=false;try{getContentResolver().applyBatch(allowed.getAuthority(),operations);}catch(Exception error){denied=contains(error,"PROVIDER_PATH_PERMISSION_UNSUPPORTED");}
                    checks.put("real-peer-mixed-batch-denied-before-any-callback-"+reverse,denied);
                }
                RawTransportChecks.run(this,checks);
                Bundle status=getContentResolver().call(OPEN,"status",null,null);
                checks.put("permission-denial-has-zero-provider-query-callbacks",status!=null&&status.getInt("protectedQueries")==0);
                checks.put("path-permission-denial-has-zero-provider-query-callbacks",status!=null&&status.getInt("pathQueries")==0);
                checks.put("bad-authority-has-zero-additional-open-query-callbacks",status!=null&&status.getInt("openQueries")==1);
                checks.put("forged-identity-and-source-have-zero-provider-call-callbacks",status!=null&&status.getInt("identityCalls")==0);
                checks.put("mixed-batch-denial-has-zero-provider-batch-callbacks",status!=null&&status.getInt("batchCalls")==0);
                checks.put("real-remote-cursor-requery-retains-authenticated-caller",status!=null&&status.getInt("cursorRequeries")==1&&getPackageName().equals(status.getString("cursorCaller")));
                checks.put("real-remote-cursor-close-runs-once",status!=null&&status.getInt("cursorCloses")==1);
                boolean passed=true;for(var keys=checks.keys();keys.hasNext();)passed&=checks.getBoolean(keys.next());
                report.put("passed",passed).put("checks",checks).put("wireProtocol",PeerProtocol.VERSION);
            }catch(Exception error){try{report.put("passed",false).put("checks",checks).put("errorClass",error.getClass().getName()).put("error",String.valueOf(error.getMessage()));}catch(Exception ignored){}}
            String text=report.toString();android.util.Log.i("TripoderProviderPeer",text);runOnUiThread(()->view.setText(text));
        },"owned-provider-peer").start();
    }
}
