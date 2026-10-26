"""Evidence capture must finish even for deeply nested accessibility trees."""
import subprocess
import tempfile
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET

from paired_capture import capture, _write_ui_tree


class PairedCaptureTest(unittest.TestCase):
    def test_deep_hierarchy_preserves_structure_and_escaping(self):
        root = ET.Element('hierarchy')
        node = root
        for _ in range(1500):
            node = ET.SubElement(node, 'node', {'text': 'a < b & "quoted"'})
        node.text = 'last < &'
        node.tail = ' tail & '
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'ui.xml'
            _write_ui_tree(root, path)
            restored = ET.fromstring(path.read_text())
        nodes = list(restored.iter('node'))
        self.assertEqual(len(nodes), 1500)
        self.assertTrue(all(n.get('text') == 'a < b & "quoted"' for n in nodes))
        self.assertEqual(nodes[-1].text, 'last < &')
        self.assertEqual(nodes[-1].tail, ' tail & ')

    def test_deep_capture_redacts_input_and_completes(self):
        window = ('mCurrentFocus=Window{abc u0 dev.tripoder.fixture/.Main}\n'
                  'Window #0 Window{abc u0 dev.tripoder.fixture/.Main}\n'
                  ' mOwnerUid=10460 flags=0x0\n')
        raw = ('<hierarchy>' + '<node package="dev.tripoder.fixture">' * 1500 +
               '<node package="dev.tripoder.fixture" class="android.widget.EditText" '
               'text="person@example.test" password="true"/>' +
               '</node>' * 1500 + '</hierarchy>')
        calls = []

        def run(*args, **kwargs):
            calls.append(args)
            if args == ('shell', 'dumpsys', 'window'):
                output = window
            elif args == ('shell', 'cat', '/sdcard/paired-ui.xml'):
                output = raw
            else:
                output = ''
            return subprocess.CompletedProcess(args, 0, stdout=output, stderr='')

        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            result = capture(run, out, 'dev.tripoder.compat20261023', 'deep-tree',
                             'dev.tripoder.fixture', 10460)
            saved = (out / 'ui.xml').read_text()
            self.assertEqual(len(list(ET.fromstring(saved).iter('node'))), 1501)
        self.assertFalse(result['captured'])
        self.assertEqual(result['reason'], 'SENSITIVE_INPUT_SCREEN_OMITTED')
        self.assertNotIn('person@example.test', saved)
        self.assertIn('[input omitted]', saved)
        self.assertFalse(any('screencap' in command for command in calls))


if __name__ == '__main__':
    unittest.main()
