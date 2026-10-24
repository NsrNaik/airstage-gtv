/*
 * JNI bridge between UxPlay's AirPlay server library and the Kotlin app.
 *
 * UxPlay calls the raop callbacks below from its own network threads. Audio is
 * decoded here (ALAC) or passed through (AAC), metadata and video-control events are
 * forwarded to NativeBridge's static methods, and the Kotlin side reports HLS
 * playback state back through nativeUpdatePlayback() so /playback-info can answer
 * without calling into Java.
 *
 * Copyright (C) 2026 airplay-tv contributors. GPL-3.0-or-later.
 */

#include <jni.h>
#include <android/log.h>
#include <sys/system_properties.h>
#include <pthread.h>
#include <string.h>
#include <time.h>
#include <mutex>
#include <string>

extern "C" {
#include "uxplay/raop.h"
#include "uxplay/dnssd.h"
#include "uxplay/logger.h"
#include "uxplay/stream.h"
}
#include "dnssd_android.h"
#include "alac/ALACDecoder.h"
#include "alac/ALACBitUtilities.h"

#define TAG "AirPlayNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* Audio "ct" (compression type) values sent by the client in SETUP. */
#define CT_ALAC 2
#define CT_AAC_LC 4
#define CT_AAC_ELD 8

/* Same as the reset types in raop.h, mirrored in NativeBridge.kt. */

static JavaVM *g_vm = nullptr;
static jclass g_bridge = nullptr;
static pthread_key_t g_detach_key;

static raop_t *g_raop = nullptr;
static dnssd_t *g_dnssd = nullptr;
static std::string g_lang;

static std::mutex g_audio_lock;
static ALACDecoder *g_alac = nullptr;
static unsigned char g_ct = 0;
static double g_client_volume = -15.0;

static std::mutex g_playback_lock;
static double g_duration = 0.0, g_position = 0.0;
static float g_rate = 0.0f;
static bool g_ready = false, g_finished = false;
/* When the iPhone last asked for /playback-info (it polls about once a second while it controls a video). */
static int64_t g_last_info_poll_ms = 0;

static int64_t now_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static struct {
    jmethodID registerService, unregisterService;
    jmethodID audioFormat, audioPcm, audioAac, audioFlush, volume;
    jmethodID coverArt, metadata, progress, connections, client;
    jmethodID videoPlay, videoScrub, videoRate, videoStop, videoReset, connReset;
} m;

static void detach_thread(void *) {
    g_vm->DetachCurrentThread();
}

/* Returns a JNIEnv for the calling UxPlay thread, attaching it once; it is detached when the thread exits. */
static JNIEnv *env() {
    JNIEnv *e = nullptr;
    if (g_vm->GetEnv((void **) &e, JNI_VERSION_1_6) == JNI_OK) {
        return e;
    }
    if (g_vm->AttachCurrentThread(&e, nullptr) != JNI_OK) {
        return nullptr;
    }
    pthread_setspecific(g_detach_key, e);
    return e;
}

static void check(JNIEnv *e) {
    if (e->ExceptionCheck()) {
        e->ExceptionDescribe();
        e->ExceptionClear();
    }
}

static jbyteArray bytes(JNIEnv *e, const void *data, int len) {
    jbyteArray a = e->NewByteArray(len);
    e->SetByteArrayRegion(a, 0, len, (const jbyte *) data);
    return a;
}

/* ---- DNS-SD (called from dnssd_android.c) ---- */

extern "C" void android_dnssd_register(const char *type, const char *name, unsigned short port,
                                       const char **keys, const char **values, int count) {
    JNIEnv *e = env();
    jclass str = e->FindClass("java/lang/String");
    jobjectArray k = e->NewObjectArray(count, str, nullptr);
    jobjectArray v = e->NewObjectArray(count, str, nullptr);
    for (int i = 0; i < count; i++) {
        jstring ks = e->NewStringUTF(keys[i]);
        jstring vs = e->NewStringUTF(values[i]);
        e->SetObjectArrayElement(k, i, ks);
        e->SetObjectArrayElement(v, i, vs);
        e->DeleteLocalRef(ks);
        e->DeleteLocalRef(vs);
    }
    jstring t = e->NewStringUTF(type), n = e->NewStringUTF(name);
    e->CallStaticVoidMethod(g_bridge, m.registerService, t, n, (jint) port, k, v);
    check(e);
    e->DeleteLocalRef(t);
    e->DeleteLocalRef(n);
    e->DeleteLocalRef(k);
    e->DeleteLocalRef(v);
}

