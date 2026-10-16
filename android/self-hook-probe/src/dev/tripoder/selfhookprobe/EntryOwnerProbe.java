package dev.tripoder.selfhookprobe;
final class EntryOwnerProbe {
    static native String runNative(int diagnosticFd);
    static native byte[] fixturePage(boolean published);
    static native byte[] publishedElf();
}
