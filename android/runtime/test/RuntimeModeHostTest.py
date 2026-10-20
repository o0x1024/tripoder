#!/usr/bin/env python3
"""Compile RuntimeMode with its host test and run it. No device, no Android classes, writes only a temp directory."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', required=True, type=Path)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[3]
    java = args.java_home / 'bin'
    env = dict(os.environ, JAVA_HOME=str(args.java_home))
    classes = Path(tempfile.mkdtemp(prefix='tripoder-mode-test-'))
    def run(*command):
        result = subprocess.run(list(map(str, command)), env=env, capture_output=True, text=True)
        if result.returncode:
            print(result.stdout, end='')
            print(result.stderr, end='')
            result.check_returncode()
        return result
    run(java / 'javac', '--release', '17', '-Xlint:all', '-d', classes,
        repo / 'android/runtime/src/dev/tripoder/runtime/RuntimeMode.java',
        repo / 'android/runtime/test/dev/tripoder/runtime/RuntimeModeTest.java')
    print(run(java / 'java', '-cp', classes, 'dev.tripoder.runtime.RuntimeModeTest').stdout, end='')


if __name__ == '__main__':
    main()
