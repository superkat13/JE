#include "llama.h"
#include <iostream>
#include <stdexcept>
#include <memory>
#include <chrono>
#include <thread>
// Only error cases inject a decode failure. All ordinary cases use real llama.cpp.
static int fail_decode_at = 0;
static int decode_calls = 0;
static int checked_decode(llama_context* context, llama_batch batch) {
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
    Java_com_pineapple_sage_SageBrainManager_nativeUnloadModel(&env,nullptr);
    check(g_cached_prompt.empty(), "unload retained prompt cache");
    std::cout << "PASS: real native generation, 48-token budget, cache equivalence, context change, chunking, failure, cancellation, unload\n";
}
