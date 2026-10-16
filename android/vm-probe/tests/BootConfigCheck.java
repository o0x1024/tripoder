package dev.tripoder.vmprobe;

import java.io.File;

/** Host test entry: the production writer has no Android dependency. */
public final class BootConfigCheck {
    public static void main(String[] args) throws Exception {
        System.out.println(GuestBootConfig.prepareSoftwareEmulator(new File(args[0])));
    }
}
