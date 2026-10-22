#!/usr/bin/env python3
"""Compile and exercise the production policy and contract, using temporary journals only."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
JAVA = Path('/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home/bin')
with tempfile.TemporaryDirectory(prefix='tripoder-recovery-tests-') as tmp:
    sources = [
        'android/engine/src/dev/tripoder/runtime/EngineRecoveryPolicy.java',
        'android/engine/test/dev/tripoder/runtime/EngineRecoveryPolicyTest.java',
        'android/runtime/src/dev/tripoder/runtime/ExecutionContract.java',
        'android/runtime/test/dev/tripoder/runtime/ExecutionContractTest.java',
        'android/runtime/src/dev/tripoder/runtime/ServiceRecoveryPolicy.java',
        'android/runtime/self-hook-tests/src/dev/tripoder/runtime/ServiceRecoveryPolicyTest.java',
    ]
    subprocess.run([str(JAVA/'javac'), '-d', tmp, *[str(ROOT/p) for p in sources]], check=True)
    for name in ['EngineRecoveryPolicyTest', 'ExecutionContractTest', 'ServiceRecoveryPolicyTest']:
        subprocess.run([str(JAVA/'java'), '-cp', tmp, 'dev.tripoder.runtime.'+name, str(Path(tmp)/'policy')], check=True)
