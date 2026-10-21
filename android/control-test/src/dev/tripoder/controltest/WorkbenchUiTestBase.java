package dev.tripoder.controltest;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.view.accessibility.AccessibilityNodeInfo;
import dev.tripoder.control.ControlWire;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Shared native UI actions for workbench acceptance. */
abstract class WorkbenchUiTestBase extends ControlTestBase {
    /** Test operations get their own durable journal; existing user pending records are untouched. */
    protected void isolateOperationJournal()throws Exception{
        isolateOperationJournal(this);
    }
    static void isolateOperationJournal(ControlTestBase test)throws Exception{
        Object app=test.getTargetContext().getApplicationContext();long deadline=SystemClock.elapsedRealtime()+5000;
        while(field(app,"agents")==null){if(SystemClock.elapsedRealtime()>deadline)throw new IllegalStateException("APPLICATION_INITIALIZATION_TIMEOUT");SystemClock.sleep(10);}
        Object old=field(app,"operations");
        String pending=(String)invoke(old,"pendingId",new Class<?>[0]);test.report.put("existingUserPendingOperation",pending).put("testOperationJournal","ui-acceptance-"+test.run);
        Context isolated=new ContextWrapper(test.getTargetContext()){
            @Override public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences(name.equals("operations")?"ui-acceptance-"+test.run:name,mode);}
        };
        Class<?> connection=Class.forName("dev.tripoder.hooklab.EngineConnection"),operations=Class.forName("dev.tripoder.hooklab.WorkbenchOperations"),imports=Class.forName("dev.tripoder.hooklab.WorkbenchImports");
        var operationConstructor=operations.getDeclaredConstructor(Context.class,connection);operationConstructor.setAccessible(true);Object journal=operationConstructor.newInstance(isolated,field(app,"engine"));
        var importConstructor=imports.getDeclaredConstructor(Context.class,operations);importConstructor.setAccessible(true);Object importer=importConstructor.newInstance(isolated,journal);
        test.runOnMainSync(()->{try{Field operationField=app.getClass().getDeclaredField("operations"),importField=app.getClass().getDeclaredField("imports");operationField.setAccessible(true);importField.setAccessible(true);operationField.set(app,journal);importField.set(app,importer);}catch(Exception error){throw new IllegalStateException(error);}});
    }
    protected static AccessibilityNodeInfo findApp(AccessibilityNodeInfo node,String prefix){if(node==null)return null;if(node.getContentDescription()!=null&&node.getContentDescription().toString().startsWith(prefix))return node;for(int i=0;i<node.getChildCount();i++){var child=findApp(node.getChild(i),prefix);if(child!=null)return child;}return null;}
    protected static int countApp(AccessibilityNodeInfo node,String prefix){if(node==null)return 0;int count=node.getContentDescription()!=null&&node.getContentDescription().toString().startsWith(prefix)?1:0;for(int i=0;i<node.getChildCount();i++)count+=countApp(node.getChild(i),prefix);return count;}
    /** Application tiles are announced by name; the package travels as an accessibility extra. */
    protected static AccessibilityNodeInfo findPackage(AccessibilityNodeInfo node,String pkg){if(node==null)return null;if(pkg.equals(node.getExtras().getString(PACKAGE_EXTRA)))return node;for(int i=0;i<node.getChildCount();i++){var child=findPackage(node.getChild(i),pkg);if(child!=null)return child;}return null;}
    protected static int countPackage(AccessibilityNodeInfo node,String pkg){if(node==null)return 0;int count=pkg.equals(node.getExtras().getString(PACKAGE_EXTRA))?1:0;for(int i=0;i<node.getChildCount();i++)count+=countPackage(node.getChild(i),pkg);return count;}
    /** First enabled tile of any package, for pickers whose contents depend on the phone. */
    protected static AccessibilityNodeInfo findAnyPackage(AccessibilityNodeInfo node){if(node==null)return null;if(node.getExtras().getString(PACKAGE_EXTRA)!=null&&node.isEnabled())return node;for(int i=0;i<node.getChildCount();i++){var child=findAnyPackage(node.getChild(i));if(child!=null)return child;}return null;}
    protected static AccessibilityNodeInfo findSuffix(AccessibilityNodeInfo node,String suffix){if(node==null)return null;if(node.getText()!=null&&node.getText().toString().endsWith(suffix))return node;for(int i=0;i<node.getChildCount();i++){var child=findSuffix(node.getChild(i),suffix);if(child!=null)return child;}return null;}
    protected static AccessibilityNodeInfo findPrefix(AccessibilityNodeInfo node,String prefix){if(node==null)return null;if(node.getText()!=null&&node.getText().toString().startsWith(prefix))return node;for(int i=0;i<node.getChildCount();i++){var child=findPrefix(node.getChild(i),prefix);if(child!=null)return child;}return null;}
    static final String PACKAGE_EXTRA="dev.tripoder.package";
    protected void clickDescription(String description)throws Exception{long deadline=SystemClock.elapsedRealtime()+5000;do{var node=waitNode(description,true);if(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))return;SystemClock.sleep(60);}while(SystemClock.elapsedRealtime()<deadline);throw new IllegalStateException("DESCRIPTION_CLICK_FAILED:"+description);}
    protected void clickBack()throws Exception{clickDescription("返回");SystemClock.sleep(400);}
    protected AccessibilityNodeInfo waitNode(String text,boolean description)throws Exception{long end=SystemClock.elapsedRealtime()+10000;do{AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow(),match=find(root,text,description);if(match!=null)return match;SystemClock.sleep(50);}while(SystemClock.elapsedRealtime()<end);var current=getUiAutomation().getRootInActiveWindow();throw new IllegalStateException("UI_TARGET_NOT_FOUND:"+text+":window="+(current==null?"null":current.getPackageName()));}
    protected static AccessibilityNodeInfo find(AccessibilityNodeInfo node,String value,boolean description){if(node==null)return null;CharSequence text=description?node.getContentDescription():node.getText();if(text!=null&&value.contentEquals(text))return node;for(int i=0;i<node.getChildCount();i++){var found=find(node.getChild(i),value,description);if(found!=null)return found;}return null;}
    protected void click(String text)throws Exception{long deadline=SystemClock.elapsedRealtime()+5000;do{AccessibilityNodeInfo node=waitNode(text,false);for(int i=0;i<4&&node!=null;i++,node=node.getParent())if(node.isClickable()){if(!node.isEnabled())throw new IllegalStateException("UI_CLICK_REJECTED:"+text);if(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))return;break;}SystemClock.sleep(60);}while(SystemClock.elapsedRealtime()<deadline);throw new IllegalStateException("UI_TARGET_NOT_CLICKABLE:"+text);}
    protected void scrollTo(String text)throws Exception{for(int i=0;i<12;i++){var node=find(getUiAutomation().getRootInActiveWindow(),text,false);if(node!=null&&node.isVisibleToUser())return;scroll(true);SystemClock.sleep(180);}throw new IllegalStateException("UI_SCROLL_TARGET_NOT_FOUND:"+text);}
    protected void scrollTop(){for(int i=0;i<12;i++){if(!scroll(false))break;SystemClock.sleep(50);}}
    protected boolean scroll(boolean forward){var root=getUiAutomation().getRootInActiveWindow();return scrollNode(root,forward);}
    protected boolean scrollNode(AccessibilityNodeInfo node,boolean forward){if(node==null)return false;if(node.isScrollable()&&node.performAction(forward?AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))return true;for(int i=0;i<node.getChildCount();i++)if(scrollNode(node.getChild(i),forward))return true;return false;}
    protected void capture(String label)throws Exception{getUiAutomation().waitForIdle(300,3000);SystemClock.sleep(650);var root=getUiAutomation().getRootInActiveWindow();String pkg=root==null?"":String.valueOf(root.getPackageName());if(!pkg.equals(enginePackage()))throw new IOException("UI_SCREENSHOT_NOT_OWNED:"+pkg);if(!label.matches("[a-z-]+"))throw new IOException("UI_SCREENSHOT_LABEL_INVALID");String name=run+"-"+label+".png";File file=new File(getTargetContext().getExternalFilesDir(null),name);
        try(var descriptor=getUiAutomation().executeShellCommand("screencap -p "+file.getAbsolutePath());var input=new ParcelFileDescriptor.AutoCloseInputStream(descriptor)){input.readAllBytes();}
        if(!file.isFile()||file.length()<8)throw new IOException("UI_SCREENSHOT_UNAVAILABLE");try(var input=new FileInputStream(file)){if(!java.util.Arrays.equals(input.readNBytes(8),new byte[]{(byte)137,80,78,71,13,10,26,10}))throw new IOException("UI_SCREENSHOT_INVALID");}
        JSONArray captures=report.optJSONArray("captures");if(captures==null){captures=new JSONArray();report.put("captures",captures);}captures.put(name);}
    protected static Object field(Object object,String name)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    protected static Object invoke(Object object,String name,Class<?>[] types,Object...args)throws Exception{Method method=object.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(object,args);}
    protected static String id(){return UUID.randomUUID().toString();}
}
