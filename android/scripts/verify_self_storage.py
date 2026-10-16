"""Independent root movement and owned app-image classification; no device I/O."""
import re


def require(value, reason):
    if not value:
        raise RuntimeError(reason)


def regions(maps):
    pattern = r'^([0-9a-f]+)-([0-9a-f]+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\d+)\s*(.*)$'
    return [dict(start=int(m[1], 16), end=int(m[2], 16), mode=m[3], inode=int(m[6]), name=m[7])
            for line in maps.splitlines() if (m := re.match(pattern, line))]


def owned_image(address, mappings):
    hits = [m for m in mappings if m['start'] <= address < m['end']]
    require(len(hits) == 1, 'OWNER_MAPPING_UNIQUE')
    region = hits[0]
    require(region['mode'] == 'rw-p' and region['inode'] == 0
            and region['name'].startswith('[anon:dalvik-') and region['name'].endswith(']'), 'OWNER_IMAGE_REGION')
    label = region['name'][len('[anon:dalvik-'):-1]
    # Linux may abbreviate the middle of this long VMA name. Match both
    # surviving ends against exactly one independently mapped OWNED file.
    require(label.count('...') <= 1, 'OWNER_IMAGE_LABEL')
    pattern = '.*'.join(re.escape(part) for part in label.split('...'))
    own = (r'(?:/data/app/[^/\s]+/dev\.tripoder\.selfhookprobe-[^/\s]+/oat/arm64/base\.art'
           r'|/data/(?:user/0|data)/dev\.tripoder\.selfhookprobe/cache/oat_primary/arm64/base\.art)')
    files = {m['name'] for m in mappings if m['inode'] > 0 and m['mode'].startswith('r')
             and re.fullmatch(own, m['name']) and re.fullmatch(pattern, m['name'])}
    require(len(files) == 1, 'OWNER_IMAGE_FILE_IDENTITY')
    return {'owner': hex(address), 'regionStart': hex(region['start']), 'regionEnd': hex(region['end']),
            'label': region['name'], 'file': next(iter(files))}


def verify(storage, maps, expected):
    require(expected in ('jit', 'aot', 'cold'), 'ROOT_COMPILATION_MODE')
    rows = storage['rootSnapshots']
    require(len(rows) == storage['published'] == storage['rootedOutsideDeclaredTables'] == 19
            and {r['slot'] for r in rows} == set(range(19)), 'ROOT_SLOT_SET')
    require(len({r['target'] for r in rows}) == len({r['backup'] for r in rows}) == 19
            and not ({r['target'] for r in rows} & {r['backup'] for r in rows}), 'ROOT_METHOD_IDENTITIES')
    moved = containers = 0
    images = []
    mappings = regions(maps)
    for row in rows:
        before, after = [int(row[k], 16) for k in ('ownerBefore', 'ownerAfter')]
        require(row['rooted'] is True and before > 0 and after > 0
                and after == int(row['targetOwner'], 16) == int(row['backupOwner'], 16), 'ROOT_OWNER_RELATION')
        initial, final = [list(map(lambda x: int(x, 16), row[k])) for k in ('containersBefore', 'containersAfter')]
        require(len(initial) == len(final) == 3 and all(x > 0 for x in initial + final), 'ROOT_CONTAINERS')
        containers += sum(a != b for a, b in zip(initial, final))
        moved += before != after
        if before == after and expected == 'cold':
            images.append({'slot': row['slot'], **owned_image(after, mappings)})
    require(moved == storage['observedOwnerMoves'] and containers == storage['observedContainerRootMoves'] > 0,
            'ROOT_MOVEMENT_ACCOUNTING')
    require(moved > 0 or (expected == 'cold' and len(images) == 19), 'OWNER_MOVEMENT_OR_PROVEN_COLD_IMAGE')
    return {'ownerMoves': moved, 'containerSlotMoves': containers, 'imageOwners': images,
            'ownerMovementProven': moved > 0}
