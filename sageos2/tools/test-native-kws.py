#!/usr/bin/env python3
"""Run real packaged KWS models, not a mocked Android service.

The former mobile export fails its first decode with /downsample/Reshape_1.
Linux native inference is a release gate, not proof of Android JNI or mic health.
"""
import argparse
from pathlib import Path
import wave

import numpy as np
import sherpa_onnx

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--assets', type=Path, default=root / 'app/src/main/assets/sherpa-kws')
parser.add_argument('--fixtures', type=Path, default=root / '.deps/kws/test_wavs')
args = parser.parse_args()
kws = sherpa_onnx.KeywordSpotter(
    tokens=str(args.assets / 'tokens.txt'),
    encoder=str(args.assets / 'encoder.int8.onnx'),
    decoder=str(args.assets / 'decoder.onnx'),
    joiner=str(args.assets / 'joiner.int8.onnx'),
    keywords_file=str(args.assets / 'keywords.txt'),
    keywords_score=1.5, keywords_threshold=0.25, num_trailing_blanks=2,
)

def decode(stream, samples):
    hits = []
    for offset in range(0, len(samples), 1600):
        stream.accept_waveform(16000, samples[offset:offset + 1600])
        while kws.is_ready(stream):
            kws.decode_stream(stream)
            result = kws.get_result(stream)
            if result:
                hits.append(result)
                kws.reset_stream(stream)
    return hits

# Exercise repeated creation, audio decode, results, and native reset.
for _ in range(3):
    stream = kws.create_stream('▁S AGE :1.5 #0.25 @sage/▁S AGE ▁G LI T CH :1.5 #0.25 @sage_glitch')
    assert not decode(stream, np.zeros(160000, dtype=np.float32)), 'False wake on silence'
    kws.reset_stream(stream)
    assert not decode(stream, np.zeros(16000, dtype=np.float32)), 'False wake after reset'

# Positive control: prove a real phrase is detected, not merely absence of crash.
with wave.open(str(args.fixtures / '0.wav')) as audio:
    assert (audio.getframerate(), audio.getnchannels(), audio.getsampwidth()) == (16000, 1, 2)
    samples = np.frombuffer(audio.readframes(audio.getnframes()), dtype='<i2').astype(np.float32) / 32768
stream = kws.create_stream('▁ L IGHT ▁UP :1.5 #0.25 @fixture_light_up')
hits = decode(stream, np.concatenate([samples, np.zeros(16000, dtype=np.float32)]))
assert 'fixture_light_up' in hits, f'Missed known spoken phrase: {hits}'
print('PASS: native KWS silence, repeated streams, reset, and recorded spoken phrase')