extern "C" void android_dnssd_unregister(const char *type) {
    JNIEnv *e = env();
    jstring t = e->NewStringUTF(type);
    e->CallStaticVoidMethod(g_bridge, m.unregisterService, t);
    check(e);
    e->DeleteLocalRef(t);
}

/* ---- raop callbacks ---- */

static void cb_conn_init(void *) {
    JNIEnv *e = env();
    e->CallStaticVoidMethod(g_bridge, m.connections, (jint) 1);
    check(e);
}

static void cb_conn_destroy(void *) {
    JNIEnv *e = env();
    e->CallStaticVoidMethod(g_bridge, m.connections, (jint) -1);
    check(e);
}

static void cb_conn_reset(void *, int reason) {
    JNIEnv *e = env();
    e->CallStaticVoidMethod(g_bridge, m.connReset, (jint) reason);
    check(e);
}

static void cb_conn_feedback(void *) {}

static void cb_audio_get_format(void *, unsigned char *ct, unsigned short *spf, bool *usingScreen,
                                bool *isMedia, uint64_t *audioFormat) {
    LOGI("audio format ct=%d spf=%d usingScreen=%d isMedia=%d", *ct, *spf, *usingScreen, *isMedia);
    {
        std::lock_guard<std::mutex> lock(g_audio_lock);
        g_ct = *ct;
        if (g_ct == CT_ALAC && !g_alac) {
            /* ALACSpecificConfig for AirPlay's fixed format: 352 frames, 16 bit, 2 ch, 44100 Hz. */
            static const uint8_t cookie[24] = {0x00, 0x00, 0x01, 0x60, 0x00, 0x10, 0x28, 0x0a,
                                               0x0e, 0x02, 0x00, 0xff, 0x00, 0x00, 0x00, 0x00,
                                               0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xac, 0x44};
            g_alac = new ALACDecoder();
            if (g_alac->Init((void *) cookie, sizeof(cookie)) != 0) {
                LOGE("ALAC decoder init failed");
                delete g_alac;
                g_alac = nullptr;
            }
        }
    }
    JNIEnv *e = env();
    e->CallStaticVoidMethod(g_bridge, m.audioFormat, (jint) *ct, (jint) *spf);
    check(e);
}

static void cb_audio_process(void *, raop_ntp_t *, audio_decode_struct *data) {
    JNIEnv *e = env();
    if (data->ct == CT_ALAC) {
        int16_t pcm[4096 * 2];
        uint32_t frames = 0;
        {
            std::lock_guard<std::mutex> lock(g_audio_lock);
            if (!g_alac) {
                return;
            }
            BitBuffer bits;
            BitBufferInit(&bits, data->data, (uint32_t) data->data_len);
            /* For full frames the decoder decodes exactly the count it is given: pass the frame length (352). */
            uint32_t frame_length = g_alac->mConfig.frameLength;
            int32_t status = g_alac->Decode(&bits, (uint8_t *) pcm, frame_length, 2, &frames);
            if (status != 0 || frames == 0) {
                static int errors = 0;
                if (errors++ < 5) LOGE("ALAC decode failed: status %d, %d bytes", status, data->data_len);
                return;
            }
        }
        jshortArray a = e->NewShortArray((jsize) frames * 2);
        e->SetShortArrayRegion(a, 0, (jsize) frames * 2, pcm);
        e->CallStaticVoidMethod(g_bridge, m.audioPcm, a);
        check(e);
        e->DeleteLocalRef(a);
    } else {
        jbyteArray a = bytes(e, data->data, data->data_len);
        e->CallStaticVoidMethod(g_bridge, m.audioAac, a);
        check(e);
        e->DeleteLocalRef(a);
    }
}

