#!/usr/bin/env python3
"""Verify every fixed framework member used by the runtime against a boot framework dump.

Usage: verify-framework.py /tmp/dd_classes*.txt [--source android]

The runtime resolves members through a fixed registry (FixedFrameworkMembers) and
rejects any miss at startup. Boot frameworks move members between releases, so a
new device surfaces the mismatch as a slot crash. This script checks the same
identity up front: owner class, declared name, descriptor and staticness.
"""
import argparse
import glob
import os
import re
import sys


def parse_dumps(paths):
    classes = {}
    for path in paths:
        current = None
        section = None
        pending = None
        with open(path, encoding='utf-8', errors='replace') as handle:
            for line in handle:
                m = re.match(r"\s*Class descriptor\s+: '([^']+)'", line)
                if m:
                    current = m.group(1)
                    section = None
                    pending = None
                    classes.setdefault(current, {'fields': {}, 'methods': {}})
                    continue
                if current is None:
                    continue
                m = re.match(r"\s*(Static fields|Instance fields|Direct methods|Virtual methods)", line)
                if m:
                    section = 'fields' if 'fields' in m.group(1) else 'methods'
                    pending = None
                    continue
                m = re.match(r"\s*name\s+: '([^']+)'", line)
                if m and section:
                    pending = m.group(1)
                    continue
                if pending and section:
                    mt = re.match(r"\s*type\s+: '([^']+)'", line)
                    ma = re.match(r"\s*access\s+: (\S+)", line)
                    if mt or ma:
                        entry = classes[current][section].setdefault(pending, {'types': [], 'static': False})
                        if mt:
                            entry['types'].append(mt.group(1))
                        if ma:
                            entry['static'] = bool(int(ma.group(1), 16) & 0x0008)
                        if mt:
                            pending = None
    return classes


def parse_calls(source_root):
    pattern = re.compile(r'FixedFrameworkMembers\.(method|constructor|field|optionalField)\(')
    calls = []
    for root, _, files in os.walk(source_root):
        for name in files:
            if not name.endswith('.java'):
                continue
            path = os.path.join(root, name)
            text = open(path, encoding='utf-8', errors='replace').read()
            for m in pattern.finditer(text):
                depth = 0
                start = m.end()
                for i in range(start, len(text)):
                    if text[i] == '(':
                        depth += 1
                    elif text[i] == ')':
                        if depth == 0:
                            args = text[start:i]
                            break
                        depth -= 1
                else:
                    continue
                parts = [p.strip() for p in re.split(r',(?=(?:[^"]*"[^"]*")*[^"]*$)', args)]
                calls.append((os.path.relpath(path), text[:m.start()].count('\n') + 1, m.group(1), parts))
    return calls


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('dumps', nargs='+')
    parser.add_argument('--source', default='android')
    args = parser.parse_args()
    paths = [p for pattern in args.dumps for p in glob.glob(pattern)] or args.dumps
    classes = parse_dumps(paths)
    names_src = ''
    for root, _, files in os.walk(args.source):
        for name in files:
            if name == 'FixedFrameworkMembers.java':
                names_src = open(os.path.join(root, name), encoding='utf-8').read()
    names = re.findall(r'"([^"]+)"', re.search(r'NAMES=\{(.*?)\};', names_src, re.S).group(1))
    owners = ['L' + n.replace('.', '/') + ';' for n in names]
    missing, checked = [], 0
    for rel, line, kind, parts in parse_calls(args.source):
        try:
            if not parts[0].isdigit():
                continue
            index = int(parts[1] if kind in ('field', 'optionalField') else parts[0])
            if index >= len(owners):
                missing.append((rel, line, kind, parts, 'owner index out of range'))
                continue
            cls = owners[index]
            info = classes.get(cls)
            if info is None:
                missing.append((rel, line, kind, parts, 'class not in dump: ' + names[index]))
                continue
            if kind == 'constructor':
                name = '<init>'
                desc = parts[1].strip('"')
                is_static = False
                hit = info['methods'].get(name)
                checked += 1
                if not hit or desc not in hit['types']:
                    missing.append((rel, line, kind, parts, 'constructor not found'))
                elif hit['static'] != is_static:
                    missing.append((rel, line, kind, parts, 'static mismatch'))
            elif kind == 'method':
                name = parts[1].strip('"')
                desc = parts[2].strip('"')
                is_static = parts[3] == 'true' if len(parts) > 3 else False
                hit = info['methods'].get(name)
                checked += 1
                if not hit or desc not in hit['types']:
                    missing.append((rel, line, kind, parts, 'method not found'))
                elif hit['static'] != is_static:
                    missing.append((rel, line, kind, parts, 'static mismatch'))
            else:
                name = parts[2].strip('"')
                typ = parts[3].strip('"')
                is_static = parts[4] == 'true' if len(parts) > 4 else False
                hit = info['fields'].get(name)
                checked += 1
                if not hit or typ not in hit['types']:
                    missing.append((rel, line, kind, parts, 'field not found'))
                elif hit['static'] != is_static:
                    missing.append((rel, line, kind, parts, 'static mismatch'))
        except Exception as error:
            missing.append((rel, line, kind, parts, 'parse error: %s' % error))
    print('checked', checked, 'missing', len(missing))
    for item in missing:
        print('%s:%s %s(%s) -> %s' % (item[0], item[1], item[2], ', '.join(item[3]), item[4]))
    return 1 if missing else 0


if __name__ == '__main__':
    sys.exit(main())
