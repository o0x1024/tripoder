"""Keep structural diagnostics, never arbitrary guest/network log values."""
import re

_LINE = re.compile(r"^(\s*\d+\.\d+\s+\d+\s+\d+\s+[VDIWEF]\s+)([^:]+):(.*)$")
_PLATFORM = {'AndroidRuntime', 'nativeloader', 'linker', 'libc', 'ActivityManager',
             'ActivityTaskManager', 'WindowManager', 'am_proc_start', 'am_proc_died',
             'am_kill', 'lmkd', 'tombstoned'}
_SENSITIVE = re.compile(r'''(?:signvalue|authorization|bearer\s|access[_ -]?token|refresh[_ -]?token|password|passwd|secret|cookie|verification[_ -]?code|验证码|身份证|手机号)["']?\s*[:=：]''', re.I)


def structural_log(line):
    match = _LINE.match(line.rstrip('\n'))
    if match is None:
        return '[unparsed diagnostic omitted]'
    header, raw_tag, message = match.groups()
    tag = raw_tag.strip()
    if message.strip() == '[sensitive message omitted]' or _SENSITIVE.search(message):
        return header + raw_tag + ': [sensitive message omitted]'
    if tag.startswith('Tripoder') or tag in _PLATFORM:
        # PackageManager diagnostic arguments can include APK metadata values.
        if tag == 'TripoderPackages':
            message = message.split('metaData=', 1)[0].split(' a=', 1)[0]
        return header + raw_tag + ':' + message
    if tag == 'DEBUG' and (re.match(r'\s*#\d+\s+pc\s', message) or 'backtrace:' in message or 'signal ' in message):
        return header + raw_tag + ':' + message
    return header + raw_tag + ': [guest or unrelated message omitted]'
