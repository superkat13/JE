#!/usr/bin/env python3
"""Close external UI turns deterministically and classify reopen traces honestly."""

from pathlib import Path
import sys


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one anchor, found {count}: {old[:100]!r}")
    path.write_text(text.replace(old, new), encoding="utf-8")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: external_action_lifecycle_v1_29.py <reconstructed-source>")
    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"

    engine = java / "SageCommandEngine.java"
    replace_once(engine, '''        public final boolean matched;
        /** Close natural conversation after an external media action. */
        public final boolean freshWakeAfterAction;

        public Result(String message) {
            this(message, false, true, true, false);
        }

        public Result(String message, boolean stopListening) {
            this(message, stopListening, true, true, false);
        }

        public Result(String message, boolean stopListening, boolean speak) {
            this(message, stopListening, speak, true, false);
        }

        private Result(
                String message,
                boolean stopListening,
                boolean speak,
                boolean matched,
                boolean freshWakeAfterAction
        ) {
            this.message = message;
            this.stopListening = stopListening;
            this.speak = speak;
            this.matched = matched;
            this.freshWakeAfterAction = freshWakeAfterAction;
        }

        public static Result quiet(String message) {
            return new Result(message, false, false, true, false);
        }

        public static Result media(String message) {
            return new Result(message, false, true, true, true);
        }

        public static Result quietMedia(String message) {
            return new Result(message, false, false, true, true);
        }

        public static Result unmatched(String message) {
            return new Result(message, false, true, false, false);
        }
''', '''        public final boolean matched;
        /** Non-empty when the completed action must end this conversation turn. */
        public final String actionBoundary;
        public final boolean freshWakeAfterAction;

        public Result(String message) {
            this(message, false, true, true, "");
        }

        public Result(String message, boolean stopListening) {
            this(message, stopListening, true, true, "");
        }

        public Result(String message, boolean stopListening, boolean speak) {
            this(message, stopListening, speak, true, "");
        }

        private Result(
                String message,
                boolean stopListening,
                boolean speak,
                boolean matched,
                String actionBoundary
        ) {
            this.message = message;
            this.stopListening = stopListening;
            this.speak = speak;
            this.matched = matched;
            this.actionBoundary = actionBoundary == null ? "" : actionBoundary;
            this.freshWakeAfterAction = !this.actionBoundary.isEmpty();
        }

        public static Result quiet(String message) {
            return new Result(message, false, false, true, "");
        }

        public static Result media(String message) {
            return new Result(message, false, true, true, "media");
        }

        public static Result quietMedia(String message) {
            return new Result(message, false, false, true, "media");
        }

        public static Result external(String message) {
            return new Result(message, false, true, true, "external_ui");
        }

        public static Result quietExternal(String message) {
            return new Result(message, false, false, true, "external_ui");
        }

        public static Result unmatched(String message) {
            return new Result(message, false, true, false, "");
        }
''')

    replace_once(engine, '''                return SageAccessibilityService.isMediaAppForeground()
                        ? Result.quietMedia("Opening number " + numberedChoice + ".")
                        : Result.quiet("Opening number " + numberedChoice + ".");
''', '''                return Result.quietExternal("Opening number " + numberedChoice + ".");
''')
    replace_once(engine, '''                result = verified ? (SageAccessibilityService.isMediaAppForeground()
                        ? Result.quietMedia("Opening the second item.")
                        : Result.quiet("Opening the second item."))
''', '''                result = verified ? Result.quietExternal("Opening the second item.")
''')
    replace_once(engine, '''        return new Result("Opening " + adobe.label + ".");
''', '''        return Result.external("Opening " + adobe.label + ".");
''')
    replace_once(engine, '''            return new Result("Opening the selected file in " + adobe.label + ".");
''', '''            return Result.external("Opening the selected file in " + adobe.label + ".");
''')
    replace_once(engine, '''        if (SageAccessibilityService.tapIndexedItem(number, type)) {
            return SageAccessibilityService.isMediaAppForeground()
                    ? Result.quietMedia("Opening number " + number + ".")
                    : Result.quiet("Opening number " + number + ".");
        }
''', '''        String sourcePackage = SageAccessibilityService.activePackageName();
        if (SageAccessibilityService.tapIndexedItem(number, type)) {
            SageDiagnostics.appendEvent(context, "ACTION DISPATCH",
                    "type=ordinal_tap source_package=" + packageOrUnavailable(sourcePackage)
                            + " ordinal=" + number + " fresh_wake_required=true");
            return Result.quietExternal("Opening number " + number + ".");
        }
''')
    replace_once(engine, '''    private Result globalAction(boolean success, String successMessage) {
        return success
                ? Result.quiet(successMessage)
                : new Result("Tablet control is not enabled. Open Sage and tap Accessibility control once.");
    }
''', '''    private Result globalAction(boolean success, String successMessage) {
        if (success) {
            SageDiagnostics.appendEvent(context, "ACTION DISPATCH",
                    "type=global_action fresh_wake_required=true result=" + successMessage);
            return Result.quietExternal(successMessage);
        }
        return new Result("Tablet control is not enabled. Open Sage and tap Accessibility control once.");
    }
''')
    replace_once(engine, '''    private Result tapText(String text) {
        String target = clean(text);
        if (target.isEmpty()) {
            return new Result("Tap what?");
        }
        return SageAccessibilityService.tapText(target)
                ? (SageAccessibilityService.isMediaAppForeground()
                ? Result.quietMedia("Tapped " + target + ".")
                : Result.quiet("Tapped " + target + "."))
                : new Result("I could not find a visible button or word named " + target + ". Say show numbers if you want me to label the clickable things.");
    }
''', '''    private Result tapText(String text) {
        String target = clean(text);
        if (target.isEmpty()) {
            return new Result("Tap what?");
        }
        String sourcePackage = SageAccessibilityService.activePackageName();
        if (!SageAccessibilityService.tapText(target)) {
            return new Result("I could not find a visible button or word named " + target + ". Say show numbers if you want me to label the clickable things.");
        }
        SageDiagnostics.appendEvent(context, "ACTION DISPATCH",
                "type=semantic_tap source_package=" + packageOrUnavailable(sourcePackage)
                        + " target=" + target + " fresh_wake_required=true");
        return Result.quietExternal("Tapped " + target + ".");
    }
''')
    replace_once(engine, '''        return SageAccessibilityService.typeText(value)
                ? new Result("Typed it.")
                : new Result("Select a text box first, and make sure Sage tablet control is enabled.");
''', '''        return SageAccessibilityService.typeText(value)
                ? Result.external("Typed it.")
                : new Result("Select a text box first, and make sure Sage tablet control is enabled.");
''')
    replace_once(engine, '''            return new Result("Opening " + target + ".");
''', '''            return Result.external("Opening " + target + ".");
''')
    replace_once(engine, '''            return new Result(message);
        } catch (ActivityNotFoundException error) {
            return new Result("I could not find a browser for that link.");
''', '''            return Result.external(message);
        } catch (ActivityNotFoundException error) {
            return new Result("I could not find a browser for that link.");
''')
    replace_once(engine, '''            context.startActivity(intent);
            return new Result(successMessage);
        } catch (ActivityNotFoundException error) {
            return new Result("That screen is not available on this tablet.");
''', '''            context.startActivity(intent);
            return Result.external(successMessage);
        } catch (ActivityNotFoundException error) {
            return new Result("That screen is not available on this tablet.");
''')
    replace_once(engine, '''    private boolean launchPackage(String packageName) {
''', '''    private static String packageOrUnavailable(String packageName) {
        return packageName == null || packageName.trim().isEmpty()
                ? "unavailable" : packageName.trim();
    }

    private boolean launchPackage(String packageName) {
''')

    service = java / "SageVoiceService.java"
    replace_once(service, '''        if (result.freshWakeAfterAction) {
            closeConversationWindow();
            commandEngine.cancelFollowUp();
            listenForCommandAfterSpeech = false;
            SageDiagnostics.appendEvent(this, "MEDIA BOUNDARY",
                    "fresh_wake_required=true result=" + result.message);
            if (mediaSessionBridge != null) {
                handler.postDelayed(() -> mediaSessionBridge.logSnapshot("after_media_action_350ms"), 350L);
                handler.postDelayed(() -> mediaSessionBridge.logSnapshot("after_media_action_1200ms"), 1200L);
            }
        }
''', '''        if (result.freshWakeAfterAction) {
            closeConversationWindow();
            commandEngine.cancelFollowUp();
            listenForCommandAfterSpeech = false;
            SageDiagnostics.appendEvent(this, "ACTION BOUNDARY",
                    "kind=" + result.actionBoundary
                            + " fresh_wake_required=true result=" + result.message);
            if ("media".equals(result.actionBoundary) && mediaSessionBridge != null) {
                handler.postDelayed(() -> mediaSessionBridge.logSnapshot("after_media_action_350ms"), 350L);
                handler.postDelayed(() -> mediaSessionBridge.logSnapshot("after_media_action_1200ms"), 1200L);
            }
        }
''')

    repair = java / "SageRepairManager.java"
    replace_once(repair, '''        String brain = (SageBrainHealth.snapshot(context) + " "
                + SageBrainHealth.lastBrainError(context)).toLowerCase(Locale.US);
''', '''        if (SageExternalActionPolicy.hasLifecycleViolation(events)) return CLASS_CODE;
        String brain = (SageBrainHealth.snapshot(context) + " "
                + SageBrainHealth.lastBrainError(context)).toLowerCase(Locale.US);
''')
    replace_once(repair, '''        packet.put("theories", theories(classification));
''', '''        packet.put("external_action_lifecycle_violation",
                SageExternalActionPolicy.hasLifecycleViolation(events));
        packet.put("theories", theories(classification, events));
''')
    replace_once(repair, '''        if (events.contains("MEDIA SNAPSHOT") || events.contains("MEDIA BOUNDARY"))
            values.put("Media boundary and playback diagnostics are present.");
        if (events.contains("TTS PROFILE")) values.put("Exact TTS profile diagnostics are present.");
''', '''        if (events.contains("MEDIA SNAPSHOT") || events.contains("MEDIA BOUNDARY")
                || events.contains("ACTION BOUNDARY"))
            values.put("Media and external-action boundary diagnostics are present.");
        if (SageExternalActionPolicy.hasLifecycleViolation(events))
            values.put("A verified external UI action was followed by an unauthorized conversation-listening reopen.");
        if (events.contains("TTS PROFILE")) values.put("Exact TTS profile diagnostics are present.");
''')
    replace_once(repair, '''    private static JSONArray theories(String classification) {
        JSONArray values = new JSONArray();
        if (CLASS_PERMISSION.equals(classification)) {
''', '''    private static JSONArray theories(String classification, String events) {
        JSONArray values = new JSONArray();
        if (SageExternalActionPolicy.hasLifecycleViolation(events)) {
            values.put("The external-action result path reopened the recognizer instead of requiring a fresh wake or push-to-talk.");
        } else if (CLASS_PERMISSION.equals(classification)) {
''')

    gradle = root / "app/build.gradle.kts"
    replace_once(gradle, '''    implementation("com.alphacephei:vosk-android:0.3.75@aar")
}
''', '''    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    testImplementation("junit:junit:4.13.2")
}
''')

    (java / "SageExternalActionPolicy.java").write_text(EXTERNAL_ACTION_POLICY, encoding="utf-8")
    test_java = root / "app/src/test/java/com/pineapple/sage"
    test_java.mkdir(parents=True, exist_ok=True)
    (test_java / "SageExternalActionPolicyTest.java").write_text(
        EXTERNAL_ACTION_POLICY_TEST, encoding="utf-8")


