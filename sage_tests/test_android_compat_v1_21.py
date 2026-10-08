#!/usr/bin/env python3
from pathlib import Path
import sys
import unittest

ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path('sage_build')
JAVA = ROOT / 'app/src/main/java/com/pineapple/sage'


class AndroidCompatibilitySourceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.main = (JAVA / 'MainActivity.java').read_text()
        cls.commands = (JAVA / 'SageCommandEngine.java').read_text()

    def test_url_encoding_supports_min_sdk_26(self):
        self.assertIn(
            'URLEncoder.encode(value, StandardCharsets.UTF_8.name())',
            self.commands,
        )
        self.assertNotIn(
            'URLEncoder.encode(value, StandardCharsets.UTF_8);',
            self.commands,
        )
        self.assertIn('catch (java.io.UnsupportedEncodingException impossible)', self.commands)

    def test_uri_grants_use_a_declared_intent_flag_constant(self):
        call = (
            'getContentResolver().takePersistableUriPermission(\n'
            '                        uri,\n'
            '                        Intent.FLAG_GRANT_READ_URI_PERMISSION\n'
            '                );'
        )
        self.assertGreaterEqual(self.main.count(call), 3)
        self.assertNotIn('takePersistableUriPermission(uri, flags)', self.main)

    def test_pre_android_13_receiver_path_is_intentionally_guarded(self):
        self.assertIn(
            '@android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")\n'
            '    private void registerStateReceiver()',
            self.main,
        )
        self.assertIn('Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU', self.main)
        self.assertIn('Context.RECEIVER_NOT_EXPORTED', self.main)


if __name__ == '__main__':
    sys.argv = [sys.argv[0]]
    unittest.main(verbosity=2)
