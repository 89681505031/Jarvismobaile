#include <jni.h>
#include <android/log.h>
#include <llama.h>

#include <algorithm>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace {

constexpr const char * TAG = "JarvisBrain";
std::mutex g_mutex;
std::once_flag g_backend_once;
llama_model * g_model = nullptr;

void log_error(const char * message) {
    __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", message);
}

void init_backend_once() {
    std::call_once(g_backend_once, [] {
        llama_backend_init();
        __android_log_print(ANDROID_LOG_INFO, TAG, "llama backend initialized");
    });
}

void free_model_locked() {
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
}

std::string apply_model_chat_template(
        llama_model * model,
        const std::string & user_content) {
    if (model == nullptr || user_content.empty()) {
        return user_content;
    }

    const char * tmpl = llama_model_chat_template(model, nullptr);
    if (tmpl == nullptr || *tmpl == '\0') {
        return user_content;
    }

    llama_chat_message message {
        "user",
        user_content.c_str()
    };

    int32_t needed = llama_chat_apply_template(
        tmpl,
        &message,
        1,
        true,
        nullptr,
        0
    );
    if (needed <= 0 || needed > 512 * 1024) {
        return user_content;
    }

    std::vector<char> formatted(static_cast<size_t>(needed) + 1U, '\0');
    int32_t written = llama_chat_apply_template(
        tmpl,
        &message,
        1,
        true,
        formatted.data(),
        static_cast<int32_t>(formatted.size())
    );
    if (written <= 0) {
        return user_content;
    }

    return std::string(formatted.data(), static_cast<size_t>(written));
}

std::string token_piece(const llama_vocab * vocab, llama_token token) {
    char small[256];
    int32_t count = llama_token_to_piece(
        vocab, token, small, static_cast<int32_t>(sizeof(small)), 0, true
    );
    if (count >= 0) {
        return std::string(small, static_cast<size_t>(count));
    }

    const int32_t needed = -count;
    if (needed <= 0 || needed > 64 * 1024) {
        return {};
    }
    std::string large(static_cast<size_t>(needed), '\0');
    count = llama_token_to_piece(vocab, token, large.data(), needed, 0, true);
    if (count < 0) {
        return {};
    }
    large.resize(static_cast<size_t>(count));
    return large;
}

int32_t thread_count() {
    const unsigned int hardware = std::thread::hardware_concurrency();
    if (hardware <= 2) return 2;
    return static_cast<int32_t>(std::clamp<unsigned int>(hardware - 2, 2, 8));
}

