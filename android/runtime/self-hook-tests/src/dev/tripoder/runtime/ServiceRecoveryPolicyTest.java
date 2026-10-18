package dev.tripoder.runtime;

public final class ServiceRecoveryPolicyTest {
    public static void main(String[] args){
        int checks=0;
        for(boolean closed:new boolean[]{false,true})for(boolean dead:new boolean[]{false,true})for(boolean hooked:new boolean[]{false,true})for(var admission:ServiceRecoveryPolicy.Admission.values()){
            boolean allowed=true;
            try{ServiceRecoveryPolicy.require(closed,dead,hooked,admission);}catch(IllegalStateException|SecurityException expected){allowed=false;}
            boolean expected=!closed&&dead&&(!hooked||admission==ServiceRecoveryPolicy.Admission.NEW_REQUEST);
            if(allowed!=expected)throw new AssertionError("closed="+closed+" dead="+dead+" hooked="+hooked+" admission="+admission);
            checks++;
        }
        System.out.println("service-recovery-policy: "+checks+" passed");
    }
}
