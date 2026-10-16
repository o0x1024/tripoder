package dev.tripoder.runtime;

/** Component names are generated into both APK manifests from this fixed 8 x 4 pool. */
final class RuntimeJobSlots {
    static final int PER_PROCESS=4;
    static String stub(int process,int lane){if(process<0||process>=RuntimeSlot.CAPACITY||lane<0||lane>=PER_PROCESS)throw new IllegalArgumentException("JOB_SLOT_INVALID");return "dev.tripoder.runtime.generated.JobP"+process+"$J"+lane;}
    static int index(String stub){for(int process=0;process<RuntimeSlot.CAPACITY;process++)for(int lane=0;lane<PER_PROCESS;lane++)if(stub(process,lane).equals(stub))return process;throw new SecurityException("JOB_STUB_INVALID");}
    private RuntimeJobSlots(){}
}
