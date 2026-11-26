#include <jni.h>
#include <android/log.h>
#include <string>
#include <cstring>
#include "whisper.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_ai_WhisperNativeBridge_nativeInitFromFile(
        JNIEnv *env,
        jobject /* thiz */,
        jstring model_path_jstr
) {
    if (model_path_jstr == nullptr) {
        return 0;
    }
    const char *model_path = env->GetStringUTFChars(model_path_jstr, nullptr);
    if (model_path == nullptr) {
        return 0;
    }

    LOGI("Loading Whisper GGML model: %s", model_path);
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    cparams.flash_attn = false;

    struct whisper_context *ctx = whisper_init_from_file_with_params(model_path, cparams);
    env->ReleaseStringUTFChars(model_path_jstr, model_path);

    if (ctx == nullptr) {
        LOGE("whisper_init_from_file_with_params returned null");
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_example_ai_WhisperNativeBridge_nativeFreeContext(
        JNIEnv * /* env */,
        jobject /* thiz */,
        jlong context_ptr
) {
    if (context_ptr == 0) {
        return;
    }
    auto *ctx = reinterpret_cast<struct whisper_context *>(context_ptr);
    whisper_free(ctx);
    LOGI("Freed Whisper context");
}

JNIEXPORT jstring JNICALL
Java_com_example_ai_WhisperNativeBridge_nativeTranscribe(
        JNIEnv *env,
        jobject /* thiz */,
        jlong context_ptr,
        jfloatArray audio_samples_jarr,
        jint num_threads,
        jstring language_jstr,
        jstring initial_prompt_jstr,
        jboolean single_segment,
        jboolean no_context
) {
    if (context_ptr == 0 || audio_samples_jarr == nullptr) {
        return env->NewStringUTF("");
    }

    auto *ctx = reinterpret_cast<struct whisper_context *>(context_ptr);
    jsize num_samples = env->GetArrayLength(audio_samples_jarr);
    if (num_samples <= 0) {
        return env->NewStringUTF("");
    }

    jfloat *samples = env->GetFloatArrayElements(audio_samples_jarr, nullptr);
    if (samples == nullptr) {
        return env->NewStringUTF("");
    }

    std::string language_str = "yue";
    if (language_jstr != nullptr) {
        const char *lang_chars = env->GetStringUTFChars(language_jstr, nullptr);
        if (lang_chars != nullptr) {
            if (std::strlen(lang_chars) > 0) {
                language_str = lang_chars;
            }
            env->ReleaseStringUTFChars(language_jstr, lang_chars);
        }
    }

    // Fallback gracefully to "zh" if a model vocabulary lacks the "yue" language token.
    if (whisper_lang_id(language_str.c_str()) < 0) {
        LOGI("Language '%s' not found in whisper_lang_id; falling back to 'zh'", language_str.c_str());
        language_str = "zh";
    }

    std::string prompt_str;
    if (initial_prompt_jstr != nullptr) {
        const char *prompt_chars = env->GetStringUTFChars(initial_prompt_jstr, nullptr);
        if (prompt_chars != nullptr) {
            prompt_str = prompt_chars;
            env->ReleaseStringUTFChars(initial_prompt_jstr, prompt_chars);
        }
    }

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = num_threads > 0 ? num_threads : 4;
    params.translate = false;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.no_timestamps = true;
    params.single_segment = (single_segment == JNI_TRUE);
    params.no_context = (no_context == JNI_TRUE);
    params.temperature = 0.0f;
    params.temperature_inc = 0.0f;
    params.suppress_blank = true;
    params.suppress_nst = true;
    params.language = language_str.c_str();
    params.initial_prompt = prompt_str.empty() ? nullptr : prompt_str.c_str();

    int rc = whisper_full(ctx, params, samples, static_cast<int>(num_samples));
    env->ReleaseFloatArrayElements(audio_samples_jarr, samples, JNI_ABORT);

    if (rc != 0) {
        LOGE("whisper_full failed with code %d", rc);
        return env->NewStringUTF("");
    }

    std::string result;
    const int n_segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_segments; ++i) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text != nullptr) {
            result += text;
        }
    }

    return env->NewStringUTF(result.c_str());
}

} // extern "C"
