#include "llama.h"
#include <iostream>
#include <stdexcept>
#include <memory>
#include <chrono>
#include <thread>
// Only error cases inject a decode failure. All ordinary cases use real llama.cpp.
static int fail_decode_at = 0;
static int decode_calls = 0;
// Check the actual token submitted to llama_decode at the first native continuation
// boundary. Merely checking that some text came back cannot detect a lost KV token.
static int expected_resume_token = -1;
static bool observed_resume_decode = false;
static bool resume_decode_matched = false;
static int checked_decode(llama_context* context, llama_batch batch) {
    if (expected_resume_token >= 0 && !observed_resume_decode) {
        observed_resume_decode = true;
        resume_decode_matched = batch.n_tokens == 1 &&
            batch.token != nullptr && batch.token[0] == expected_resume_token;
        expected_resume_token = -1;
    }
    if (fail_decode_at && ++decode_calls == fail_decode_at) return -1;
    return llama_decode(context, batch);
}
#define llama_decode checked_decode
#include "../app/src/main/cpp/sage_brain.cpp"
#undef llama_decode

static void check(bool ok, const char* message) {
    if (!ok) throw std::runtime_error(message);
}
int main(int argc, char** argv) {
    if (argc != 2) return 2;
    // Verify UTF-8 bridging without relying on the host JNI shim's byte-copy behavior.
    std::string complete, tail;
    const std::string pineapple = std::string("\xF0\x9F\x8D\x8D", 4);
    check(split_complete_utf8(std::string("Sage ") + pineapple.substr(0, 1), complete, tail),
          "UTF-8 first segment rejected");
    check(complete == "Sage " && tail == pineapple.substr(0, 1),
          "first UTF-8 segment exposed a partial codepoint");
    check(split_complete_utf8(tail + pineapple.substr(1, 2), complete, tail),
          "UTF-8 middle segment rejected");
    check(complete.empty() && tail == pineapple.substr(0, 3),
          "UTF-8 middle segment lost carry bytes");
    check(split_complete_utf8(tail + pineapple.substr(3) + "!", complete, tail),
          "UTF-8 final segment rejected");
    check(complete == pineapple + "!" && tail.empty(),
          "UTF-8 continuation did not reconstruct the whole character");
    check(!split_complete_utf8(std::string("\xC0", 1), complete, tail),
          "invalid UTF-8 overlong lead was accepted");
    JNIEnv env;
    std::string path(argv[1]);
    auto load = [&] { check(Java_com_pineapple_sage_SageBrainManager_nativeLoadModel(&env, nullptr, &path), "fixture load failed"); };
    long long id = 0;
    auto generate = [&](std::string system, std::string user, int budget = 48) {
        std::unique_ptr<std::string> out(Java_com_pineapple_sage_SageBrainManager_nativeGenerate(
            &env, nullptr, ++id, &system, &user, budget, JNI_TRUE));
        check(g_active_request_id == 0, "request ID not retired");
        return *out;
    };
    load();
    const std::string system = "Once upon a time", user = "there was a little girl who";
    const auto cold = generate(system, user);
    check(!cold.empty(), "empty cold response");
    check(g_last_generated_token_count == 48, "native bridge truncated requested 48 tokens");
    check(g_last_cached_prompt_tokens == 0, "cold turn reused stale state");
    check(g_last_finish_reason == FINISH_TOKEN_LIMIT, "fixture did not stop at token ceiling");
    check(g_continuation_available.load(), "token-limited generation lost continuation lease");
    const auto saved_resume_token = g_continuation_token.load();
    check(saved_resume_token != 0, "fixture has an unexpected zero continuation token");
    expected_resume_token = saved_resume_token;
    std::unique_ptr<std::string> resumed(Java_com_pineapple_sage_SageBrainManager_nativeContinueGeneration(
        &env, nullptr, id, 4, JNI_TRUE));
    check(observed_resume_decode, "continuation never decoded a token");
    check(resume_decode_matched, "continuation decoded the wrong token from the saved KV state");
    check(g_last_finish_reason != FINISH_DECODE_ERROR &&
          g_last_finish_reason != FINISH_INVALID_REQUEST, "native continuation failed");
    check(g_last_generated_token_count > 48 ||
          g_last_finish_reason == FINISH_END_OF_GENERATION, "continuation made no valid progress");
    // Repeat across a second segment, not just the first 48-token boundary.
    // A later pass must preserve its own pending sample token as well.
    if (g_last_finish_reason == FINISH_TOKEN_LIMIT) {
        const auto second_resume_token = g_continuation_token.load();
        expected_resume_token = second_resume_token;
        observed_resume_decode = false;
        resume_decode_matched = false;
        std::unique_ptr<std::string> second_resumed(
            Java_com_pineapple_sage_SageBrainManager_nativeContinueGeneration(
                &env, nullptr, id, 4, JNI_TRUE
            )
        );
        check(observed_resume_decode, "second continuation never decoded a token");
        check(resume_decode_matched, "second continuation decoded a stale or cleared token");
        check(g_last_finish_reason != FINISH_INVALID_REQUEST &&
              g_last_finish_reason != FINISH_DECODE_ERROR, "second native continuation failed");
    }
    const auto cold_ms = g_last_prompt_prefill_duration_ms.load();
    const auto warm = generate(system, user);
    check(warm == cold, "cached reply differs from cold reply");
    check(g_last_cached_prompt_tokens == g_last_prompt_token_count - 1, "identical prompt not reused");
    std::cout << "Repeated prompt: cached=" << g_last_cached_prompt_tokens << "/" << g_last_prompt_token_count
              << " prefill cold_ms=" << cold_ms << " warm_ms=" << g_last_prompt_prefill_duration_ms << '\n';
    const auto changed = generate(system, user + " lived in a forest");
    check(g_last_cached_prompt_tokens > 0, "shared exact prefix not reused");
    load();
    check(generate(system, user + " lived in a forest") == changed, "changed context contaminated by cache");
    const auto new_identity = generate("A completely different story", user);
    load();
    check(generate("A completely different story", user) == new_identity, "system context changed cached semantics");
    std::string long_context;
    for (int i=0;i<90;++i) long_context += "There was a little girl. ";
    check(!generate(long_context, user, 8).empty(), "chunked prefill failed");
    check(g_last_prompt_token_count > 256, "fixture missed chunk boundary");
    check(generate(system, std::string(20000, 'x')).empty(), "oversized prompt accepted");
    check(g_cached_prompt.empty(), "failure retained reusable state");
    check(!generate(system, user, 1).empty() && g_last_generated_token_count == 1, "one-token budget failed");
    check(g_last_cached_prompt_tokens == 0, "post-failure turn reused cache");
    fail_decode_at = 2; decode_calls = 0;
    check(generate(system, user).empty(), "decode failure returned partial success");
    check(!g_last_error.empty() && g_cached_prompt.empty(), "decode failure erased evidence or retained cache");
    fail_decode_at = 0;
    // Cancellation during genuine native prefill; no Android or model-output mock.
    load();
    const long long cancel_id = id + 1;
    std::thread cancel([&] {
        const auto until = std::chrono::steady_clock::now() + std::chrono::seconds(10);
        while (std::chrono::steady_clock::now() < until) {
            if (g_active_request_id == cancel_id && g_last_stage == 5) {
                Java_com_pineapple_sage_SageBrainManager_nativeCancelGeneration(&env, nullptr, cancel_id);
                return;
            }
            std::this_thread::yield();
        }
    });
    const auto cancelled = generate(long_context, user);
    cancel.join();
    check(cancelled.empty() && g_last_error.find("cancel") != std::string::npos, "cancellation failed");
    check(g_cached_prompt.empty(), "cancelled state reused");
    check(generate(system, user) == cold && g_last_cached_prompt_tokens == 0, "recovery changed reply");
    // Cancellation between two native segments must not be discarded just
    // because g_active_request_id has returned to zero.
    const auto before_handoff_cancel = generate(system, user);
    check(!before_handoff_cancel.empty() && g_last_finish_reason == FINISH_TOKEN_LIMIT,
          "fixture did not expose a continuation handoff for cancellation");
    check(g_active_request_id == 0 && g_continuation_available.load(),
          "fixture was not between native continuation segments");
    // A pending multibyte prefix also must not leak into the next owner request.
    g_pending_utf8_bytes = std::string("\xF0\x9F", 2);
    Java_com_pineapple_sage_SageBrainManager_nativeCancelGeneration(&env, nullptr, id);
    check(g_cancel_requested.load(), "cancellation was lost between segments");
    std::unique_ptr<std::string> cancelled_continuation(
        Java_com_pineapple_sage_SageBrainManager_nativeContinueGeneration(
            &env, nullptr, id, 4, JNI_TRUE
        )
    );
    check(cancelled_continuation->empty(), "cancelled continuation emitted text");
    check(g_last_finish_reason == FINISH_CANCELLED, "cancelled continuation did not report cancellation");
    check(g_cached_prompt.empty() && !g_continuation_available.load() &&
          g_pending_utf8_bytes.empty(),
          "cancelled continuation retained reusable cache or UTF-8 state");
    check(generate(system, user) == cold && g_last_cached_prompt_tokens == 0,
          "post-handoff cancellation contaminated the next owner turn");
    Java_com_pineapple_sage_SageBrainManager_nativeUnloadModel(&env,nullptr);
    check(g_cached_prompt.empty(), "unload retained prompt cache");
    std::cout << "PASS: real native generation, 48-token budget, two KV continuations, UTF-8 carry, handoff cancellation, cache equivalence, context change, chunking, failure, unload\n";
}