EXTERNAL_ACTION_POLICY = r'''package com.pineapple.sage;

import java.util.Locale;

/** Pure trace policy used by Self-Repair to distinguish lifecycle code defects. */
final class SageExternalActionPolicy {
    private static final String[] EXTERNAL_RESULTS = {
            "detail=tapped ", "detail=going home.", "detail=going back.",
            "detail=showing recent apps.", "detail=opening ",
            "detail=scrolling down.", "detail=scrolling up."
    };

    private SageExternalActionPolicy() { }

    static boolean hasLifecycleViolation(String events) {
        String lower = events == null ? "" : events.toLowerCase(Locale.US);
        int outcome = -1;
        for (String marker : EXTERNAL_RESULTS) {
            outcome = Math.max(outcome, lower.lastIndexOf(marker));
        }
        if (outcome < 0) return false;
        int listening = lower.indexOf("conversation open", outcome);
        int boundary = lower.indexOf("action boundary", outcome);
        return listening >= 0 && (boundary < 0 || listening < boundary);
    }
}
'''


EXTERNAL_ACTION_POLICY_TEST = r'''package com.pineapple.sage;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SageExternalActionPolicyTest {
    @Test
    public void detectsPhysicalTabletClickVideoReopenTrace() {
        String trace = "COORDINATOR RESULT detail=Tapped video.\n"
                + "STATE Conversation open — listening\n"
                + "COMMAND Command turn opened";
        assertTrue(SageExternalActionPolicy.hasLifecycleViolation(trace));
    }

    @Test
    public void acceptsFreshWakeBoundaryBeforeAnyLaterConversation() {
        String trace = "COORDINATOR RESULT detail=Tapped video.\n"
                + "ACTION BOUNDARY kind=external_ui fresh_wake_required=true\n"
                + "STATE Waiting for Sage — quiet wake listener\n"
                + "WAKE HEARD hey sage\n"
                + "STATE Conversation open — listening";
        assertFalse(SageExternalActionPolicy.hasLifecycleViolation(trace));
    }

    @Test
    public void ignoresOrdinaryConversationalFollowUp() {
        String trace = "COORDINATOR RESULT detail=Brain online.\n"
                + "STATE Conversation open — listening";
        assertFalse(SageExternalActionPolicy.hasLifecycleViolation(trace));
    }
}
'''


if __name__ == "__main__":
    main()