jstring generate_locked(
        JNIEnv * env,
        const std::string & prompt,
        int32_t requested_new_tokens) {
    if (g_model == nullptr || prompt.empty()) {
        return nullptr;
    }

    if (llama_model_has_encoder(g_model)) {
        log_error("encoder-decoder models are not supported by Jarvis Brain 0.1");
        return nullptr;
    }

    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    if (vocab == nullptr) {
        log_error("model vocabulary unavailable");
        return nullptr;
    }

    const std::string model_prompt = apply_model_chat_template(g_model, prompt);

    const int32_t token_count = -llama_tokenize(
        vocab,
        model_prompt.c_str(),
        static_cast<int32_t>(model_prompt.size()),
        nullptr,
        0,
        true,
        true
    );
    if (token_count <= 0) {
        log_error("prompt tokenization failed");
        return nullptr;
    }

    std::vector<llama_token> prompt_tokens(static_cast<size_t>(token_count));
    const int32_t written = llama_tokenize(
        vocab,
        model_prompt.c_str(),
        static_cast<int32_t>(model_prompt.size()),
        prompt_tokens.data(),
        token_count,
        true,
        true
    );
    if (written <= 0) {
        log_error("prompt tokenization returned no tokens");
        return nullptr;
    }
    prompt_tokens.resize(static_cast<size_t>(written));

    const uint32_t trained_ctx = std::max<uint32_t>(
        1024U, llama_model_n_ctx_train(g_model)
    );
    if (prompt_tokens.size() + 16 >= trained_ctx) {
        log_error("prompt is larger than model context");
        return nullptr;
    }

    const uint32_t available = trained_ctx -
        static_cast<uint32_t>(prompt_tokens.size()) - 8U;
    const uint32_t n_predict = std::min<uint32_t>(
        static_cast<uint32_t>(std::clamp(requested_new_tokens, 32, 1024)),
        available
    );
    const uint32_t requested_ctx =
        static_cast<uint32_t>(prompt_tokens.size()) + n_predict + 8U;
    const uint32_t n_ctx = std::min<uint32_t>(
        trained_ctx, std::max<uint32_t>(1024U, requested_ctx)
    );

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = n_ctx;
    context_params.n_batch = std::min<uint32_t>(
        n_ctx, std::max<uint32_t>(512U, static_cast<uint32_t>(prompt_tokens.size()))
    );
    context_params.n_ubatch = std::min<uint32_t>(context_params.n_batch, 512U);
    context_params.n_threads = thread_count();
    context_params.n_threads_batch = context_params.n_threads;

    llama_context * context = llama_init_from_model(g_model, context_params);
    if (context == nullptr) {
        log_error("failed to create llama context");
        return nullptr;
    }

    llama_sampler_chain_params chain_params = llama_sampler_chain_default_params();
    chain_params.no_perf = true;
    llama_sampler * sampler = llama_sampler_chain_init(chain_params);
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.92f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.72f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    llama_batch batch = llama_batch_get_one(
        prompt_tokens.data(), static_cast<int32_t>(prompt_tokens.size())
    );

    std::string output;
    output.reserve(static_cast<size_t>(n_predict) * 5U);

    bool failed = false;
    llama_token next_token = LLAMA_TOKEN_NULL;
    for (uint32_t i = 0; i < n_predict; ++i) {
        if (llama_decode(context, batch) != 0) {
            log_error("llama_decode failed");
            failed = true;
            break;
        }

        next_token = llama_sampler_sample(sampler, context, -1);
        if (llama_vocab_is_eog(vocab, next_token)) {
            break;
        }

        const std::string piece = token_piece(vocab, next_token);
        if (piece.empty()) {
            log_error("failed to decode generated token");
            failed = true;
            break;
        }
        output.append(piece);
        batch = llama_batch_get_one(&next_token, 1);
    }

    llama_sampler_free(sampler);
    llama_free(context);

    if (failed || output.empty()) {
        return nullptr;
    }

    return env->NewStringUTF(output.c_str());
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jarvis_phone_JarvisNativeLanguageModel_nativeRuntimeAvailable(
        JNIEnv *, jobject) {
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jarvis_phone_JarvisNativeLanguageModel_nativeLoadModel(
        JNIEnv * env, jobject, jstring j_path) {
    if (j_path == nullptr) return JNI_FALSE;

    std::lock_guard<std::mutex> guard(g_mutex);
    init_backend_once();

    const char * raw_path = env->GetStringUTFChars(j_path, nullptr);
    if (raw_path == nullptr) return JNI_FALSE;
    const std::string path(raw_path);
    env->ReleaseStringUTFChars(j_path, raw_path);

    free_model_locked();

    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = 0;

    g_model = llama_model_load_from_file(path.c_str(), params);
    if (g_model == nullptr) {
        log_error("failed to load GGUF model");
        return JNI_FALSE;
    }

    __android_log_print(ANDROID_LOG_INFO, TAG, "local GGUF model loaded");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_jarvis_phone_JarvisNativeLanguageModel_nativeGenerate(
        JNIEnv * env, jobject, jstring j_prompt, jint max_new_tokens) {
    if (j_prompt == nullptr) return nullptr;

    std::lock_guard<std::mutex> guard(g_mutex);
    if (g_model == nullptr) return nullptr;

    const char * raw_prompt = env->GetStringUTFChars(j_prompt, nullptr);
    if (raw_prompt == nullptr) return nullptr;
    const std::string prompt(raw_prompt);
    env->ReleaseStringUTFChars(j_prompt, raw_prompt);

    return generate_locked(
        env,
        prompt,
        static_cast<int32_t>(max_new_tokens)
    );
}

extern "C" JNIEXPORT void JNICALL
Java_com_jarvis_phone_JarvisNativeLanguageModel_nativeUnloadModel(
        JNIEnv *, jobject) {
    std::lock_guard<std::mutex> guard(g_mutex);
    free_model_locked();
}
