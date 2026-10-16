#!/usr/bin/env python3
"""Run the production resolver under a deterministic forwarded-root model."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--java-home', type=Path, required=True)
    args = parser.parse_args()
    native = ROOT / 'android/self-hook/native'
    test = ROOT / 'android/self-hook/tests/framework_root_test.c'
    with tempfile.TemporaryDirectory(prefix='tripoder-framework-roots-') as directory:
        folder = Path(directory)
        command = ['clang', '-std=c11', '-Wall', '-Wextra', '-Werror',
                   '-I' + str(args.java_home / 'include'), '-I' + str(args.java_home / 'include/darwin'),
                   '-I' + str(native), str(test)]
        subprocess.run([*command, '-o', str(folder / 'fixed')], check=True)
        fixed = subprocess.run([str(folder / 'fixed')], capture_output=True, text=True, check=True)
        # The prior raw comparison must fail the same behavioral test.
        mutant = (native / 'art_framework.c').read_text().replace(
            'uintptr_t declared=declaring_class(method);', 'uintptr_t declared=method[0];').replace(
            'declaring_class((const uint32_t *)field)!=klass', '*(const uint32_t *)field!=klass')
        (folder / 'raw-root.c').write_text(mutant)
        subprocess.run([*command, '-Wno-unused-function', '-DTP_FRAMEWORK_SOURCE="' + str(folder / 'raw-root.c') + '"',
                        '-o', str(folder / 'raw-root')], check=True)
        previous = subprocess.run([str(folder / 'raw-root')], capture_output=True, text=True)
        if previous.returncode == 0:
            raise RuntimeError('RAW_ROOT_REGRESSION_NOT_DETECTED')
        print(json.dumps({'passed': True, 'productionResolver': fixed.stdout.strip(),
                          'priorRawComparisonRejected': True, 'deviceExecuted': False}))


if __name__ == '__main__':
    main()
