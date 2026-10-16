package dev.tripoder.runtime;

/** Checks authority deadlines without sleeping or relying on watchdog scheduling. */
public final class AgentContactLeaseTest {
    private static int checks;
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
    public static void main(String[] args){
        AgentContactLease handoff=new AgentContactLease(1000);
        check(handoff.expiry(61_000)==null&&!handoff.connected(),"human import is not disconnected");
        handoff.touch(201_000);
        check(handoff.connected()&&handoff.expiry(245_999)==null,"first signed request establishes contact");
        check("CONTACT_EXPIRED".equals(handoff.expiry(246_000)),"45 second disconnect deadline is exact");
        try{handoff.touch(246_000);throw new AssertionError("late request revived lease");}catch(IllegalStateException expected){check("CONTACT_EXPIRED".equals(expected.getMessage()),"late request cannot beat delayed watchdog");}
        AgentContactLease absent=new AgentContactLease(1000);
        check(absent.expiry(480_999)==null&&"CLIENT_CONNECT_TIMEOUT".equals(absent.expiry(481_000)),"initial wait is bounded at 8 minutes");
        AgentContactLease bounded=new AgentContactLease(0);bounded.touch(479_999);bounded.touch(520_000);bounded.touch(560_000);bounded.touch(599_999);
        check(bounded.deadline()==600_000&&"EXPIRED".equals(bounded.expiry(600_000)),"heartbeats never extend absolute authorization");
        check("EXPIRED".equals(absent.expiry(601_000)),"absolute expiration dominates first contact");
        System.out.println("{\"passed\":true,\"checks\":"+checks+"}");
    }
}
