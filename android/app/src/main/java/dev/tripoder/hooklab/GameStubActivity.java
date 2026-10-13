package dev.tripoder.hooklab;

import android.app.Activity;
import android.os.Bundle;

/** OS-visible slot; the component factory constructs the original game Activity. */
public final class GameStubActivity extends Activity {
    @Override public void onCreate(Bundle state){super.onCreate(state);ProductUi.notice(this,"游戏进程需要重新准备","请返回项目，再次启动游戏。");}
}