static void cb_audio_flush(void *) {
    JNIEnv *e = env();
    e->CallStaticVoidMethod(g_bridge, m.audioFlush);
    check(e);
}

static double cb_audio_set_client_volume(void *) {
    return g_client_volume;
}

static void cb_audio_set_volume(void *, float volume) {
    g_client_volume = volume;
    JNIEnv *e = env();
    e->CallStaticVoidMethod(g_bridge, m.volume, (jfloat) volume);
    check(e);
}

static void cb_audio_set_metadata(void *, const void *buffer, int buflen) {
    JNIEnv *e = env();
    jbyteArray a = bytes(e, buffer, buflen);
    e->CallStaticVoidMethod(g_bridge, m.metadata, a);
    check(e);
    e->DeleteLocalRef(a);
}

static void cb_audio_set_coverart(void *, const void *buffer, int buflen) {
    if (!buffer || buflen <= 0) {
        return;
    }
    JNIEnv *e = env();
    jbyteArray a = bytes(e, buffer, buflen);
    e->CallStaticVoidMethod(g_bridge, m.coverArt, a);
    check(e);
    e->DeleteLocalRef(a);
}

static void cb_audio_stop_coverart_rendering(void *) {}

static void cb_audio_set_progress(void *, uint32_t *start, uint32_t *curr, uint32_t *end) {
    JNIEnv *e = env();
    e->CallStaticVoidMethod(g_bridge, m.progress, (jlong) *start, (jlong) *curr, (jlong) *end);
    check(e);
}

static void cb_report_client_request(void *, char *deviceid, char *model, char *name, bool *admit) {
    LOGI("connection request from %s (%s) deviceID %s", name, model, deviceid);
    *admit = true;
    JNIEnv *e = env();
    jstring n = e->NewStringUTF(name ? name : "");
    jstring mo = e->NewStringUTF(model ? model : "");
    e->CallStaticVoidMethod(g_bridge, m.client, n, mo);
    check(e);
    e->DeleteLocalRef(n);
    e->DeleteLocalRef(mo);
}

static void cb_video_process(void *, raop_ntp_t *, video_decode_struct *) {
    /* Screen mirroring is not supported: the mirroring feature bit is off, so this should not be called. */
}

static void cb_video_pause(void *) {}
static void cb_video_resume(void *) {}
static void cb_video_flush(void *) {}

static int cb_video_set_codec_and_size(void *, video_codec_t, unsigned short, unsigned short, unsigned short,
                                       unsigned short, unsigned short, unsigned short) {
    return 0;
}

static void cb_display_pin(void *, char *pin) {}
static void cb_register_client(void *, const char *, const char *, const char *) {}
static bool cb_check_register(void *, const char *) { return true; }

static const char *cb_passwd(void *, int *len) {
    *len = 0;
    return nullptr;
}

static void cb_export_dacp(void *, const char *, const char *) {}

static void cb_video_reset(void *, reset_type_t type) {
    LOGI("video_reset type %d", (int) type);
    switch (type) {
    case RESET_TYPE_NOHOLD:
        raop_destroy_airplay_video(g_raop, -1);
        break;
    case RESET_TYPE_HLS_SHUTDOWN:
        raop_destroy_airplay_video(g_raop, -1);
        raop_remove_hls_connections(g_raop);
        break;
    default:
        break;
    }
    JNIEnv *e = env();
    e->CallStaticVoidMethod(g_bridge, m.videoReset, (jint) type);
    check(e);
}

