#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "llama.h"

#define LOG_TAG "SAGE-BRAIN"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

llama_model * g_model = nullptr;
llama_context * g_context = nullptr;
std::mutex g_mutex;
std::atomic<bool> g_cancel_requested{false};
std::atomic<long long> g_last_first_token_latency_ms{-1};
std::atomic<long long> g_last_generation_duration_ms{-1};
std::atomic<long long> g_last_prompt_prefill_duration_ms{-1};
std::atomic<int> g_last_prompt_token_count{0};
std::atomic<float> g_last_prompt_tokens_per_second{-1.0f};
std::atomic<int> g_last_generated_token_count{0};
std::atomic<long long> g_active_request_id{0};
std::atomic<int> g_last_stage{0};
std::string g_last_error = "Model not loaded";
bool g_backend_initialized = false;
// These ceilings are deliberately tuned for the owner's inherited Qwen3-1.7B-Q8 model on the
// VASOUN tablet. Larger values compiled successfully but made a real turn exceed the useful
// physical response window.
constexpr int kContextTokens = 2048;
constexpr int kMaximumResponseTokens = 24;

const char * stage_name(int stage) {
    switch (stage) {
        case 1: return "model_verification";
        case 2: return "model_load";
        case 3: return "context_creation";
        case 4: return "prompt_tokenization";
        case 5: return "prompt_prefill";
        case 6: return "sampling";
        case 7: return "first_token";
        case 8: return "generation";
        case 9: return "cancellation";
        case 10: return "complete";
        default: return "not_started";
    }
}

void set_error(const std::string & message) {
    g_last_error = message;
    LOGE("%s", message.c_str());
}

void release_model_locked() {
    if (g_context != nullptr) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
}

std::string from_java_string(JNIEnv * env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char * raw = env->GetStringUTFChars(value, nullptr);
    if (raw == nullptr) {
        return {};
    }
    std::string result(raw);
    env->ReleaseStringUTFChars(value, raw);
    return result;
}

jstring to_java_string(JNIEnv * env, const std::string & value) {
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(value.size()));
    if (bytes == nullptr) {
        return env->NewStringUTF("");
    }
    if (!value.empty()) {
        env->SetByteArrayRegion(
                bytes,
                0,
                static_cast<jsize>(value.size()),
                reinterpret_cast<const jbyte *>(value.data())
        );
    }
    jclass string_class = env->FindClass("java/lang/String");
    jmethodID constructor = env->GetMethodID(
            string_class,
            "<init>",
            "([BLjava/lang/String;)V"
    );
    jstring charset = env->NewStringUTF("UTF-8");
    jstring result = static_cast<jstring>(
            env->NewObject(string_class, constructor, bytes, charset)
    );
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(string_class);
    return result;
}

std::string token_piece(const llama_vocab * vocab, llama_token token) {
    std::string piece(64, '\0');
    int count = llama_token_to_piece(
            vocab,
            token,
            piece.data(),
            static_cast<int>(piece.size()),
            0,
            false
    );
    if (count < 0) {
        piece.resize(static_cast<size_t>(-count));
        count = llama_token_to_piece(
                vocab,
                token,
                piece.data(),
                static_cast<int>(piece.size()),
                0,
                false
        );
    }
    if (count <= 0) {
        return {};
    }
    piece.resize(static_cast<size_t>(count));
    return piece;
}

bool tokenize(
        const llama_vocab * vocab,
        const std::string & text,
        std::vector<llama_token> & tokens
) {
    int count = -llama_tokenize(
            vocab,
            text.c_str(),
            static_cast<int32_t>(text.size()),
            nullptr,
            0,
            true,
            true
    );
    if (count <= 0) {
        set_error("Prompt tokenization failed");
        return false;
    }
    tokens.resize(static_cast<size_t>(count));
    int actual = llama_tokenize(
            vocab,
            text.c_str(),
            static_cast<int32_t>(text.size()),
            tokens.data(),
            static_cast<int32_t>(tokens.size()),
            true,
            true
    );
    if (actual <= 0) {
        set_error("Prompt tokenization returned no tokens");
        return false;
    }
    tokens.resize(static_cast<size_t>(actual));
    return true;
}

