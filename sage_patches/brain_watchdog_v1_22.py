from pathlib import Path
import re
import sys

ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("sage_build")
JAVA = ROOT / "app/src/main/java/com/pineapple/sage"
CPP = ROOT / "app/src/main/cpp/sage_brain.cpp"


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1))


voice = JAVA / "SageVoiceService.java"
brain = JAVA / "SageBrainManager.java"

replace_once(
    voice,
    "    private static final long COMMAND_BUSY_RETRY_MS = 1400L;\n"
    "    private static final int MAX_COMMAND_BUSY_RETRIES = 1;",
    "    private static final long COMMAND_BUSY_RETRY_MS = 1400L;\n"
    "    private static final long BRAIN_REQUEST_TIMEOUT_MS = 30000L;\n"
    "    private static final int MAX_COMMAND_BUSY_RETRIES = 1;",
    "brain timeout constant",
)

replace_once(
    voice,
    "    private boolean translationInProgress;\n"
    "    private boolean brainInProgress;\n"
    "    private boolean mediaResponseFinishing;",
    "    private boolean translationInProgress;\n"
    "    private boolean brainInProgress;\n"
    "    private long brainRequestGeneration;\n"
    "    private boolean mediaResponseFinishing;",
    "brain request generation field",
)

old_method = '''    private void beginBrainRequest(
            String originalCommand,
            SageCommandEngine.Result fallbackResult
    ) {
        brainInProgress = true;
        stopWakeListening();
        destroyCommandRecognizer();
        if (isConversationOpen()) {
            extendConversationWindow();
        }
        SageDiagnostics.brainRequest(this, originalCommand);
        broadcastStatus(brainManager.isReady()
                ? "Sage Brain is thinking"
                : "Loading Sage Brain");
        updateNotification("Sage Brain is thinking");
        brainManager.askAsync(originalCommand, new SageBrainManager.ReplyCallback() {
            @Override
            public void onStatus(String status) {
                if (stopRequested) {
                    return;
                }
                broadcastStatus(status);
                updateNotification(status);
                if (isConversationOpen()) {
                    extendConversationWindow();
                }
            }

            @Override
            public void onReply(SageBrainManager.Reply reply) {
                if (stopRequested) {
                    brainInProgress = false;
                    return;
                }
                brainInProgress = false;
                if (reply.action) {
                    SageCommandEngine.Result interpreted = commandEngine.execute(reply.text);
                    if (interpreted.matched) {
                        SageDiagnostics.appendEvent(
                                SageVoiceService.this,
                                "BRAIN ACTION",
                                reply.text
                        );
                        deliverCommandResult(interpreted);
                        return;
                    }
                    SageDiagnostics.recordError(
                            SageVoiceService.this,
                            "Brain proposed an unmatched action: " + reply.text
                    );
                    deliverCommandResult(fallbackResult);
                    return;
                }
                deliverCommandResult(new SageCommandEngine.Result(reply.text));
            }

            @Override
            public void onError(String message) {
                if (stopRequested) {
                    brainInProgress = false;
                    return;
                }
                brainInProgress = false;
                SageDiagnostics.recordError(
                        SageVoiceService.this,
                        "Sage Brain request: " + message
                );
                deliverCommandResult(fallbackResult);
            }
        });
    }'''

new_method = '''    private void beginBrainRequest(
            String originalCommand,
            SageCommandEngine.Result fallbackResult
    ) {
        brainInProgress = true;
        final long requestGeneration = ++brainRequestGeneration;
        stopWakeListening();
        destroyCommandRecognizer();
        if (isConversationOpen()) {
            extendConversationWindow();
        }
        SageDiagnostics.brainRequest(this, originalCommand);
        broadcastStatus(brainManager.isReady()
                ? "Sage Brain is thinking"
                : "Loading Sage Brain");
        updateNotification("Sage Brain is thinking");

        final Runnable timeout = () -> {
            if (stopRequested
                    || !brainInProgress
                    || requestGeneration != brainRequestGeneration) {
                return;
            }
            brainInProgress = false;
            ++brainRequestGeneration;
            brainManager.cancelCurrentRequest();
            SageDiagnostics.recordError(
                    SageVoiceService.this,
                    "Sage Brain timed out after " + BRAIN_REQUEST_TIMEOUT_MS + " ms"
            );
            broadcastStatus("Sage Brain timed out — listening restored");
            updateNotification("Sage Brain timed out — listening restored");
            deliverCommandResult(fallbackResult);
        };
        handler.postDelayed(timeout, BRAIN_REQUEST_TIMEOUT_MS);

        brainManager.askAsync(originalCommand, new SageBrainManager.ReplyCallback() {
            private boolean finishRequest() {
                if (requestGeneration != brainRequestGeneration || !brainInProgress) {
                    return false;
                }
                handler.removeCallbacks(timeout);
                brainInProgress = false;
                return true;
            }

            @Override
            public void onStatus(String status) {
                if (stopRequested
                        || requestGeneration != brainRequestGeneration
                        || !brainInProgress) {
                    return;
                }
                broadcastStatus(status);
                updateNotification(status);
                if (isConversationOpen()) {
                    extendConversationWindow();
                }
            }

            @Override
            public void onReply(SageBrainManager.Reply reply) {
                if (!finishRequest() || stopRequested) {
                    return;
                }
                if (reply.action) {
                    SageCommandEngine.Result interpreted = commandEngine.execute(reply.text);
                    if (interpreted.matched) {
                        SageDiagnostics.appendEvent(
                                SageVoiceService.this,
                                "BRAIN ACTION",
                                reply.text
                        );
                        deliverCommandResult(interpreted);
                        return;
                    }
                    SageDiagnostics.recordError(
                            SageVoiceService.this,
                            "Brain proposed an unmatched action: " + reply.text
                    );
                    deliverCommandResult(fallbackResult);
                    return;
                }
                deliverCommandResult(new SageCommandEngine.Result(reply.text));
            }

            @Override
            public void onError(String message) {
                if (!finishRequest() || stopRequested) {
                    return;
                }
                SageDiagnostics.recordError(
                        SageVoiceService.this,
                        "Sage Brain request: " + message
                );
                deliverCommandResult(fallbackResult);
            }
        });
    }'''
