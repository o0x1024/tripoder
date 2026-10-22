package dev.tripoder.resultcontract;

import java.io.Serializable;

/** Synthetic package-owned domain data; no identity or bank material. */
public final class Model implements Serializable {
    private static final long serialVersionUID=1L;
    public enum Outcome { SUCCESS,CANCELED,FAILED }
    public static final class Detail implements Serializable {
        private static final long serialVersionUID=1L;
        final long precise=9007199254740993L;
        final String marker="owned-result";
    }
    final Outcome outcome;
    final Detail detail=new Detail();
    final int senderPid=android.os.Process.myPid();
    final String caller;
    byte[] bytes={4,2};
    int rejectedCases;
    Model(int mode,String caller){this.outcome=Outcome.values()[mode];this.caller=caller;}
}