std::string format_chat_prompt(
        const std::string & system_prompt,
        const std::string & user_prompt
) {
    const char * chat_template = llama_model_chat_template(g_model, nullptr);
    if (chat_template == nullptr) {
        return system_prompt + "\n\nUser: " + user_prompt + "\nAssistant:";
    }
    std::string effective_user_prompt = user_prompt;
    const std::string template_text(chat_template);
    const bool thinking_template = template_text.find("enable_thinking") != std::string::npos
            || template_text.find("<think>") != std::string::npos;
    const bool owner_selected_thinking = user_prompt.find("/think") != std::string::npos
            || user_prompt.find("/no_think") != std::string::npos;
    if (thinking_template && !owner_selected_thinking) {
        // The inherited Qwen3 model otherwise spends a short mobile response budget entirely on
        // hidden reasoning. Direct mode produces a complete owner-visible answer by default.
        effective_user_prompt += "\n/no_think";
    }
    llama_chat_message messages[] = {
            {"system", system_prompt.c_str()},
            {"user", effective_user_prompt.c_str()}
    };
    int32_t required = llama_chat_apply_template(
            chat_template,
            messages,
            2,
            true,
            nullptr,
            0
    );
    if (required <= 0) {
        return system_prompt + "\n\nUser: " + user_prompt + "\nAssistant:";
    }
    std::vector<char> buffer(static_cast<size_t>(required) + 1U, '\0');
    int32_t written = llama_chat_apply_template(
            chat_template,
            messages,
            2,
            true,
            buffer.data(),
            static_cast<int32_t>(buffer.size())
    );
    if (written <= 0) {
        return system_prompt + "\n\nUser: " + user_prompt + "\nAssistant:";
    }
    return std::string(buffer.data(), static_cast<size_t>(written));
}

}  // namespace

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_pineapple_sage_SageBrainManager_nativeLoadModel(
        JNIEnv * env,
        jclass,
        jstring path
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_last_stage.store(1, std::memory_order_release);
    std::string model_path = from_java_string(env, path);
    if (model_path.empty()) {
        set_error("Model path is empty");
        return JNI_FALSE;
    }

    if (!g_backend_initialized) {
        llama_log_set([](enum ggml_log_level level, const char * text, void *) {
            if (level >= GGML_LOG_LEVEL_ERROR) {
                LOGE("%s", text);
            }
        }, nullptr);
        llama_backend_init();
        g_backend_initialized = true;
    }

    release_model_locked();
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;

    g_last_stage.store(2, std::memory_order_release);
    LOGI("Loading local model: %s", model_path.c_str());
    g_model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (g_model == nullptr) {
        set_error("llama.cpp could not load that GGUF model");
        return JNI_FALSE;
    }

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = kContextTokens;
    context_params.n_batch = 512;
    context_params.n_ubatch = 256;
    unsigned int hardware_threads = std::thread::hardware_concurrency();
    int threads = static_cast<int>(std::max(2U, std::min(4U, hardware_threads)));
    context_params.n_threads = threads;
    context_params.n_threads_batch = threads;
    context_params.no_perf = true;
    context_params.abort_callback = [](void *) {
        return g_cancel_requested.load(std::memory_order_acquire);
    };
    context_params.abort_callback_data = nullptr;

    g_last_stage.store(3, std::memory_order_release);
    g_context = llama_init_from_model(g_model, context_params);
    if (g_context == nullptr) {
        release_model_locked();
        set_error("llama.cpp could not create Sage's local context");
        return JNI_FALSE;
    }

    g_last_error.clear();
    g_last_stage.store(10, std::memory_order_release);
    LOGI("Local model ready with %d threads", threads);
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_pineapple_sage_SageBrainManager_nativeGenerate(
        JNIEnv * env,
        jclass,
        jlong request_id,
        jstring system_prompt_value,
        jstring user_prompt_value,
        jint requested_tokens,
        jboolean deterministic
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_last_first_token_latency_ms.store(-1, std::memory_order_release);
    g_last_generation_duration_ms.store(-1, std::memory_order_release);
    g_last_prompt_prefill_duration_ms.store(-1, std::memory_order_release);
    g_last_prompt_token_count.store(0, std::memory_order_release);
    g_last_prompt_tokens_per_second.store(-1.0f, std::memory_order_release);
    g_last_generated_token_count.store(0, std::memory_order_release);
    g_last_error.clear();
    g_last_stage.store(4, std::memory_order_release);
    const long long active_request_id = static_cast<long long>(request_id);
    g_active_request_id.store(active_request_id, std::memory_order_release);
    const auto generation_start = std::chrono::steady_clock::now();
    if (g_model == nullptr || g_context == nullptr) {
        set_error("Sage Brain was asked before its model loaded");
        return to_java_string(env, "");
    }

    std::string system_prompt = from_java_string(env, system_prompt_value);
    std::string user_prompt = from_java_string(env, user_prompt_value);
    if (system_prompt.empty() || user_prompt.empty()) {
        set_error("Brain prompt was empty");
        return to_java_string(env, "");
    }

    std::string prompt = format_chat_prompt(system_prompt, user_prompt);
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    if (vocab == nullptr) {
        set_error("Model vocabulary is unavailable");
        return to_java_string(env, "");
    }

    std::vector<llama_token> prompt_tokens;
    if (!tokenize(vocab, prompt, prompt_tokens)) {
        return to_java_string(env, "");
    }
    g_last_prompt_token_count.store(static_cast<int>(prompt_tokens.size()), std::memory_order_release);

    int max_tokens = std::max(
            1,
            std::min(kMaximumResponseTokens, static_cast<int>(requested_tokens))
    );
    if (prompt_tokens.size() + static_cast<size_t>(max_tokens) + 8U
            > static_cast<size_t>(llama_n_ctx(g_context))) {
        set_error("Brain prompt exceeded Sage's local context window");
        return to_java_string(env, "");
    }

    g_last_stage.store(5, std::memory_order_release);
    llama_memory_clear(llama_get_memory(g_context), true);
    llama_batch batch = llama_batch_get_one(
            prompt_tokens.data(),
            static_cast<int32_t>(prompt_tokens.size())
    );

    llama_sampler * sampler;
    if (deterministic == JNI_TRUE) {
        sampler = llama_sampler_init_greedy();
    } else {
        sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(20));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.8f, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    g_cancel_requested.store(false, std::memory_order_release);
    bool cancelled = false;
    bool prompt_prefilled = false;
    auto generation_only_start = generation_start;
    int generated_token_count = 0;
    std::string output;
    for (int generated = 0; generated < max_tokens; ++generated) {
        if (g_cancel_requested.load(std::memory_order_acquire)) {
            cancelled = true;
            break;
        }
        int decode_result = llama_decode(g_context, batch);
        if (!prompt_prefilled) {
            const auto prefill_finished = std::chrono::steady_clock::now();
            const auto prefill_duration = std::chrono::duration_cast<std::chrono::milliseconds>(
                    prefill_finished - generation_start
            ).count();
            g_last_prompt_prefill_duration_ms.store(
                    std::max<long long>(0, prefill_duration), std::memory_order_release
            );
            if (prefill_duration > 0) {
                g_last_prompt_tokens_per_second.store(
                        static_cast<float>(prompt_tokens.size()) * 1000.0f
                                / static_cast<float>(prefill_duration),
                        std::memory_order_release
                );
            }
            generation_only_start = prefill_finished;
            prompt_prefilled = true;
        }
        if (decode_result != 0) {
            if (decode_result == 2
                    && g_cancel_requested.load(std::memory_order_acquire)) {
                cancelled = true;
                break;
            }
            set_error("llama_decode failed with code " + std::to_string(decode_result));
            break;
        }

        if (g_cancel_requested.load(std::memory_order_acquire)) {
            cancelled = true;
            break;
        }
        g_last_stage.store(6, std::memory_order_release);
        llama_token token = llama_sampler_sample(sampler, g_context, -1);
        if (llama_vocab_is_eog(vocab, token)) {
            break;
        }

        ++generated_token_count;
        g_last_generated_token_count.store(generated_token_count, std::memory_order_release);
        if (generated_token_count == 1) {
            g_last_stage.store(7, std::memory_order_release);
            const auto first_token_at = std::chrono::steady_clock::now();
            const auto latency = std::chrono::duration_cast<std::chrono::milliseconds>(
                    first_token_at - generation_start
            ).count();
            g_last_first_token_latency_ms.store(
                    std::max<long long>(0, latency),
                    std::memory_order_release
            );
        }

        std::string piece = token_piece(vocab, token);
        if (!piece.empty()) {
            output += piece;
        }
        g_last_stage.store(8, std::memory_order_release);
        batch = llama_batch_get_one(&token, 1);

        if (output.size() > 2048U) {
            break;
        }
    }

    llama_sampler_free(sampler);
    const auto generation_finished = std::chrono::steady_clock::now();
    const auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(
            generation_finished - generation_only_start
    ).count();
    g_last_generation_duration_ms.store(
            std::max<long long>(1, duration),
            std::memory_order_release
    );
    g_last_generated_token_count.store(generated_token_count, std::memory_order_release);
    if (cancelled) {
        g_last_stage.store(9, std::memory_order_release);
        llama_memory_clear(llama_get_memory(g_context), true);
        set_error("Brain request cancelled");
        return to_java_string(env, "");
    }
    if (output.empty()) {
        set_error("The local model generated no text");
        return to_java_string(env, "");
    }
    g_last_error.clear();
    g_last_stage.store(10, std::memory_order_release);
    g_active_request_id.store(0, std::memory_order_release);
    return to_java_string(env, output);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_pineapple_sage_SageBrainManager_nativeLastStage(JNIEnv * env, jclass) {
    return env->NewStringUTF(stage_name(g_last_stage.load(std::memory_order_acquire)));
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_pineapple_sage_SageBrainManager_nativeLastFirstTokenLatencyMs(JNIEnv *, jclass) {
    return static_cast<jlong>(
            g_last_first_token_latency_ms.load(std::memory_order_acquire)
    );
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_pineapple_sage_SageBrainManager_nativeLastGenerationDurationMs(JNIEnv *, jclass) {
    return static_cast<jlong>(
            g_last_generation_duration_ms.load(std::memory_order_acquire)
    );
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_pineapple_sage_SageBrainManager_nativeLastPromptPrefillDurationMs(JNIEnv *, jclass) {
    return static_cast<jlong>(
            g_last_prompt_prefill_duration_ms.load(std::memory_order_acquire)
    );
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_pineapple_sage_SageBrainManager_nativeLastPromptTokenCount(JNIEnv *, jclass) {
    return static_cast<jint>(g_last_prompt_token_count.load(std::memory_order_acquire));
}

extern "C"
JNIEXPORT jfloat JNICALL
Java_com_pineapple_sage_SageBrainManager_nativeLastPromptTokensPerSecond(JNIEnv *, jclass) {
    return static_cast<jfloat>(g_last_prompt_tokens_per_second.load(std::memory_order_acquire));
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_pineapple_sage_SageBrainManager_nativeLastGeneratedTokenCount(JNIEnv *, jclass) {
    return static_cast<jint>(
            g_last_generated_token_count.load(std::memory_order_acquire)
    );
}

extern "C"
JNIEXPORT void JNICALL
Java_com_pineapple_sage_SageBrainManager_nativeCancelGeneration(JNIEnv *, jclass, jlong request_id) {
    if (static_cast<long long>(request_id) <= 0
            || g_active_request_id.load(std::memory_order_acquire)
                    != static_cast<long long>(request_id)) return;
    g_cancel_requested.store(true, std::memory_order_release);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_pineapple_sage_SageBrainManager_nativeUnloadModel(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    release_model_locked();
    g_last_error = "Model unloaded";
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_pineapple_sage_SageBrainManager_nativeLastError(JNIEnv * env, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return to_java_string(env, g_last_error);
}
