import hashlib
import importlib.util
import io
import struct
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "sage_tools" / "inspect_gguf.py"
SPEC = importlib.util.spec_from_file_location("inspect_gguf", MODULE_PATH)
GGUF = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GGUF)


def string(value):
    raw = value.encode("utf-8")
    return struct.pack("<Q", len(raw)) + raw


def synthetic_gguf():
    metadata = [
        ("general.architecture", 8, string("unit_arch")),
        ("general.name", 8, string("Sage fixture")),
        ("general.file_type", 4, struct.pack("<I", 7)),
        ("unit_arch.context_length", 4, struct.pack("<I", 4096)),
        ("tokenizer.ggml.model", 8, string("fixture-tokenizer")),
        ("tokenizer.chat_template", 8, string("{{ messages }}")),
        ("tokenizer.ggml.tokens", 9,
         struct.pack("<IQ", 8, 3) + string("a") + string("b") + string("c")),
    ]
    body = bytearray(b"GGUF")
    body += struct.pack("<IQQ", 3, 2, len(metadata))
    for key, type_id, value in metadata:
        body += string(key) + struct.pack("<I", type_id) + value
    body += string("token_embd.weight")
    body += struct.pack("<IQQIQ", 2, 32, 100, 8, 0)
    body += string("output.weight")
    body += struct.pack("<IQQIQ", 2, 32, 100, 8, 3200)
    return bytes(body)


class InspectGgufTest(unittest.TestCase):
    def test_reports_identity_tokenizer_quantization_and_parameters(self):
        result = GGUF.inspect(io.BytesIO(synthetic_gguf()))
        self.assertEqual(3, result["gguf_version"])
        self.assertEqual("unit_arch", result["model_family"])
        self.assertEqual("Sage fixture", result["embedded_model_name"])
        self.assertEqual(6400, result["parameter_count"])
        self.assertEqual("MOSTLY_Q8_0", result["general_file_type_label"])
        self.assertEqual({"Q8_0": 2}, result["tensor_type_counts"])
        self.assertEqual(4096, result["context_length_fields"]["unit_arch.context_length"])
        self.assertEqual("{{ messages }}", result["tokenizer_and_chat_template"]["tokenizer.chat_template"])
        token_summary = result["all_metadata"]["tokenizer.ggml.tokens"]
        self.assertEqual(3, token_summary["count"])
        encoded = b"".join(struct.pack("<Q", 1) + value for value in (b"a", b"b", b"c"))
        self.assertEqual(hashlib.sha256(encoded).hexdigest(), token_summary["sha256_of_encoded_items"])

    def test_rejects_non_gguf(self):
        with self.assertRaises(GGUF.GgufError):
            GGUF.inspect(io.BytesIO(b"nope" + b"\0" * 32))

    def test_rejects_unbounded_string_before_allocation(self):
        payload = b"GGUF" + struct.pack("<IQQQ", 3, 0, 1, GGUF.MAX_STRING_BYTES + 1)
        with self.assertRaises(GGUF.GgufError):
            GGUF.inspect(io.BytesIO(payload))

    def test_rejects_truncated_tensor_descriptor(self):
        payload = b"GGUF" + struct.pack("<IQQ", 3, 1, 0) + string("tensor")
        with self.assertRaises(GGUF.GgufError):
            GGUF.inspect(io.BytesIO(payload))

    def test_rejects_array_item_count_before_scanning(self):
        payload = bytearray(b"GGUF")
        payload += struct.pack("<IQQ", 3, 0, 1)
        payload += string("tokenizer.ggml.tokens")
        payload += struct.pack("<IIQ", 9, 8, GGUF.MAX_ARRAY_ITEMS + 1)
        with self.assertRaises(GGUF.GgufError):
            GGUF.inspect(io.BytesIO(bytes(payload)))


if __name__ == "__main__":
    unittest.main()
