package dev.tripoder.mtlsfixture;

import java.nio.file.*;

public final class HostCheck {
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]);System.out.println(MtlsChecks.run(new TlsMaterial(name->Files.newInputStream(root.resolve(name))),"host-mtls"));
    }
}
