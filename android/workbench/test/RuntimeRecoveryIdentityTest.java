package dev.tripoder.hooklab;
import dev.tripoder.runtime.RuntimeRecoveryIdentity;
public final class RuntimeRecoveryIdentityTest {
    public static void main(String[] args){
        RuntimeRecoveryIdentity.requireScope("space/original/package/cmb.pb","original","cmb.pb");
        for(String scope:new String[]{null,"*","space/original/package/cmb.pb","retired/old/space/current/package/cmb.pb","space/current/package/other"}){
            try{RuntimeRecoveryIdentity.requireScope(scope,"current","cmb.pb");throw new AssertionError("accepted a different or unavailable owner: "+scope);}
            catch(SecurityException expected){if(!expected.getMessage().equals("LAUNCH_RECOVERY_BINDING_STALE"))throw new AssertionError(expected);}
        }
        System.out.println("Recovery identity: exact original ownership required; current, global and retired scopes rejected");
    }
}
