#include <jni.h>
#include <string>
#include <vector>
#include "opus.h"
#include "whisper.h"

static const int RATE = 16000;
static const int FRAME = RATE / 50;
static const int MAX_FRAME = FRAME * 6;

extern "C" JNIEXPORT jfloatArray JNICALL
Java_dev_resticky_app_Whisper_decodeOpus(JNIEnv *env, jclass, jbyteArray frames) {
    std::vector<unsigned char> in(env->GetArrayLength(frames));
    env->GetByteArrayRegion(frames, 0, static_cast<jsize>(in.size()), reinterpret_cast<jbyte *>(in.data()));
    int err = 0;
    OpusDecoder *dec = opus_decoder_create(RATE, 1, &err);
    if (err != OPUS_OK) return nullptr;
    std::vector<float> out;
    float buf[MAX_FRAME];
    size_t i = 0;
    while (i + 2 <= in.size()) {
        size_t len = in[i] | (in[i + 1] << 8);
        i += 2;
        if (i + len > in.size()) break;
        int got = opus_decode_float(dec, len ? in.data() + i : nullptr, static_cast<opus_int32>(len), buf, len ? MAX_FRAME : FRAME, 0);
        if (got > 0) out.insert(out.end(), buf, buf + got);
        else out.insert(out.end(), FRAME, 0.0f);
        i += len;
    }
    opus_decoder_destroy(dec);
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(out.size()));
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
    return result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_resticky_app_Whisper_load(JNIEnv *env, jclass, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context *ctx = whisper_init_from_file_with_params(p, whisper_context_default_params());
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_resticky_app_Whisper_free(JNIEnv *, jclass, jlong ctx) {
    whisper_free(reinterpret_cast<whisper_context *>(ctx));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_resticky_app_Whisper_transcribe(JNIEnv *env, jclass, jlong handle, jfloatArray pcm, jint threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
    params.n_threads = threads;
    params.language = "en";
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    params.beam_search.beam_size = 5;
    params.greedy.best_of = 5;
    params.token_timestamps = true;
    params.suppress_nst = true;
    params.no_context = true;
    jsize n = env->GetArrayLength(pcm);
    jfloat *data = env->GetFloatArrayElements(pcm, nullptr);
    int rc = whisper_full(ctx, params, data, n);
    env->ReleaseFloatArrayElements(pcm, data, JNI_ABORT);
    return rc == 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_resticky_app_Whisper_segments(JNIEnv *, jclass, jlong handle) {
    return whisper_full_n_segments(reinterpret_cast<whisper_context *>(handle));
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_resticky_app_Whisper_segmentStartMs(JNIEnv *, jclass, jlong handle, jint i) {
    return whisper_full_get_segment_t0(reinterpret_cast<whisper_context *>(handle), i) * 10;
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_resticky_app_Whisper_segmentEndMs(JNIEnv *, jclass, jlong handle, jint i) {
    return whisper_full_get_segment_t1(reinterpret_cast<whisper_context *>(handle), i) * 10;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_resticky_app_Whisper_segmentText(JNIEnv *env, jclass, jlong handle, jint i) {
    std::string text = whisper_full_get_segment_text(reinterpret_cast<whisper_context *>(handle), i);
    jbyteArray out = env->NewByteArray(static_cast<jsize>(text.size()));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
    return out;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_resticky_app_Whisper_tokens(JNIEnv *, jclass, jlong handle, jint i) {
    return whisper_full_n_tokens(reinterpret_cast<whisper_context *>(handle), i);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_dev_resticky_app_Whisper_tokenTimes(JNIEnv *env, jclass, jlong handle, jint i) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    int n = whisper_full_n_tokens(ctx, i);
    whisper_token eot = whisper_token_eot(ctx);
    std::vector<jlong> out(n * 3);
    for (int j = 0; j < n; j++) {
        whisper_token_data d = whisper_full_get_token_data(ctx, i, j);
        out[j * 3] = d.t0 * 10;
        out[j * 3 + 1] = d.t1 * 10;
        out[j * 3 + 2] = d.id >= eot ? 1 : 0;
    }
    jlongArray result = env->NewLongArray(n * 3);
    env->SetLongArrayRegion(result, 0, n * 3, out.data());
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_resticky_app_Whisper_tokenText(JNIEnv *env, jclass, jlong handle, jint i, jint j) {
    std::string text = whisper_full_get_token_text(reinterpret_cast<whisper_context *>(handle), i, j);
    jbyteArray out = env->NewByteArray(static_cast<jsize>(text.size()));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
    return out;
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_resticky_app_Whisper_opusOpen(JNIEnv *, jclass) {
    int err = 0;
    OpusDecoder *dec = opus_decoder_create(RATE, 1, &err);
    return err == OPUS_OK ? reinterpret_cast<jlong>(dec) : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_resticky_app_Whisper_opusReset(JNIEnv *, jclass, jlong handle) {
    opus_decoder_ctl(reinterpret_cast<OpusDecoder *>(handle), OPUS_RESET_STATE);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_resticky_app_Whisper_opusClose(JNIEnv *, jclass, jlong handle) {
    opus_decoder_destroy(reinterpret_cast<OpusDecoder *>(handle));
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_resticky_app_Whisper_opusFrame(JNIEnv *env, jclass, jlong handle, jbyteArray packet, jint offset, jint length, jfloatArray out) {
    float buf[MAX_FRAME];
    std::vector<unsigned char> in(length);
    if (length > 0) env->GetByteArrayRegion(packet, offset, length, reinterpret_cast<jbyte *>(in.data()));
    int got = opus_decode_float(reinterpret_cast<OpusDecoder *>(handle), length ? in.data() : nullptr, length, buf, length ? MAX_FRAME : FRAME, 0);
    if (got <= 0) return got;
    int n = got < env->GetArrayLength(out) ? got : env->GetArrayLength(out);
    env->SetFloatArrayRegion(out, 0, n, buf);
    return n;
}
