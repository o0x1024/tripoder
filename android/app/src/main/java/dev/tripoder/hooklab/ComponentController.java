package dev.tripoder.hooklab;

import android.app.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.WeakHashMap;

/** Dispatches input only into the owned Activity window; never requests system input injection. */
final class ComponentController {
    private static final WeakHashMap<Activity, Handler> jobs = new WeakHashMap<>();
    private static final String RECIPE = "[{\"type\":\"tap\",\"viewId\":\"action\",\"delayMs\":100},{\"type\":\"text\",\"viewId\":\"input\",\"text\":\"Tripoder automation\",\"delayMs\":100}]";
    private static String projectId(Activity activity){GuestRuntime runtime=GuestService.peekRuntime();return runtime==null?ProjectStore.id(activity):runtime.projectId();}
    private static String inputValue(Activity activity,String name,String initialValue){
        try {
            JSONObject config = new JSONObject(new ProjectStore(activity).read(projectId(activity), "config/input.json", "{}"));
            if (!config.has(name)) return initialValue;
            Object value = config.get(name);
            if (!(value instanceof String)) throw new IllegalArgumentException(name + " 必须是 JSON 文本字符串");
            return (String) value;
        } catch (Exception error) {
            throw new IllegalStateException("读取 config/input.json 失败：" + error.getMessage(), error);
        }
    }
    private static void saveInput(Activity activity,String name,String value){
        try{ProjectStore store=new ProjectStore(activity);String id=projectId(activity);JSONObject json=new JSONObject(store.read(id,"config/input.json","{}"));json.put(name,value);store.write(id,"config/input.json",json.toString(2));}catch(Exception error){throw new IllegalStateException("项目输入配置保存失败",error);}
    }
    static void install(Activity activity, GuestRuntime runtime) {
        if (jobs.containsKey(activity)) return;
        Handler handler = new Handler(Looper.getMainLooper()); jobs.put(activity, handler);
        Window.Callback original = activity.getWindow().getCallback();
        Window.Callback callback = (Window.Callback) Proxy.newProxyInstance(Window.Callback.class.getClassLoader(), new Class<?>[]{Window.Callback.class}, (proxy, method, args) -> {
            if (method.getName().equals("dispatchKeyEvent") && args != null && args[0] instanceof KeyEvent) {
                KeyEvent event = (KeyEvent) args[0];
                if ((event.getSource() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) {
                    try {
                        JSONObject map = new JSONObject(inputValue(activity,"buttons", "{\"96\":\"action\"}"));
                        String key = Integer.toString(event.getKeyCode());
                        Object mapped = map.has(key) ? map.get(key) : "";
                        if (!(mapped instanceof String)) throw new IllegalArgumentException("手柄映射目标必须是控件资源名");
                        String id = (String) mapped;
                        if (!id.isEmpty()) {
                            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) tap(activity, find(activity, id));
                            return true;
                        }
                    } catch (Exception error) {
                        handler.removeCallbacksAndMessages(null);
                        android.util.Log.e("Tripoder", "Gamepad mapping failed", error);
                        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                            Toast.makeText(activity, "手柄映射未执行：" + error.getMessage(), Toast.LENGTH_LONG).show();
                        // Reject this event; do not run a default mapping or forward it to the guest.
                        return true;
                    }
                }
            }
            try { return method.invoke(original, args); } catch (InvocationTargetException error) { throw error.getCause(); }
        });
        activity.getWindow().setCallback(callback);
        ImageButton tools=new ImageButton(activity);tools.setImageDrawable(new ProductUi.Icon("brand",ProductUi.WHITE));tools.setContentDescription("打开 Tripoder 工具面板");
        tools.setBackground(ProductUi.shape(ProductUi.ACCENT,18,activity));int padding=ProductUi.dp(activity,13);tools.setPadding(padding,padding,padding,padding);tools.setElevation(ProductUi.dp(activity,6));
        FrameLayout.LayoutParams layout=new FrameLayout.LayoutParams(ProductUi.dp(activity,50),ProductUi.dp(activity,50),Gravity.BOTTOM|Gravity.RIGHT);
        layout.setMargins(0,0,ProductUi.dp(activity,16),ProductUi.dp(activity,100));((FrameLayout)activity.findViewById(android.R.id.content)).addView(tools,layout);
        tools.setOnClickListener(v->panel(activity,handler));
        String requested=activity.getIntent().getStringExtra("tool");activity.getIntent().removeExtra("tool");
        if(requested!=null)handler.postDelayed(()->{if(requested.equals("automation"))automation(activity,handler);else if(requested.equals("gamepad"))gamepad(activity);},400);
        if (activity.getIntent().getBooleanExtra("componentTest", false)) {
            int seconds = Math.max(1, Math.min(60, activity.getIntent().getIntExtra("autoDelaySeconds", 1)));
            handler.postDelayed(() -> selfTest(activity, runtime, handler), seconds * 1000L);
        }
    }
    private static void panel(Activity activity,Handler handler){
        LinearLayout content=ProductUi.card(activity);ProductUi.add(content,ProductUi.text(activity,"Tripoder",22,ProductUi.INK,true),0);
        ProductUi.add(content,ProductUi.text(activity,new ProjectStore(activity).title(projectId(activity))+" · 项目工具",12,ProductUi.MUTED,false),8);
        Dialog dialog=sheet(activity,content);
        content.addView(ProductUi.entry(activity,"code","项目工作区","Agent、脚本、测试与文件",()->{dialog.dismiss();activity.startActivity(ProjectStore.intent(activity,ProjectActivity.class,projectId(activity)));}));
        content.addView(ProductUi.entry(activity,"play","交互自动化","点击与文本输入流程",()->{dialog.dismiss();automation(activity,handler);}));
        content.addView(ProductUi.entry(activity,"gamepad","手柄映射","按键与应用控件绑定",()->{dialog.dismiss();gamepad(activity);}));
        content.addView(ProductUi.entry(activity,"file","文件与环境","当前客体的文件和设备字段",()->{dialog.dismiss();activity.startActivity(ProjectStore.intent(activity,GuestToolsActivity.class,projectId(activity)));}));
        ProductUi.add(content,ProductUi.button(activity,"收起面板",false,dialog::dismiss),12);
    }
    private static Dialog sheet(Activity activity,LinearLayout content){
        Dialog dialog=new Dialog(activity);ScrollView scroll=new ScrollView(activity);scroll.addView(content);dialog.setContentView(scroll);
        Window w=dialog.getWindow();if(w!=null){w.setBackgroundDrawableResource(android.R.color.transparent);w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);}
        dialog.show();if(w!=null){w.setLayout(-1,-2);w.setGravity(Gravity.BOTTOM);}
        return dialog;
    }
    private static void automation(Activity activity,Handler handler){
        LinearLayout content=ProductUi.card(activity);ProductUi.add(content,ProductUi.text(activity,"交互自动化",22,ProductUi.INK,true),0);
        ProductUi.add(content,ProductUi.text(activity,"仅在当前应用窗口执行。离开窗口后流程会停止。",12,ProductUi.MUTED,false),9);
        EditText target=ProductUi.field(content,"点击的控件","资源名，例如 action","action",false);
        EditText input=ProductUi.field(content,"输入的控件","资源名，例如 input","input",false);
        EditText value=ProductUi.field(content,"输入内容","要自动填写的文字","Tripoder automation",false);
        Dialog dialog=sheet(activity,content);
        ProductUi.add(content,ProductUi.button(activity,"保存并运行这两步",true,()->{
            try{JSONArray recipe=new JSONArray().put(new JSONObject().put("type","tap").put("viewId",target.getText().toString()).put("delayMs",300))
                .put(new JSONObject().put("type","text").put("viewId",input.getText().toString()).put("text",value.getText().toString()).put("delayMs",300));
                JSONArray valid=validateRecipe(recipe.toString());validateTargets(activity,valid);
                saveInput(activity,"recipe",recipe.toString());dialog.dismiss();
                runRecipe(activity,valid,handler,()->Toast.makeText(activity,"自动化已完成",Toast.LENGTH_SHORT).show());
            }catch(Exception error){value.setError(error.getMessage());}
        }),16);
        ProductUi.add(content,ProductUi.button(activity,"运行已保存的流程",false,()->{
            try{JSONArray recipe=validateRecipe(inputValue(activity,"recipe",RECIPE));validateTargets(activity,recipe);dialog.dismiss();runRecipe(activity,recipe,handler,()->Toast.makeText(activity,"自动化已完成",Toast.LENGTH_SHORT).show());}
            catch(Exception error){value.setError(error.getMessage());}
        }),10);
        ProductUi.pair(content,ProductUi.button(activity,"停止流程",false,()->{handler.removeCallbacksAndMessages(null);Toast.makeText(activity,"流程已停止",Toast.LENGTH_SHORT).show();dialog.dismiss();}),ProductUi.button(activity,"高级 JSON",false,()->{
            try {
                String saved = inputValue(activity,"recipe",RECIPE);
                dialog.dismiss();edit(activity,"自动化流程 JSON",saved,json->{JSONArray recipe=validateRecipe(json);validateTargets(activity,recipe);saveInput(activity,"recipe",json);runRecipe(activity,recipe,handler,()->Toast.makeText(activity,"自动化已完成",Toast.LENGTH_SHORT).show());});
            } catch (Exception error) { value.setError(error.getMessage()); }
        }));
        ProductUi.add(content,ProductUi.button(activity,"关闭",false,dialog::dismiss),10);
    }
    private static void gamepad(Activity activity){
        LinearLayout content=ProductUi.card(activity);ProductUi.add(content,ProductUi.text(activity,"手柄映射",22,ProductUi.INK,true),0);
        ProductUi.add(content,ProductUi.text(activity,"按键触发当前应用内的控件点击。支持系统识别的游戏手柄按键。",12,ProductUi.MUTED,false),10);
        Spinner keys=new Spinner(activity);String[] labels={"A","B","X","Y","L1","R1","START","SELECT"};int[] codes={96,97,99,100,102,103,108,109};
        keys.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,labels));ProductUi.add(content,keys,14);
        EditText target=ProductUi.field(content,"目标控件","资源名，例如 action","action",false);
        TextView current=ProductUi.text(activity,"",13,ProductUi.MUTED,false);ProductUi.add(content,current,14);
        Runnable refresh=()->{try{JSONObject map=new JSONObject(inputValue(activity,"buttons","{\"96\":\"action\"}"));StringBuilder text=new StringBuilder("已保存的映射");java.util.Iterator<String> names=map.keys();while(names.hasNext()){String code=names.next();text.append("\n").append(KeyEvent.keyCodeToString(Integer.parseInt(code)).replace("KEYCODE_BUTTON_","")).append(" → ").append(map.getString(code));}current.setText(text);String mapped=map.optString(Integer.toString(codes[keys.getSelectedItemPosition()]),"");target.setText(mapped);}catch(Exception error){current.setText(error.getMessage());}};
        keys.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int pos,long id){refresh.run();}public void onNothingSelected(AdapterView<?> p){}});
        Dialog dialog=sheet(activity,content);
        ProductUi.add(content,ProductUi.button(activity,"保存映射",true,()->{try{find(activity,target.getText().toString());JSONObject map=new JSONObject(inputValue(activity,"buttons","{\"96\":\"action\"}"));map.put(Integer.toString(codes[keys.getSelectedItemPosition()]),target.getText().toString());saveInput(activity,"buttons",map.toString());refresh.run();Toast.makeText(activity,"映射已保存",Toast.LENGTH_SHORT).show();}catch(Exception error){target.setError(error.getMessage());}}),16);
        ProductUi.pair(content,ProductUi.button(activity,"移除此按键",false,()->{try{JSONObject map=new JSONObject(inputValue(activity,"buttons","{}"));map.remove(Integer.toString(codes[keys.getSelectedItemPosition()]));saveInput(activity,"buttons",map.toString());refresh.run();}catch(Exception error){target.setError(error.getMessage());}}),ProductUi.button(activity,"高级 JSON",false,()->{
            try {
                String saved = inputValue(activity,"buttons","{\"96\":\"action\"}");
                dialog.dismiss();edit(activity,"手柄键码 → 控件资源名",saved,json->{JSONObject map=new JSONObject(json);if(map.length()>32)throw new IllegalArgumentException("最多 32 个按键");java.util.Iterator<String> names=map.keys();while(names.hasNext()){String code=names.next();Integer.parseInt(code);find(activity,map.getString(code));}saveInput(activity,"buttons",json);});
            } catch (Exception error) { target.setError(error.getMessage()); }
        }));ProductUi.add(content,ProductUi.button(activity,"完成",false,dialog::dismiss),10);refresh.run();
    }
    interface EditAction { void run(String value) throws Exception; }
    private static void edit(Activity activity, String title, String initial, EditAction action) {
        LinearLayout content=ProductUi.card(activity);EditText input=ProductUi.field(content,"JSON","",initial,true);input.setMinLines(4);input.setMaxLines(12);input.setTypeface(android.graphics.Typeface.MONOSPACE);input.setTextSize(12);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(title).setView(content).setNegativeButton("取消",null).setPositiveButton("应用",null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            try { action.run(input.getText().toString()); dialog.dismiss(); } catch(Exception error) { input.setError(error.getMessage()); }
        })); dialog.show();
    }
    static void cancel(Activity activity) { Handler handler = jobs.get(activity); if (handler != null) handler.removeCallbacksAndMessages(null); }
    private static View find(Activity activity, String name) {
        View view = findNamed(activity.getWindow().getDecorView(), GuestStore.PACKAGE + ":id/" + name);
        if (view == null) throw new IllegalArgumentException("客体视图不存在: " + name); return view;
    }
    private static View findNamed(View root, String resourceName) {
        if (root.getId() != View.NO_ID) {
            try { if (resourceName.equals(root.getResources().getResourceName(root.getId()))) return root; }
            catch (android.content.res.Resources.NotFoundException ignored) { }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) { View result = findNamed(group.getChildAt(i), resourceName); if (result != null) return result; }
        }
        return null;
    }
    private static void tap(Activity activity, View target) {
        int[] location = new int[2], origin = new int[2]; target.getLocationOnScreen(location); activity.getWindow().getDecorView().getLocationOnScreen(origin);
        float x = location[0] - origin[0] + target.getWidth()/2f, y = location[1] - origin[1] + target.getHeight()/2f;
        long time = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,x,y,0), up = MotionEvent.obtain(time,time+20,MotionEvent.ACTION_UP,x,y,0);
        try { activity.dispatchTouchEvent(down); activity.dispatchTouchEvent(up); } finally { down.recycle(); up.recycle(); }
    }
    static JSONArray validateRecipe(String value) throws Exception {
        if (value.length() > 16384) throw new IllegalArgumentException("自动化文本过长");
        JSONArray array = new JSONArray(value); if (array.length() == 0 || array.length() > 64) throw new IllegalArgumentException("需要 1–64 个步骤");
        int total = 0;
        for(int i=0;i<array.length();i++) {
            JSONObject step=array.getJSONObject(i); String type=step.getString("type");
            if (!type.equals("tap") && !type.equals("text")) throw new IllegalArgumentException("只支持 tap / text");
            if (!step.getString("viewId").matches("[a-zA-Z0-9_]{1,80}")) throw new IllegalArgumentException("视图名无效");
            int delay=step.optInt("delayMs",100); total+=delay;
            if(delay<0 || delay>5000 || total>30000 || step.optString("text","").length()>4096) throw new IllegalArgumentException("步骤超过范围");
        } return array;
    }
    private static void validateTargets(Activity activity,JSONArray recipe) throws Exception {
        for (int i=0;i<recipe.length();i++) {
            JSONObject step=recipe.getJSONObject(i); View view=find(activity,step.getString("viewId"));
            if(step.getString("type").equals("text") && !(view instanceof EditText)) throw new IllegalArgumentException("text 需要 EditText 目标");
        }
    }
    private static void runRecipe(Activity activity, JSONArray recipe, Handler handler, Runnable done) throws Exception {
        validateTargets(activity,recipe);
        handler.removeCallbacksAndMessages(null); int elapsed=0;
        for(int i=0;i<recipe.length();i++) {
            JSONObject step=recipe.getJSONObject(i); elapsed+=step.optInt("delayMs",100);
            handler.postDelayed(() -> {
                try { View view=find(activity,step.getString("viewId")); if(step.getString("type").equals("tap")) tap(activity,view); else ((EditText)view).setText(step.getString("text")); }
                catch(Exception error) { handler.removeCallbacksAndMessages(null); Toast.makeText(activity,"自动化失败: "+error.getMessage(),Toast.LENGTH_LONG).show(); }
            },elapsed);
        }
        handler.postDelayed(done,elapsed+50);
    }
    private static int field(Activity activity, String name) throws Exception { return activity.getClass().getField(name).getInt(null); }
    private static void check(JSONArray checks, String name, boolean passed, Object actual) throws Exception {
        checks.put(new JSONObject().put("name",name).put("passed",passed).put("actual",actual)); if(!passed) throw new IllegalStateException(name+": "+actual);
    }
    private static void selfTest(Activity activity, GuestRuntime runtime, Handler handler) {
        JSONObject report=new JSONObject(); JSONArray checks=new JSONArray();
        try {
            report.put("startedAtMs",System.currentTimeMillis()).put("pid",android.os.Process.myPid()).put("uid",android.os.Process.myUid())
                .put("hostPid",activity.getIntent().getIntExtra("hostPid",0)).put("debuggerConnected",Debug.isDebuggerConnected())
                .put("debuggable",(activity.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
                .put("hostApkSha256",GuestStore.sha256(new File(activity.getApplicationInfo().sourceDir))).put("guestApkSha256",runtime.guestHash()).put("checks",checks);
            check(checks,"real-guest-activity-class",activity.getClass().getName().equals("dev.tripoder.fixture.FixtureActivity"),activity.getClass().getName());
            check(checks,"component-classloader",activity.getClass().getClassLoader()==runtime.componentLoader(),true);
            check(checks,"guest-application-instance",activity.getApplication().getClass().getName().equals("dev.tripoder.fixture.FixtureApplication"),activity.getApplication().getClass().getName());
            check(checks,"guest-application-onCreate",activity.getApplication().getClass().getField("created").getInt(null)==1,true);
            int titleId = activity.getClass().getClassLoader().loadClass(GuestStore.PACKAGE + ".R$string").getField("guest_title").getInt(null);
            String title = activity.getResources().getString(titleId);
            check(checks,"apk-string-and-layout-resources",find(activity,"action") instanceof Button && title.equals("来自独立 APK 的真实界面"),title);
            check(checks,"application-private-data",runtime.files("read","application-created.txt","").getString("content").equals("来自独立 APK 的真实界面"),true);
            check(checks,"activity-lifecycle-resumed",field(activity,"creates")>=1 && field(activity,"resumes")>=1,field(activity,"resumes"));
            check(checks,"opengl-frame-rendered",field(activity,"frames")>0,field(activity,"frames"));
            check(checks,"opengl-readback",field(activity,"glError")==0 && field(activity,"pixelGreen")>150 && field(activity,"pixelGreen")<180,field(activity,"pixelGreen"));
            report.put("glVersion",activity.getClass().getField("glVersion").get(null));
            inputTests(activity, runtime, handler, report, checks);

        } catch(Exception error) {
            try { report.put("passed",false).put("error",android.util.Log.getStackTraceString(error)); } catch(Exception ignored){} finish(activity,runtime,report);
        }
    }
    interface CheckedStep { void run() throws Exception; }
    private static void inputTests(Activity activity, GuestRuntime runtime, Handler handler, JSONObject report, JSONArray checks) throws Exception {
        int before = field(activity,"clicks");
        String savedMap = inputValue(activity,"buttons","{\"96\":\"action\"}"), model = android.os.Build.MODEL;
        Runnable restore = () -> {
            saveInput(activity,"buttons",savedMap);
            try { runtime.environment("{}"); } catch(Exception ignored) { }
        };
        CheckedStep[] steps = new CheckedStep[]{
            () -> tap(activity,find(activity,"action")),
            () -> {
                check(checks,"local-touch-dispatch",field(activity,"clicks")==before+1,field(activity,"clicks"));
                saveInput(activity,"buttons","{\"96\":\"action\"}");
                long now=SystemClock.uptimeMillis();
                activity.getWindow().getCallback().dispatchKeyEvent(new KeyEvent(now,now,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_A,0,0,-1,0,0,InputDevice.SOURCE_GAMEPAD));
            },
            () -> {
                check(checks,"synthetic-gamepad-key-mapping",field(activity,"clicks")==before+2,field(activity,"clicks"));
                saveInput(activity,"buttons",savedMap);
                runtime.command("reset",null); runtime.command("update",new JSONObject().put("message","component:").put("delayMs",400)); runtime.command("enable",null);
                tap(activity,find(activity,"action"));
            },
            () -> {
                String displayed=((TextView)find(activity,"result")).getText().toString();
                check(checks,"activity-java-native-hook",displayed.contains("component:phone") && displayed.contains("Native: 403"),displayed);
                runtime.environment("{\"MODEL\":\"Tripoder Profile\"}"); tap(activity,find(activity,"action"));
            },
            () -> {
                check(checks,"guest-java-Build-profile",((TextView)find(activity,"result")).getText().toString().contains("Tripoder Profile"),android.os.Build.MODEL);
                runtime.environment("{}"); check(checks,"environment-restored",model.equals(android.os.Build.MODEL),android.os.Build.MODEL);
                runtime.files("write","editor-test.txt","edited in guest data");
                check(checks,"file-edit-readback",runtime.files("read","editor-test.txt","").getString("content").equals("edited in guest data"),true);
                boolean denied=false; try { runtime.files("write","../escape.txt","bad"); } catch(IOException expected) { denied=true; }
                check(checks,"file-traversal-rejected",denied,denied);
                File link=new File(runtime.componentData(),"test-symlink");
                try {
                    Files.createSymbolicLink(link.toPath(),runtime.componentData().getParentFile().toPath()); denied=false;
                    try { runtime.files("read","test-symlink/base.apk",""); } catch(IOException expected) { denied=true; }
                    check(checks,"file-symlink-rejected",denied,denied);
                } finally { Files.deleteIfExists(link.toPath()); }
                runtime.command("disable",null); int clicks=field(activity,"clicks");
                runRecipe(activity,validateRecipe(RECIPE),handler,() -> {
                    try {
                        check(checks,"automation-tap-and-text",field(activity,"clicks")==clicks+1 && ((EditText)find(activity,"input")).getText().toString().equals("Tripoder automation"),field(activity,"clicks"));
                        report.put("passed",true).put("physicalGamepadVerified",false);
                    } catch(Exception error) { try { report.put("passed",false).put("error",error.toString()); } catch(Exception ignored){} }
                    restore.run(); finish(activity,runtime,report);
                });
            }
        };
        // ACTION_UP posts View.performClick; each subsequent check must run after that callback.
        class Sequence implements Runnable {
            int index;
            public void run() {
                try { steps[index++].run(); if(index < steps.length) handler.post(this); }
                catch(Exception error) {
                    restore.run();
                    try { report.put("passed",false).put("error",android.util.Log.getStackTraceString(error)); } catch(Exception ignored) { }
                    finish(activity,runtime,report);
                }
            }
        }
        handler.post(new Sequence());
    }
    private static void finish(Activity activity, GuestRuntime runtime, JSONObject report) {
        try {
            report.put("finalAgent",runtime.command("reset",null)).put("finishedAtMs",System.currentTimeMillis());
            try(FileOutputStream out=new FileOutputStream(new File(activity.getExternalFilesDir(null),"component-result.json"))) { out.write(report.toString(2).getBytes(StandardCharsets.UTF_8)); }
            new AlertDialog.Builder(activity).setTitle((report.getBoolean("passed")?"PASS":"FAIL")+" · "+report.getJSONArray("checks").length()+" 项组件检查")
                .setMessage(report.optString("error","真实 Activity、资源、OpenGL、Hook、文件、设备字段、自动化与按键映射已验证。实体手柄尚未连接验收。"))
                .setPositiveButton("关闭",null).show();
        } catch(Exception error) { Toast.makeText(activity,error.toString(),Toast.LENGTH_LONG).show(); }
    }
}
