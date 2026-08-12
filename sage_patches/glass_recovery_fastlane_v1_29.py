#!/usr/bin/env python3
"""Glass-recovery checkpoint: recover transient command STT failures and bypass Brain for bounded arithmetic.

Additive only. This patch does not replace Sage's wake listener, conversation state machine,
media/echo boundaries, authorization model, Brain, or existing command routes.
"""
from pathlib import Path
import sys


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


FAST_PATH = r'''package com.pineapple.sage;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded deterministic answers that should never pay the local-LLM latency tax. */
final class SageFastPath {
    private static final Pattern BINARY = Pattern.compile(
            "^([+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+))\\s*([+\\-*/])\\s*([+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+))$");
    private static final Pattern PERCENT_OF = Pattern.compile(
            "^([+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+))\\s*%\\s*of\\s*([+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+))$");

    private SageFastPath() {}

    static String answer(String spoken) {
        String expression = normalize(spoken);
        if (expression.isEmpty()) return null;

        Matcher percent = PERCENT_OF.matcher(expression);
        if (percent.matches()) {
            BigDecimal rate = decimal(percent.group(1));
            BigDecimal base = decimal(percent.group(2));
            if (rate == null || base == null) return null;
            return format(base.multiply(rate).divide(new BigDecimal("100"), MathContext.DECIMAL64));
        }

        Matcher binary = BINARY.matcher(expression);
        if (!binary.matches()) return null;
        BigDecimal left = decimal(binary.group(1));
        BigDecimal right = decimal(binary.group(3));
        if (left == null || right == null) return null;
        String op = binary.group(2);
        BigDecimal result;
        switch (op) {
            case "+": result = left.add(right); break;
            case "-": result = left.subtract(right); break;
            case "*": result = left.multiply(right); break;
            case "/":
                if (right.compareTo(BigDecimal.ZERO) == 0) return "I cannot divide by zero.";
                result = left.divide(right, MathContext.DECIMAL64);
                break;
            default: return null;
        }
        return format(result);
    }

    private static String normalize(String spoken) {
        if (spoken == null) return "";
        String value = spoken.toLowerCase(Locale.US).trim().replace(",", "");
        value = value.replaceFirst("^(what is|what's|calculate|compute|work out)\\s+", "");
        value = value.replace("multiplied by", "*")
                .replace("times", "*")
                .replace(" x ", " * ")
                .replace("divided by", "/")
                .replace("over", "/")
                .replace("plus", "+")
                .replace("minus", "-")
                .replace("percent", "%");
        return value.trim().replaceAll("\\s+", " ");
    }

    private static BigDecimal decimal(String value) {
        try { return new BigDecimal(value); }
        catch (RuntimeException ignored) { return null; }
    }

    private static String format(BigDecimal value) {
        if (value == null) return null;
        BigDecimal clean = value.stripTrailingZeros();
        if (clean.scale() < 0) clean = clean.setScale(0);
        return clean.toPlainString();
    }
}
'''


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: glass_recovery_fastlane_v1_29.py <reconstructed-source>")
    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"
    voice = java / "SageVoiceService.java"
    command = java / "SageCommandEngine.java"
    if not voice.is_file() or not command.is_file():
        raise SystemExit("glass recovery requires reconstructed Sage 1.29 source")

    (java / "SageFastPath.java").write_text(FAST_PATH, encoding="utf-8")

    replace_once(
        command,
        '''        raw = stripWakeAndConversation(raw);\n        lower = normalize(raw);\n        SageSurpriseManager.Outcome surprise=SageSurpriseManager.execute(context,raw);''',
        '''        raw = stripWakeAndConversation(raw);\n        lower = normalize(raw);\n        String fastAnswer = SageFastPath.answer(raw);\n        if (fastAnswer != null) {\n            preferences.edit().putString("last_heard", raw).apply();\n            SageDiagnostics.appendEvent(context, "FAST PATH",\n                    "route=deterministic_math brain_bypassed=true");\n            return new Result(fastAnswer);\n        }\n        SageSurpriseManager.Outcome surprise=SageSurpriseManager.execute(context,raw);''',
        "deterministic arithmetic before Brain/surprise routing",
    )

    replace_once(
        voice,
        '''    private static final long COMMAND_BUSY_RETRY_MS = 1400L;\n    private static final long BRAIN_LOAD_TIMEOUT_MS = 15000L;''',
        '''    private static final long COMMAND_BUSY_RETRY_MS = 1400L;\n    private static final long COMMAND_NETWORK_RETRY_MS = 650L;\n    private static final long BRAIN_LOAD_TIMEOUT_MS = 15000L;''',
        "command network retry delay",
    )
    replace_once(
        voice,
        '''    private static final int MAX_COMMAND_BUSY_RETRIES = 1;\n    private static final int MAX_COMMAND_QUALITY_RETRIES = 1;''',
        '''    private static final int MAX_COMMAND_BUSY_RETRIES = 1;\n    private static final int MAX_COMMAND_NETWORK_RETRIES = 1;\n    private static final int MAX_COMMAND_QUALITY_RETRIES = 1;''',
        "command network retry budget",
    )
    replace_once(
        voice,
        '''    private int commandBusyRetries;\n    private int commandQualityRetries;''',
        '''    private int commandBusyRetries;\n    private int commandNetworkRetries;\n    private int commandQualityRetries;''',
        "command network retry state",
    )

    network_recovery = '''                    if ((error == SpeechRecognizer.ERROR_NETWORK\n                            || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT)\n                            && commandNetworkRetries < MAX_COMMAND_NETWORK_RETRIES\n                            && !stopRequested\n                            && !speaking\n                            && isConversationOpen()) {\n                        commandNetworkRetries++;\n                        captureAuthorizedByWakeOrPushToTalk = authorizedCapture;\n                        SageDiagnostics.appendEvent(SageVoiceService.this,\n                                "COMMAND STT RECOVERY",\n                                "error=" + error + " retry=" + commandNetworkRetries\n                                        + " authorization_preserved=true partial_executed=false");\n                        broadcastStatus("Voice service hiccup — retrying once");\n                        updateNotification("Voice typing hiccup — retrying once");\n                        startCommandRecognition(COMMAND_NETWORK_RETRY_MS, true);\n                        return;\n                    }\n'''
    replace_once(
        voice,
        '''                    if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY\n                            && commandBusyRetries < MAX_COMMAND_BUSY_RETRIES''',
        network_recovery + '''                    if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY\n                            && commandBusyRetries < MAX_COMMAND_BUSY_RETRIES''',
        "transient command STT network recovery",
    )
    replace_once(
        voice,
        '''                    if (!stopRequested && !speaking) {\n                        commandBusyRetries = 0;\n                        closeConversationWindow();''',
        '''                    if (!stopRequested && !speaking) {\n                        commandBusyRetries = 0;\n                        commandNetworkRetries = 0;\n                        closeConversationWindow();''',
        "network retry reset after terminal recognizer failure",
    )
    replace_once(
        voice,
        '''                    commandListening = false;\n                    ArrayList<String> choices =\n                            results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);''',
        '''                    commandListening = false;\n                    commandNetworkRetries = 0;\n                    ArrayList<String> choices =\n                            results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);''',
        "network retry reset after successful command recognition",
    )

    print("Applied glass recovery: one bounded command-STT network retry plus deterministic arithmetic fast lane")


if __name__ == "__main__":
    main()
