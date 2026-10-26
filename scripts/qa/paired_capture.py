"""Capture only the QA foreground; keep unrelated windows and input values out of evidence."""
import json
import re
import xml.etree.ElementTree as ET
from xml.sax.saxutils import XMLGenerator


def _write_ui_tree(tree, destination):
    """Serialize the sanitized hierarchy without Python's recursive XML writer."""
    with destination.open('w', encoding='utf-8') as stream:
        writer = XMLGenerator(stream, encoding='utf-8', short_empty_elements=True)
        writer.startDocument()
        writer.startElement(tree.tag, tree.attrib)
        if tree.text:
            writer.characters(tree.text)
        stack = [(tree, iter(tree))]
        while stack:
            node, children = stack[-1]
            child = next(children, None)
            if child is None:
                writer.endElement(node.tag)
                if node.tail:
                    writer.characters(node.tail)
                stack.pop()
            else:
                writer.startElement(child.tag, child.attrib)
                if child.text:
                    writer.characters(child.text)
                stack.append((child, iter(child)))
        writer.endDocument()


def _focus(window):
    focused = []
    for line in window.splitlines():
        if 'mCurrentFocus=' not in line:
            continue
        match = re.search(r'Window\{([0-9a-f]+)\s+u\d+\s+([\w.]+/[\w.$]+)', line)
        if match:
            focused.append((match[1], match[2]))
    if len(focused) != 1:
        return None
    token, component = focused[0]
    blocks = re.split(r'(?m)^\s*Window #\d+ ', window)
    matches = [block for block in blocks[1:] if block.startswith('Window{' + token + ' ')]
    owner = re.search(r'\bmOwnerUid=(\d+)\b', matches[0]) if len(matches) == 1 else None
    if not owner:
        return None
    block = matches[0]
    secure = bool(re.search(r'\bSECURE\b', block))
    numeric = re.search(r'\b(?:fl|flags)=0x([0-9a-fA-F]+)\b', block)
    if numeric:
        secure = secure or bool(int(numeric[1], 16) & 0x2000)
    return component, int(owner[1]), secure, token


def capture(run, out, package, run_id, guest, owner_uid):
    window = run('shell', 'dumpsys', 'window').stdout
    focus = _focus(window)
    focused = [focus[0]] if focus else []
    verdict = {'focusedComponents': focused, 'captured': False, 'ownerUid': focus[1] if focus else None}
    verdict['secureWindow'] = focus[2] if focus else None
    (out / 'focus.txt').write_text(json.dumps({'focusedComponents': focused}) + '\n')
    activity = run('shell', 'dumpsys', 'activity', 'activities').stdout
    owned = sorted(set(re.findall(re.escape(package) + r'/[\w.$]+', activity)))
    (out / 'activities.txt').write_text('\n'.join(owned) + '\n')
    if (focus is None or not isinstance(owner_uid, int) or focus[1] != owner_uid or
            focus[0].split('/', 1)[0] not in (package, guest)):
        verdict['reason'] = 'FOREIGN_OR_UNRESOLVED_FOREGROUND'
        return verdict
    if focus[0].split('/', 1)[0] == package and focus[0].split('/', 1)[1] in (
            'dev.tripoder.hooklab.WorkbenchActivity', 'dev.tripoder.runtime.RuntimeRejectedActivity'):
        verdict['reason'] = 'HOST_CONTROL_OR_TERMINAL_SCREEN_IS_NOT_GUEST_UI'
        return verdict
    dumped = run('shell', 'uiautomator', 'dump', '/sdcard/paired-ui.xml', timeout=15)
    if dumped.returncode != 0:
        verdict['reason'] = 'UI_DUMP_FAILED'
        verdict['dumpReturnCode'] = dumped.returncode
        diagnostic = (dumped.stdout + dumped.stderr).lower()
        verdict['dumpDiagnostic'] = ('UI_AUTOMATOR_IDLE_STATE_UNAVAILABLE' if
                                     'could not get idle state' in diagnostic else
                                     'UI_AUTOMATOR_COMMAND_FAILED')
        return verdict
    raw = run('shell', 'cat', '/sdcard/paired-ui.xml').stdout
    try:
        tree = ET.fromstring(raw)
    except ET.ParseError:
        verdict['reason'] = 'UI_DUMP_INVALID'
        return verdict
    sensitive_input = False
    foreign_content = False
    for node in tree.iter('node'):
        if node.get('package', '') not in ('', package, guest):
            for key in ('text', 'content-desc', 'hint'):
                if node.get(key, ''):
                    foreign_content = True
                    node.set(key, '[foreign UI omitted]')
        value = node.get('text', '')
        if value and (node.get('password') == 'true' or
                      ('EditText' in node.get('class', '') and
                       (re.search(r'@|\d{6,}', value)))):
            sensitive_input = True
            node.set('text', '[input omitted]')
        for key in ('text', 'content-desc'):
            text = node.get(key, '')
            text = re.sub(r'[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}', '[email omitted]', text)
            text = re.sub(r'(?<!\d)\+?\d[\d ()-]{6,}\d(?!\d)', '[number omitted]', text)
            node.set(key, text)
    _write_ui_tree(tree, out / 'ui.xml')
    if foreign_content:
        verdict['reason'] = 'FOREIGN_VISIBLE_CONTENT_SCREEN_OMITTED'
        return verdict
    if focus[2]:
        verdict['reason'] = 'SECURE_WINDOW_SCREEN_OMITTED'
        return verdict
    if sensitive_input:
        verdict['reason'] = 'SENSITIVE_INPUT_SCREEN_OMITTED'
        return verdict
    # Re-check focus after the UI dump before capturing pixels.
    current = run('shell', 'dumpsys', 'window').stdout
    if _focus(current) != focus:
        verdict['reason'] = 'FOREGROUND_CHANGED'
        return verdict
    screen_path = '/sdcard/Android/data/' + package + '/files/paired-screen-' + run_id + '.png'
    screen = run('shell', 'screencap', '-p', screen_path, timeout=15)
    if _focus(run('shell', 'dumpsys', 'window').stdout) != focus:
        run('shell', 'rm', '--', screen_path)
        verdict['reason'] = 'FOREGROUND_CHANGED_DURING_SCREEN_CAPTURE'
        return verdict
    pulled = run('pull', screen_path, str(out / 'ui.png'), timeout=15) if screen.returncode == 0 else None
    verdict['captured'] = bool(pulled is not None and pulled.returncode == 0 and
                               (out / 'ui.png').is_file() and
                               (out / 'ui.png').read_bytes().startswith(b'\x89PNG\r\n\x1a\n'))
    if not verdict['captured']:
        verdict['reason'] = 'SCREEN_CAPTURE_FAILED'
    return verdict
