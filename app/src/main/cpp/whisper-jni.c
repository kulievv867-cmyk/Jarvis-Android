// JNI wrapper around whisper.cpp for the Jarvis Android app.
// Methods are exposed to Java class com.devin.jarvis.voice.WhisperJni.
//
// This is a slimmed-down adaptation of the official whisper.cpp Android
// sample. We only need: load model from file → transcribe a float32 PCM
// buffer (mono, 16 kHz) → return concatenated text. No streaming, no
// per-segment timestamps.

#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <sys/sysinfo.h>

#include "whisper.h"

#define TAG "Jarvis-Whisper-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

JNIEXPORT jlong JNICALL
Java_com_devin_jarvis_voice_WhisperJni_initContext(
        JNIEnv *env, jclass cls, jstring model_path_str) {
    (void) cls;
    const char *model_path = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    LOGI("Loading model: %s", model_path);
    struct whisper_context *ctx = whisper_init_from_file_with_params(model_path, cparams);
    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path);
    if (ctx == NULL) {
        LOGE("Failed to load model");
        return 0;
    }
    LOGI("Model loaded.");
    return (jlong) ctx;
}

JNIEXPORT void JNICALL
Java_com_devin_jarvis_voice_WhisperJni_freeContext(
        JNIEnv *env, jclass cls, jlong context_ptr) {
    (void) env; (void) cls;
    if (context_ptr == 0) return;
    whisper_free((struct whisper_context *) context_ptr);
}

JNIEXPORT jstring JNICALL
Java_com_devin_jarvis_voice_WhisperJni_transcribe(
        JNIEnv *env, jclass cls,
        jlong context_ptr,
        jfloatArray audio_data,
        jstring lang_str,
        jint num_threads) {
    (void) cls;
    if (context_ptr == 0) {
        return (*env)->NewStringUTF(env, "");
    }
    struct whisper_context *ctx = (struct whisper_context *) context_ptr;

    jfloat *audio = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize n_samples = (*env)->GetArrayLength(env, audio_data);

    const char *lang = lang_str == NULL ? NULL : (*env)->GetStringUTFChars(env, lang_str, NULL);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime  = false;
    params.print_progress  = false;
    params.print_timestamps = false;
    params.print_special   = false;
    params.translate       = false;
    params.language        = (lang != NULL && lang[0] != '\0') ? lang : "ru";
    params.n_threads       = num_threads > 0 ? num_threads : 4;
    params.offset_ms       = 0;
    params.no_context      = true;
    params.single_segment  = false;
    params.suppress_blank  = true;
    params.suppress_nst    = true;

    LOGI("Transcribing %d samples (%d threads, lang=%s)", (int) n_samples, params.n_threads, params.language);

    int rc = whisper_full(ctx, params, audio, (int) n_samples);

    if (lang != NULL) (*env)->ReleaseStringUTFChars(env, lang_str, lang);
    (*env)->ReleaseFloatArrayElements(env, audio_data, audio, JNI_ABORT);

    if (rc != 0) {
        LOGE("whisper_full failed: %d", rc);
        return (*env)->NewStringUTF(env, "");
    }

    // Concatenate every segment.
    int n_seg = whisper_full_n_segments(ctx);
    size_t total = 1;
    for (int i = 0; i < n_seg; i++) {
        const char *t = whisper_full_get_segment_text(ctx, i);
        if (t) total += strlen(t) + 1;
    }
    char *buf = (char *) malloc(total);
    if (buf == NULL) {
        return (*env)->NewStringUTF(env, "");
    }
    buf[0] = '\0';
    for (int i = 0; i < n_seg; i++) {
        const char *t = whisper_full_get_segment_text(ctx, i);
        if (t) {
            strcat(buf, t);
            if (i + 1 < n_seg) strcat(buf, " ");
        }
    }
    jstring result = (*env)->NewStringUTF(env, buf);
    free(buf);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_devin_jarvis_voice_WhisperJni_systemInfo(
        JNIEnv *env, jclass cls) {
    (void) cls;
    const char *info = whisper_print_system_info();
    return (*env)->NewStringUTF(env, info);
}
