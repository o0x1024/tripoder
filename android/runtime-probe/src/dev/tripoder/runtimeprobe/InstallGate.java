package dev.tripoder.runtimeprobe;

import android.os.Bundle;
import java.util.concurrent.*;

/** Acceptance-only barrier at a real install phase, with bounded failure if the test disappears. */
final class InstallGate {
    private String id,phase;private CountDownLatch release;private volatile boolean entered,expired;
    synchronized Bundle command(Bundle input)throws Exception{
        String command=input.getString("command");Bundle out=new Bundle();
        if("probeInstallGateArm".equals(command)){
            if(release!=null)throw new IllegalStateException("INSTALL_GATE_ALREADY_ARMED");
            id=input.getString("gate");phase=input.getString("phase");
            if(id==null||!id.matches("[a-zA-Z0-9-]{1,80}")||!java.util.Set.of("AFTER_COPY","AFTER_PUBLISH").contains(phase))throw new IllegalArgumentException("INSTALL_GATE_INVALID");
            release=new CountDownLatch(1);entered=false;expired=false;
        }else{
            if(release==null||!id.equals(input.getString("gate")))throw new IllegalStateException("INSTALL_GATE_MISMATCH");
            out.putBoolean("entered",entered);out.putBoolean("held",entered&&!expired&&release.getCount()==1);
            if("probeInstallGateRelease".equals(command)){release.countDown();release=null;}
            else if(!"probeInstallGateState".equals(command))throw new IllegalArgumentException("INSTALL_GATE_COMMAND");
        }return out;
    }
    void at(Bundle request,String point)throws Exception{
        if(!request.containsKey("gate"))return;CountDownLatch wait;
        synchronized(this){if(!java.util.Objects.equals(phase,point))return;if(release==null||!id.equals(request.getString("gate")))throw new IllegalStateException("INSTALL_GATE_MISMATCH");entered=true;wait=release;}
        if(!wait.await(15,TimeUnit.SECONDS)){expired=true;throw new IllegalStateException("INSTALL_GATE_EXPIRED");}
    }
}
