#!/usr/bin/env python3
"""Bounded, read-only GGUF v2/v3 metadata and tensor-descriptor inspector."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import struct
import sys
from collections import Counter


VALUE_TYPES = {
    0: ("uint8", "<B"),
    1: ("int8", "<b"),
    2: ("uint16", "<H"),
    3: ("int16", "<h"),
    4: ("uint32", "<I"),
    5: ("int32", "<i"),
    6: ("float32", "<f"),
    7: ("bool", "<?"),
    8: ("string", None),
    9: ("array", None),
    10: ("uint64", "<Q"),
    11: ("int64", "<q"),
    12: ("float64", "<d"),
}

TENSOR_TYPES = {
    0: "F32", 1: "F16", 2: "Q4_0", 3: "Q4_1", 6: "Q5_0", 7: "Q5_1",
    8: "Q8_0", 9: "Q8_1", 10: "Q2_K", 11: "Q3_K", 12: "Q4_K",
    13: "Q5_K", 14: "Q6_K", 15: "Q8_K", 16: "IQ2_XXS", 17: "IQ2_XS",
    18: "IQ3_XXS", 19: "IQ1_S", 20: "IQ4_NL", 21: "IQ3_S", 22: "IQ2_S",
    23: "IQ4_XS", 24: "I8", 25: "I16", 26: "I32", 27: "I64", 28: "F64",
    29: "IQ1_M", 30: "BF16", 31: "Q4_0_4_4", 32: "Q4_0_4_8", 33: "Q4_0_8_8",
    34: "TQ1_0", 35: "TQ2_0", 39: "MXFP4", 40: "NVFP4", 41: "Q1_0",
    42: "Q2_0",
}

FILE_TYPES = {
    0: "ALL_F32", 1: "MOSTLY_F16", 2: "MOSTLY_Q4_0", 3: "MOSTLY_Q4_1",
    4: "MOSTLY_Q4_1_SOME_F16", 7: "MOSTLY_Q8_0", 8: "MOSTLY_Q5_0",
    9: "MOSTLY_Q5_1", 10: "MOSTLY_Q2_K",
    11: "MOSTLY_Q3_K_S", 12: "MOSTLY_Q3_K_M", 13: "MOSTLY_Q3_K_L",
    14: "MOSTLY_Q4_K_S", 15: "MOSTLY_Q4_K_M", 16: "MOSTLY_Q5_K_S",
    17: "MOSTLY_Q5_K_M", 18: "MOSTLY_Q6_K", 19: "MOSTLY_IQ2_XXS",
    20: "MOSTLY_IQ2_XS", 21: "MOSTLY_Q2_K_S", 22: "MOSTLY_IQ3_XS",
    23: "MOSTLY_IQ3_XXS", 24: "MOSTLY_IQ1_S", 25: "MOSTLY_IQ4_NL",
    26: "MOSTLY_IQ3_S", 27: "MOSTLY_IQ3_M", 28: "MOSTLY_IQ2_S",
    29: "MOSTLY_IQ2_M", 30: "MOSTLY_IQ4_XS", 31: "MOSTLY_IQ1_M",
    32: "MOSTLY_BF16", 36: "MOSTLY_TQ1_0", 37: "MOSTLY_TQ2_0",
    38: "MOSTLY_MXFP4_MOE", 39: "MOSTLY_NVFP4", 40: "MOSTLY_Q1_0",
    41: "MOSTLY_Q2_0",
}

MAX_METADATA = 1_000_000
MAX_TENSORS = 1_000_000
MAX_STRING_BYTES = 16 * 1024 * 1024
MAX_ARRAY_ITEMS = 2_000_000
MAX_HEADER_SCAN_BYTES = 512 * 1024 * 1024
MAX_DIMS = 16
MAX_PARAMETERS = 1_000_000_000_000_000
ARRAY_PREVIEW_ITEMS = 8


class GgufError(ValueError):
    pass


class Reader:
    def __init__(self, stream):
        self.stream = stream
        self.offset = 0

    def exact(self, count: int) -> bytes:
        if count < 0:
            raise GgufError("negative read")
        if self.offset + count > MAX_HEADER_SCAN_BYTES:
            raise GgufError(
                f"metadata/descriptors exceed safe scan bound {MAX_HEADER_SCAN_BYTES} bytes"
            )
        value = self.stream.read(count)
        if len(value) != count:
            raise GgufError(f"truncated GGUF at byte {self.offset}: wanted {count}, got {len(value)}")
        self.offset += count
        return value

    def unpack(self, fmt: str):
        size = struct.calcsize(fmt)
        return struct.unpack(fmt, self.exact(size))[0]

    def string_bytes(self) -> bytes:
        length = self.unpack("<Q")
        if length > MAX_STRING_BYTES:
            raise GgufError(f"string length {length} exceeds safe bound {MAX_STRING_BYTES}")
        return self.exact(length)

    def string(self) -> str:
        raw = self.string_bytes()
        try:
            return raw.decode("utf-8")
        except UnicodeDecodeError as error:
            raise GgufError(f"invalid UTF-8 string at byte {self.offset - len(raw)}") from error


def scalar(reader: Reader, type_id: int):
    definition = VALUE_TYPES.get(type_id)
    if definition is None or definition[1] is None:
        raise GgufError(f"unsupported scalar metadata type {type_id}")
    return reader.unpack(definition[1])


def array_value(reader: Reader):
    item_type = reader.unpack("<I")
    count = reader.unpack("<Q")
    if count > MAX_ARRAY_ITEMS:
        raise GgufError(f"array length {count} exceeds safe bound {MAX_ARRAY_ITEMS}")
    item_definition = VALUE_TYPES.get(item_type)
    if item_definition is None or item_type == 9:
        raise GgufError(f"unsupported array item type {item_type}")
    digest = hashlib.sha256()
    preview = []
    if item_type == 8:
        for index in range(count):
            raw = reader.string_bytes()
            digest.update(struct.pack("<Q", len(raw)))
            digest.update(raw)
            if index < ARRAY_PREVIEW_ITEMS:
                preview.append(raw.decode("utf-8", errors="replace"))
    else:
        fmt = item_definition[1]
        assert fmt is not None
        width = struct.calcsize(fmt)
        for index in range(count):
            raw = reader.exact(width)
            digest.update(raw)
            if index < ARRAY_PREVIEW_ITEMS:
                preview.append(struct.unpack(fmt, raw)[0])
    return {
        "type": "array",
        "item_type": item_definition[0],
        "count": count,
        "sha256_of_encoded_items": digest.hexdigest(),
        "preview": preview,
    }


def metadata_value(reader: Reader, type_id: int):
    if type_id == 8:
        return reader.string()
    if type_id == 9:
        return array_value(reader)
    return scalar(reader, type_id)


def inspect(stream):
    reader = Reader(stream)
    if reader.exact(4) != b"GGUF":
        raise GgufError("magic is not GGUF")
    version = reader.unpack("<I")
    if version not in (2, 3):
        raise GgufError(f"unsupported GGUF version {version}; this bounded parser accepts v2/v3")
    tensor_count = reader.unpack("<Q")
    metadata_count = reader.unpack("<Q")
    if tensor_count > MAX_TENSORS:
        raise GgufError(f"tensor count {tensor_count} exceeds safe bound {MAX_TENSORS}")
    if metadata_count > MAX_METADATA:
        raise GgufError(f"metadata count {metadata_count} exceeds safe bound {MAX_METADATA}")

    metadata = {}
    metadata_types = {}
    for _ in range(metadata_count):
        key = reader.string()
        if not key or key in metadata:
            raise GgufError(f"empty or duplicate metadata key {key!r}")
        type_id = reader.unpack("<I")
        if type_id not in VALUE_TYPES:
            raise GgufError(f"unknown metadata type {type_id} for {key}")
        metadata[key] = metadata_value(reader, type_id)
        metadata_types[key] = VALUE_TYPES[type_id][0]

    parameter_count = 0
    tensor_types = Counter()
    for _ in range(tensor_count):
        reader.string()
        dimensions = reader.unpack("<I")
        if dimensions == 0 or dimensions > MAX_DIMS:
            raise GgufError(f"invalid tensor dimension count {dimensions}")
        elements = 1
        for _ in range(dimensions):
            dimension = reader.unpack("<Q")
            if dimension == 0 or dimension > MAX_PARAMETERS:
                raise GgufError(f"invalid tensor dimension {dimension}")
            elements *= dimension
            if elements > MAX_PARAMETERS:
                raise GgufError("tensor parameter count exceeds safe bound")
        type_id = reader.unpack("<I")
        reader.unpack("<Q")  # offset relative to the aligned tensor-data section
        parameter_count += elements
        if parameter_count > MAX_PARAMETERS:
            raise GgufError("model parameter count exceeds safe bound")
        tensor_types[TENSOR_TYPES.get(type_id, f"UNKNOWN_{type_id}")] += 1

    alignment = metadata.get("general.alignment", 32)
    if not isinstance(alignment, int) or alignment <= 0 or alignment > 1024 * 1024:
        raise GgufError(f"invalid general.alignment {alignment!r}")
    data_offset = ((reader.offset + alignment - 1) // alignment) * alignment
    architecture = metadata.get("general.architecture")
    context_keys = {
        key: value for key, value in metadata.items()
        if key.endswith(".context_length") or key == "context_length"
    }
    tokenizer = {key: value for key, value in metadata.items() if key.startswith("tokenizer.")}
    provenance = {
        key: value for key, value in metadata.items()
        if key.startswith("general.") or any(word in key.lower() for word in (
            "source", "license", "url", "repository", "base_model", "finetune"
        ))
    }
    file_type = metadata.get("general.file_type")
    return {
        "gguf_version": version,
        "header_and_descriptors_bytes": reader.offset,
        "tensor_data_offset": data_offset,
        "metadata_count": metadata_count,
        "tensor_count": tensor_count,
        "parameter_count": parameter_count,
        "model_family": architecture,
        "embedded_model_name": metadata.get("general.name"),
        "architecture": architecture,
        "general_file_type": file_type,
        "general_file_type_label": FILE_TYPES.get(file_type, f"UNKNOWN_{file_type}" if file_type is not None else None),
        "tensor_type_counts": dict(sorted(tensor_types.items())),
        "context_length_fields": context_keys,
        "tokenizer_and_chat_template": tokenizer,
        "metadata_provenance": provenance,
        "metadata_types": metadata_types,
        "all_metadata": metadata,
    }


def sha256_file(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as stream:
        while True:
            chunk = stream.read(4 * 1024 * 1024)
            if not chunk:
                return digest.hexdigest()
            digest.update(chunk)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", help="GGUF path, or - to read a streamed header/descriptors from stdin")
    parser.add_argument("--sha256", action="store_true", help="hash the complete regular file")
    args = parser.parse_args(argv)
    if args.path == "-":
        if args.sha256:
            parser.error("--sha256 requires a regular file path")
        result = inspect(sys.stdin.buffer)
        result["file"] = "stdin"
        result["file_size_bytes"] = None
        result["sha256"] = None
    else:
        stat = os.stat(args.path)
        if not os.path.isfile(args.path):
            parser.error("path is not a regular file")
        with open(args.path, "rb") as stream:
            result = inspect(stream)
        result["file"] = os.path.abspath(args.path)
        result["file_size_bytes"] = stat.st_size
        result["sha256"] = sha256_file(args.path) if args.sha256 else None
    json.dump(result, sys.stdout, indent=2, sort_keys=True, ensure_ascii=False)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (GgufError, OSError) as error:
        print(f"GGUF inspection failed: {error}", file=sys.stderr)
        raise SystemExit(2)