replace_once(voice, old_method, new_method, "cancellable brain request watchdog")

replace_once(
    voice,
    '''        translationInProgress = false;
        handler.removeCallbacksAndMessages(null);''',
    '''        translationInProgress = false;
        brainInProgress = false;
        ++brainRequestGeneration;
        if (brainManager != null) {
            brainManager.cancelCurrentRequest();
        }
        handler.removeCallbacksAndMessages(null);''',
    "cancel brain on service destroy",
)

replace_once(
    brain,
    "    private static final int MAX_REPLY_TOKENS = 96;",
    "    private static final int MAX_REPLY_TOKENS = 48;",
    "shorter local-brain replies",
)

replace_once(
    brain,
    '''    public void askAsync(String userText, ReplyCallback callback) {''',
    '''    public void cancelCurrentRequest() {
        if (!NATIVE_AVAILABLE) {
            return;
        }
        try {
            nativeCancelGeneration();
        } catch (Throwable ignored) {
        }
    }

    public void askAsync(String userText, ReplyCallback callback) {''',
    "Java brain cancellation API",
)

replace_once(
    brain,
    '''    private static native String nativeGenerate(String systemPrompt, String userPrompt, int maxTokens);
    private static native void nativeUnloadModel();''',
    '''    private static native String nativeGenerate(String systemPrompt, String userPrompt, int maxTokens);
    private static native void nativeCancelGeneration();
    private static native void nativeUnloadModel();''',
    "native brain cancellation declaration",
)

replace_once(
    CPP,
    "#include <algorithm>\n#include <mutex>",
    "#include <algorithm>\n#include <atomic>\n#include <mutex>",
    "atomic include",
)

replace_once(
    CPP,
    '''std::mutex g_mutex;
std::string g_last_error = "Model not loaded";''',
    '''std::mutex g_mutex;
std::atomic<bool> g_cancel_requested{false};
std::string g_last_error = "Model not loaded";''',
    "native cancellation flag",
)

replace_once(
    CPP,
    '''    std::string output;
    for (int generated = 0; generated < max_tokens; ++generated) {
        int decode_result = llama_decode(g_context, batch);''',
    '''    g_cancel_requested.store(false, std::memory_order_release);
    bool cancelled = false;
    std::string output;
    for (int generated = 0; generated < max_tokens; ++generated) {
        if (g_cancel_requested.load(std::memory_order_acquire)) {
            cancelled = true;
            break;
        }
        int decode_result = llama_decode(g_context, batch);''',
    "native pre-decode cancellation",
)

replace_once(
    CPP,
    '''        llama_token token = llama_sampler_sample(sampler, g_context, -1);
        if (llama_vocab_is_eog(vocab, token)) {''',
    '''        if (g_cancel_requested.load(std::memory_order_acquire)) {
            cancelled = true;
            break;
        }
        llama_token token = llama_sampler_sample(sampler, g_context, -1);
        if (llama_vocab_is_eog(vocab, token)) {''',
    "native post-decode cancellation",
)

replace_once(
    CPP,
    '''    llama_sampler_free(sampler);
    if (output.empty()) {''',
    '''    llama_sampler_free(sampler);
    if (cancelled) {
        set_error("Brain request cancelled");
        return to_java_string(env, "");
    }
    if (output.empty()) {''',
    "native cancelled result",
)

replace_once(
    CPP,
    '''extern "C"
JNIEXPORT void JNICALL
Java_com_pineapple_sage_SageBrainManager_nativeUnloadModel(JNIEnv *, jclass) {''',
    '''extern "C"
JNIEXPORT void JNICALL
Java_com_pineapple_sage_SageBrainManager_nativeCancelGeneration(JNIEnv *, jclass) {
    g_cancel_requested.store(true, std::memory_order_release);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_pineapple_sage_SageBrainManager_nativeUnloadModel(JNIEnv *, jclass) {''',
    "native cancellation JNI function",
)

build_gradle = ROOT / "app/build.gradle.kts"
text = build_gradle.read_text()
text, code_count = re.subn(r'versionCode\s*=\s*31', 'versionCode = 32', text, count=1)
text, name_count = re.subn(r'versionName\s*=\s*"1\.21"', 'versionName = "1.22"', text, count=1)
if code_count != 1 or name_count != 1:
    raise SystemExit(f"Sage 1.22 version identity: code={code_count}, name={name_count}")
build_gradle.write_text(text)

for xml in (ROOT / "app/src/main").rglob("*.xml"):
    xml_text = xml.read_text()
    updated = re.sub(
        r"Sage Commander(?:\s+\d+\.\d+)?",
        "Sage Commander 1.22",
        xml_text,
    )
    if updated != xml_text:
        xml.write_text(updated)

print("Applied Sage 1.22 cancellable brain watchdog and permanent update identity.")