static void cb_on_video_play(void *, const char *location, const float start_position) {
    LOGI("video play %s from %.1f s", location, start_position);
    {
        std::lock_guard<std::mutex> lock(g_playback_lock);
        g_duration = 0.0;
        g_position = start_position;
        g_rate = 0.0f;
        g_ready = false;
        g_finished = false;
        g_last_info_poll_ms = now_ms();
    }
    JNIEnv *e = env();
    jstring url = e->NewStringUTF(location);
    e->CallStaticVoidMethod(g_bridge, m.videoPlay, url, (jfloat) start_position);
    check(e);
    e->DeleteLocalRef(url);
}

static void cb_on_video_scrub(void *, const float position) {
    JNIEnv *e = env();
    e->CallStaticVoidMethod(g_bridge, m.videoScrub, (jfloat) position);
    check(e);
}

static void cb_on_video_rate(void *, const float rate) {
    JNIEnv *e = env();
    e->CallStaticVoidMethod(g_bridge, m.videoRate, (jfloat) rate);
    check(e);
}

static void cb_on_video_stop(void *) {
    JNIEnv *e = env();
    e->CallStaticVoidMethod(g_bridge, m.videoStop);
    check(e);
}

static void cb_on_video_acquire_playback_info(void *, playback_info_t *info) {
    std::lock_guard<std::mutex> lock(g_playback_lock);
    g_last_info_poll_ms = now_ms();
    info->duration = g_duration;
    info->position = g_position;
    info->rate = g_rate;
    info->seek_start = 0.0;
    info->seek_duration = g_duration;
    info->ready_to_play = g_ready;
    info->playback_buffer_empty = !g_ready;
    info->playback_buffer_full = g_ready;
    info->playback_likely_to_keep_up = g_ready;
    if (g_finished) {
        info->position = -1.0;
        info->duration = -1.0;
    }
}

static float cb_on_video_playlist_remove(void *) {
    std::lock_guard<std::mutex> lock(g_playback_lock);
    return (float) g_position;
}

/* Keep only H.264 YouTube variants up to 1080p: ExoPlayer otherwise picks the VP9 ones, which stall. */
static void cb_get_custom_profile(void *, const char **custom_profile) {
    *custom_profile = "AVC:1080;HEVC:0;VP9:0;AV1:0";
}

static void cb_log(void *, int level, const char *msg) {
    int prio = level <= LOGGER_ERR ? ANDROID_LOG_ERROR
             : level == LOGGER_WARNING ? ANDROID_LOG_WARN
             : level == LOGGER_INFO ? ANDROID_LOG_INFO : ANDROID_LOG_DEBUG;
    __android_log_write(prio, "UxPlay", msg);
}

/* ---- JNI entry points ---- */

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    pthread_key_create(&g_detach_key, detach_thread);
    JNIEnv *e;
    vm->GetEnv((void **) &e, JNI_VERSION_1_6);
    jclass c = e->FindClass("dev/airplaytv/NativeBridge");
    g_bridge = (jclass) e->NewGlobalRef(c);
    m.registerService = e->GetStaticMethodID(c, "onRegisterService",
                                             "(Ljava/lang/String;Ljava/lang/String;I[Ljava/lang/String;[Ljava/lang/String;)V");
    m.unregisterService = e->GetStaticMethodID(c, "onUnregisterService", "(Ljava/lang/String;)V");
    m.audioFormat = e->GetStaticMethodID(c, "onAudioFormat", "(II)V");
    m.audioPcm = e->GetStaticMethodID(c, "onAudioPcm", "([S)V");
    m.audioAac = e->GetStaticMethodID(c, "onAudioAac", "([B)V");
    m.audioFlush = e->GetStaticMethodID(c, "onAudioFlush", "()V");
    m.volume = e->GetStaticMethodID(c, "onVolume", "(F)V");
    m.coverArt = e->GetStaticMethodID(c, "onCoverArt", "([B)V");
    m.metadata = e->GetStaticMethodID(c, "onMetadata", "([B)V");
    m.progress = e->GetStaticMethodID(c, "onProgress", "(JJJ)V");
    m.connections = e->GetStaticMethodID(c, "onConnections", "(I)V");
    m.client = e->GetStaticMethodID(c, "onClient", "(Ljava/lang/String;Ljava/lang/String;)V");
    m.videoPlay = e->GetStaticMethodID(c, "onVideoPlay", "(Ljava/lang/String;F)V");
    m.videoScrub = e->GetStaticMethodID(c, "onVideoScrub", "(F)V");
    m.videoRate = e->GetStaticMethodID(c, "onVideoRate", "(F)V");
    m.videoStop = e->GetStaticMethodID(c, "onVideoStop", "()V");
    m.videoReset = e->GetStaticMethodID(c, "onVideoReset", "(I)V");
    m.connReset = e->GetStaticMethodID(c, "onConnReset", "(I)V");
    return JNI_VERSION_1_6;
}

