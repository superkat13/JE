#!/usr/bin/env python3
"""Export SentencePiece pieces/merge scores from bpe.model without protobuf dependencies."""
import struct
import sys
from pathlib import Path

def varint(data, pos):
    value = 0
    shift = 0
    while True:
        if pos >= len(data) or shift > 63:
            raise ValueError("invalid protobuf varint")
        b = data[pos]
        pos += 1
        value |= (b & 0x7f) << shift
        if b < 0x80:
            return value, pos
        shift += 7

def field(data, pos):
    key, pos = varint(data, pos)
    return key >> 3, key & 7, pos

def skip(data, pos, wire):
    if wire == 0:
        _, pos = varint(data, pos)
        return pos
    if wire == 1:
        return pos + 8
    if wire == 2:
        n, pos = varint(data, pos)
        return pos + n
    if wire == 5:
        return pos + 4
    raise ValueError(f"unsupported protobuf wire type {wire}")

def parse_piece(data):
    pos = 0
    piece = None
    score = 0.0
    kind = 1
    while pos < len(data):
        number, wire, pos = field(data, pos)
        if number == 1 and wire == 2:
            n, pos = varint(data, pos)
            piece = data[pos:pos+n].decode("utf-8")
            pos += n
        elif number == 2 and wire == 5:
            score = struct.unpack("<f", data[pos:pos+4])[0]
            pos += 4
        elif number == 3 and wire == 0:
            kind, pos = varint(data, pos)
        else:
            pos = skip(data, pos, wire)
    return piece, score, kind

def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: export-kws-bpe-vocab.py INPUT.model OUTPUT.tsv")
    raw = Path(sys.argv[1]).read_bytes()
    out = []
    pos = 0
    while pos < len(raw):
        number, wire, pos = field(raw, pos)
        if number == 1 and wire == 2:
            n, pos = varint(raw, pos)
            sub = raw[pos:pos+n]
            pos += n
            piece, score, kind = parse_piece(sub)
            if piece is not None:
                if "\t" in piece or "\n" in piece or "\r" in piece:
                    raise ValueError("unexpected control character in SentencePiece vocabulary")
                out.append(f"{piece}\t{score:.9g}\t{kind}")
        else:
            pos = skip(raw, pos, wire)
    if not out:
        raise ValueError("no SentencePiece vocabulary entries found")
    Path(sys.argv[2]).write_text("\n".join(out) + "\n", encoding="utf-8")
    print(f"Exported {len(out)} SentencePiece entries to {sys.argv[2]}")

if __name__ == "__main__":
    main()
