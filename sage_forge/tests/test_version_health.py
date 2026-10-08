import inspect
import unittest

import sage_forge
from sage_forge.server import ForgeRequestHandler


class ForgeVersionHealthTests(unittest.TestCase):
    def test_runtime_and_http_identity_match(self):
        self.assertEqual(sage_forge.__version__, "0.3.1")
        self.assertEqual(ForgeRequestHandler.server_version, "SageForge/0.3.1")
        source = inspect.getsource(ForgeRequestHandler._dispatch)
        self.assertIn('"version": __version__', source)
        self.assertIn('"api_schema": "1.0"', source)


if __name__ == "__main__":
    unittest.main()