/* Starts the AirPlay server and announces it. Returns the TCP port, or a negative error. */
extern "C" JNIEXPORT jint JNICALL
Java_dev_airplaytv_NativeBridge_nativeStart(JNIEnv *e, jobject, jstring jname, jbyteArray jhw,
                                            jstring jkeyfile, jstring jlang) {
    if (g_raop) {
        return raop_get_port(g_raop);
    }
    const char *name = e->GetStringUTFChars(jname, nullptr);
    const char *keyfile = e->GetStringUTFChars(jkeyfile, nullptr);
    const char *lang = e->GetStringUTFChars(jlang, nullptr);
    g_lang = lang;
    jsize hwlen = e->GetArrayLength(jhw);
    char hw[6];
    e->GetByteArrayRegion(jhw, 0, hwlen < 6 ? hwlen : 6, (jbyte *) hw);
    char device_id[18];
    snprintf(device_id, sizeof(device_id), "%02x:%02x:%02x:%02x:%02x:%02x",
             hw[0] & 0xff, hw[1] & 0xff, hw[2] & 0xff, hw[3] & 0xff, hw[4] & 0xff, hw[5] & 0xff);

    int ret = -1;
    int err = 0;
    ntp_global_init();
    g_dnssd = dnssd_init(name, (int) strlen(name), hw, 6, 0, &err);
    if (err) {
        LOGE("dnssd_init failed: %d", err);
        goto done;
    }
    /* Feature set as in uxplay.cpp's start_dnssd(), with HLS video on and screen mirroring off. */
    {
        static const int on[] = {0, 1, 2, 4, 5, 6, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 25, 28, 30};
        for (int bit = 0; bit < 64; bit++) {
            dnssd_set_airplay_features(g_dnssd, bit, 0);
        }
        for (int bit : on) {
            dnssd_set_airplay_features(g_dnssd, bit, 1);
        }
    }

    raop_callbacks_t cbs;
    memset(&cbs, 0, sizeof(cbs));
    cbs.conn_init = cb_conn_init;
    cbs.conn_destroy = cb_conn_destroy;
    cbs.conn_reset = cb_conn_reset;
    cbs.conn_feedback = cb_conn_feedback;
    cbs.audio_process = cb_audio_process;
    cbs.video_process = cb_video_process;
    cbs.audio_flush = cb_audio_flush;
    cbs.video_flush = cb_video_flush;
    cbs.video_pause = cb_video_pause;
    cbs.video_resume = cb_video_resume;
    cbs.audio_set_client_volume = cb_audio_set_client_volume;
    cbs.audio_set_volume = cb_audio_set_volume;
    cbs.audio_get_format = cb_audio_get_format;
    cbs.audio_set_metadata = cb_audio_set_metadata;
    cbs.audio_set_coverart = cb_audio_set_coverart;
    cbs.audio_stop_coverart_rendering = cb_audio_stop_coverart_rendering;
    cbs.audio_set_progress = cb_audio_set_progress;
    cbs.report_client_request = cb_report_client_request;
    cbs.display_pin = cb_display_pin;
    cbs.register_client = cb_register_client;
    cbs.check_register = cb_check_register;
    cbs.passwd = cb_passwd;
    cbs.export_dacp = cb_export_dacp;
    cbs.video_reset = cb_video_reset;
    cbs.video_set_codec_and_size = cb_video_set_codec_and_size;
    cbs.on_video_play = cb_on_video_play;
    cbs.on_video_scrub = cb_on_video_scrub;
    cbs.on_video_rate = cb_on_video_rate;
    cbs.on_video_stop = cb_on_video_stop;
    cbs.on_video_playlist_remove = cb_on_video_playlist_remove;
    cbs.on_video_acquire_playback_info = cb_on_video_acquire_playback_info;
    cbs.get_custom_profile = cb_get_custom_profile;

    g_raop = raop_init(&cbs);
    if (!g_raop) {
        LOGE("raop_init failed");
        goto done;
    }
    raop_set_log_callback(g_raop, cb_log, nullptr);
    {
        /* adb shell setprop debug.airplaytv.verbose 1 (then restart the app) for UxPlay's protocol logs. */
        char verbose[PROP_VALUE_MAX] = "";
        __system_property_get("debug.airplaytv.verbose", verbose);
        raop_set_log_level(g_raop, verbose[0] == '1' ? LOGGER_DEBUG : LOGGER_INFO);
    }
    if (raop_init2(g_raop, 1, device_id, keyfile)) {
        LOGE("raop_init2 failed");
        raop_destroy(g_raop);
        g_raop = nullptr;
        goto done;
    }
    raop_set_plist(g_raop, "hls", 1);
    {
        unsigned short tcp[3] = {7000, 7001, 7100};
        unsigned short udp[3] = {6000, 6001, 7011};
        raop_set_tcp_ports(g_raop, tcp);
        raop_set_udp_ports(g_raop, udp);
        unsigned short port = raop_get_port(g_raop);
        if (raop_start_httpd(g_raop, &port) < 0) {
            LOGE("raop_start_httpd failed");
            raop_destroy(g_raop);
            g_raop = nullptr;
            goto done;
        }
        raop_set_port(g_raop, port);
        raop_set_dnssd(g_raop, g_dnssd);
        raop_set_lang(g_raop, "", "", g_lang.c_str());
        if (dnssd_register_raop(g_dnssd, port) || dnssd_register_airplay(g_dnssd, port)) {
            LOGE("dnssd registration failed");
        }
        ret = port;
    }

done:
    e->ReleaseStringUTFChars(jname, name);
    e->ReleaseStringUTFChars(jkeyfile, keyfile);
    e->ReleaseStringUTFChars(jlang, lang);
    return ret;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_airplaytv_NativeBridge_nativeStop(JNIEnv *, jobject) {
    if (g_raop) {
        raop_destroy(g_raop);
        g_raop = nullptr;
    }
    if (g_dnssd) {
        dnssd_unregister_raop(g_dnssd);
        dnssd_unregister_airplay(g_dnssd);
        dnssd_destroy(g_dnssd);
        g_dnssd = nullptr;
    }
    std::lock_guard<std::mutex> lock(g_audio_lock);
    delete g_alac;
    g_alac = nullptr;
}

/* After a lost connection UxPlay expects its HTTP server to be restarted (as uxplay.cpp's main loop does). */
extern "C" JNIEXPORT void JNICALL
Java_dev_airplaytv_NativeBridge_nativeRestartHttpd(JNIEnv *, jobject) {
    if (!g_raop) {
        return;
    }
    raop_stop_httpd(g_raop);
    unsigned short port = raop_get_port(g_raop);
    raop_start_httpd(g_raop, &port);
    raop_set_port(g_raop, port);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_airplaytv_NativeBridge_nativeUpdatePlayback(JNIEnv *, jobject, jdouble duration, jdouble position,
                                                     jfloat rate, jboolean ready, jboolean finished) {
    std::lock_guard<std::mutex> lock(g_playback_lock);
    g_duration = duration;
    g_position = position;
    g_rate = rate;
    g_ready = ready;
    g_finished = finished;
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_airplaytv_NativeBridge_nativeMsSincePlaybackPoll(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_playback_lock);
    return (jlong) (now_ms() - g_last_info_poll_ms);
}
