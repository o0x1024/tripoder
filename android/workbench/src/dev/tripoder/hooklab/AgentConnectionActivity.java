package dev.tripoder.hooklab;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import java.nio.charset.StandardCharsets;

/** Export the HTTP endpoint address through the document picker. */
public final class AgentConnectionActivity extends Activity {
    @Override public void onCreate(Bundle saved){super.onCreate(saved);if(saved==null)startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,"tripoder-connection.json"),1);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=1)return;
        try{if(result==RESULT_OK&&data!=null&&data.getData()!=null){AgentNetworkIdentity identity=((WorkbenchApplication)getApplication()).agents.networkIdentity;if(identity==null)throw new IllegalStateException("MCP_ENDPOINT_CLOSED");try(var output=getContentResolver().openOutputStream(data.getData(),"wt")){if(output==null)throw new java.io.IOException("MCP_EXPORT_FAILED");output.write(identity.connection().toString(2).getBytes(StandardCharsets.UTF_8));}}finish();}
        catch(Exception error){ProductUi.notice(this,"导出失败",UiText.error(error));}
    }
}
