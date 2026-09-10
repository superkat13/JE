"""Static recovery contracts for failures that JVM tests cannot instantiate safely."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


class SageOs2RecoveryContractTest(unittest.TestCase):
    def read(self, relative: str) -> str:
        return (ROOT / relative).read_text(encoding="utf-8")

    def test_preserved_sherpa_service_is_private_and_real(self) -> None:
        manifest = self.read("sageos2/app/src/main/AndroidManifest.xml")
        service = self.read(
            "sageos2/app/src/main/java/com/pineapple/sage/"
            "SageSherpaRecognitionService.java"
        )
        old_stub_home = self.read(
            "sageos2/app/src/main/java/com/pineapple/sage/"
            "VoiceInteractionComponents.kt"
        )

        self.assertIn(
            'android:name="com.pineapple.sage.SageSherpaRecognitionService" '
            'android:exported="false"',
            manifest,
        )
        self.assertIn("OnlineRecognizerKt.getModelConfig(10)", service)
        self.assertIn("recognizer.createStream", service)
        self.assertIn("stream.acceptWaveform", service)
        self.assertIn("MAX_UTTERANCE_MS = 15_000L", service)
        self.assertNotIn("class SageSherpaRecognitionService", old_stub_home)

    def test_sherpa_readiness_targets_preserved_owner_model(self) -> None:
        state = self.read(
            "sageos2/app/src/main/java/com/pineapple/sage/"
            "SageSpeechBackendState.java"
        )
        self.assertIn("sherpa-onnx-streaming-zipformer-en-20M-2023-02-17", state)
        for name, size in {
            "tokens.txt": "5_048L",
            "encoder-epoch-99-avg-1.int8.onnx": "42_845_182L",
            "decoder-epoch-99-avg-1.onnx": "2_092_272L",
            "joiner-epoch-99-avg-1.int8.onnx": "259_572L",
        }.items():
            self.assertIn(f'exactFile(dir, "{name}", {size})', state)
        self.assertNotIn("delete", state.lower())

    def test_fast_route_cannot_outrun_fast_parser(self) -> None:
        router = self.read(
            "sageos2/app/src/main/java/com/pineapple/sageos2/core/"
            "SageCommandRouter.kt"
        )
        self.assertIn("fastCommands.parse(normalized) != null", router)
        self.assertNotIn("fastPrefixes", router)

    def test_brain_prompt_does_not_assume_a_model_family(self) -> None:
        prompt = self.read(
            "sageos2/app/src/main/java/com/pineapple/sageos2/brain/"
            "BrainPromptBudget.kt"
        )
        native = self.read("sageos2/app/src/main/cpp/sage_brain.cpp")
        combined = (prompt + native).lower()
        self.assertNotIn("qwen", combined)
        self.assertNotIn("mistral", combined)
        self.assertNotIn("deepseek", combined)


if __name__ == "__main__":
    unittest.main()
