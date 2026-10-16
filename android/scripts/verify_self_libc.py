"""Independent byte/mapping verification for the exact libc shared-page probe."""
import hashlib
import json
from pathlib import Path
import re
import struct


def verify_libc(directory, result, build, require, mapping):
    def sha(path):
        return hashlib.sha256(path.read_bytes()).hexdigest()

    phase = result['fixedLibc']
    contract = json.loads((directory / 'libc-contract.json').read_text())
    platform_hash = result['platform']['binaries']['/apex/com.android.runtime/lib64/bionic/libc.so']
    require(contract['passed'] is True and contract['sourceLibcSha256'] == platform_hash ==
            build['platformInputs']['libcSha256'] == sha(Path(build['platformInputs']['libc'])), 'FIXED_LIBC_SOURCE')
    require(contract['contractSha256'] == build['sources']['android/self-hook/native/libc_fixed_contract.h']
            and contract['checkerSha256'] == build['sources']['android/self-hook/tests/check_libc_contract.py'], 'LIBC_HOST_CONTRACT')
    require(phase['passed'] is True and phase['checks'] == 24 and phase['failures'] == phase['caseFailures'] == 0
            and phase['caseChecks'] == 24, 'FIXED_LIBC_CHECKS')
    require(phase['validatedEntries'] == phase['absoluteEntries'] == 10 and phase['changedWords'] == 40 and phase['publications'] == phase['restorations'] == 2
            and phase['rejectedBatches'] == 1 and phase['enabled'] is False and phase['pauseStatus'] == 0, 'FIXED_LIBC_TRANSITIONS')
    require(phase['workerCalls'] > phase['workersActive'] > phase['workersBefore'] > 0
            and phase['workerErrors'] == 0 and phase['workersAfterRestore'] is True, 'FIXED_LIBC_CONCURRENCY')
    require(len(phase['entryCalls']) == 10 and all(value > 0 for value in phase['entryCalls']), 'FIXED_LIBC_ENTRY_CALLS')
    require(phase['sourceBti'] is True and phase['absoluteEntries'] == 10 and phase['processLifetimeLease'] is True
            and phase['liveStagingPages'] == phase['liveAnonymousPages'] == 0, 'FIXED_LIBC_LIFETIME')
    require(phase['runtimePolicyReused'] is True and phase['fixedLibcStorageOnly'] is True
            and phase['genericNativeHook'] is False and phase['btiFaultOracleTested'] is False
            and phase['productMigrated'] is False, 'FIXED_LIBC_SCOPE')
    for name, digest in result['libcSnapshots'].items():
        require(sha(directory / 'device' / name) == digest, 'LIBC_SNAPSHOT_HASH:' + name)
    require(contract['absoluteEntryAudit']['interiorReferences']==0 and contract['absoluteEntryAudit']['auditedInstructions']>100000 and contract['absoluteEntryAudit']['patchBytesPerEntry']==16,'LIBC_ABSOLUTE_STATIC_AUDIT')
    source = (directory / 'libc-source-pages.bin').read_bytes()
    require(len(source) == 8192 and hashlib.sha256(source).hexdigest() == contract['sourcePagesSha256'], 'LIBC_SOURCE_PAGES')
    for index, page in enumerate(contract['pages']):
        require(hashlib.sha256(source[index * 4096:(index + 1) * 4096]).hexdigest() == page['sha256'], 'LIBC_SOURCE_PAGE_HASH')
    before = (directory / 'device/libc-pages-libc-prepared.bin').read_bytes()
    rejected = (directory / 'device/libc-pages-libc-rejected.bin').read_bytes()
    active = (directory / 'device/libc-pages-libc-active.bin').read_bytes()
    restored = (directory / 'device/libc-pages-libc-restored.bin').read_bytes()
    require(before == rejected == restored == source and len(active) == 8192, 'LIBC_COMPLETE_ORIGINAL_IMAGES')
    base = int(phase['base'], 16)
    targets = [int(value,16) for value in phase['targets']]
    require(len(targets)==10 and len(set(targets))==10,'LIBC_EXACT_ABSOLUTE_TARGETS')
    pages = [int(address, 16) for address in phase['pages']]
    require(pages == [base + page['rva'] for page in contract['pages']], 'LIBC_PAGE_ADDRESSES')
    expected = bytearray(source)
    require(len(contract['entries']) == 10, 'LIBC_HOST_ENTRIES')
    for index, entry in enumerate(contract['entries']):
        offset = entry['rva'] - contract['pages'][0]['rva']
        require(source[offset:offset + 24] == struct.pack('<6I', *entry['words']), 'LIBC_ORIGINAL_ENTRY')
        struct.pack_into('<IIQ', expected, offset + 4, 0x58000050, 0xd61f0200, targets[index])
    require(active == expected, 'LIBC_ONLY_FORTY_EXACT_WORDS_CHANGED')

    def guarded(text, address):
        selected = False
        for line in text.splitlines():
            header = re.match(r'^([0-9a-f]+)-([0-9a-f]+)\s+(\S+)', line)
            if header:
                selected = int(header[1], 16) <= address and address + 4096 <= int(header[2], 16)
                if selected:
                    require(header[3] == 'r-xp', 'LIBC_SMAPS_RX')
            elif selected and line.startswith('VmFlags:'):
                flags = line.split()[1:]
                return 'bt' in flags and 'mt' not in flags
        return False

    baseline = None
    for stage in ('libc-prepared', 'libc-rejected', 'libc-active', 'libc-restored'):
        maps = (directory / 'device' / ('maps-' + stage + '.txt')).read_text()
        smaps = (directory / 'device' / ('smaps-' + stage + '.txt')).read_text()
        rows = [mapping(maps, address) for address in pages]
        require(all(guarded(smaps, address) for address in pages), 'LIBC_BTI_PRESERVED:' + stage)
        for target in targets:
            target_map=mapping(maps,target)
            require(target%4==0 and target_map['mode']=='r-xp' and target_map['inode']>0 and target_map['path'].endswith('/libtripoderselfhook.so'),'LIBC_TARGET_OWNED_EXECUTABLE')
        if stage == 'libc-prepared':
            baseline = rows
            for index, row in enumerate(rows):
                require(row['mode'] == 'r-xp' and row['inode'] > 0 and row['path'] ==
                        '/apex/com.android.runtime/lib64/bionic/libc.so' and row['offset'] == contract['pages'][index]['fileOffset'],
                        'LIBC_ORIGINAL_FILE_MAP')
        elif stage in ('libc-rejected', 'libc-restored'):
            require(rows == baseline, 'LIBC_FILE_MAPPING_IDENTITY_RESTORED')
        else:
            require(all(row['mode'] == 'r-xp' and row['inode'] == 0 and row['device'] == '00:00' and not row['path'] for row in rows),
                    'LIBC_ANONYMOUS_EXECUTION_PAGES')
    require((directory / 'device/libc-artifacts/seed.dat').read_bytes() == b'artifact', 'LIBC_ARTIFACT_IMMUTABLE')
    return {'fixedLibcChecks': phase['checks'], 'fixedLibcCaseChecks': phase['caseChecks'],
            'fixedLibcWorkerCalls': phase['workerCalls'], 'fixedLibcChangedWords': 40,
            'fixedLibcGuardedPages': 2, 'fixedLibcAbsoluteEntries': 10}
