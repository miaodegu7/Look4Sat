#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <hamlib/rig.h>

#define JNI(name) Java_com_rtbishop_look4sat_core_data_framework_HamlibNative_##name
static void fail(JNIEnv *env, const char *message) {
    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"), message);
}
static int checked(JNIEnv *env, int code) {
    if (code != RIG_OK) fail(env, rigerror(code));
    return code == RIG_OK;
}
struct list { char *text; size_t size; int failed; };
static int model(const struct rig_caps *caps, rig_ptr_t data) {
    struct list *list = data;
    /* Android serial bridge cannot serve libusb, parallel or custom network backends. */
    if (caps->port_type != RIG_PORT_SERIAL) return 1;
    char line[512];
    snprintf(line, sizeof(line), "%d\t%s %s\n", caps->rig_model, caps->mfg_name, caps->model_name);
    size_t n = strlen(line);
    char *next = realloc(list->text, list->size + n + 1);
    if (!next) { list->failed = 1; return 0; }
    list->text = next;
    memcpy(list->text + list->size, line, n + 1);
    list->size += n;
    return 1;
}
JNIEXPORT jstring JNICALL JNI(models)(JNIEnv *env, jobject self) {
    rig_set_debug(RIG_DEBUG_NONE);
    rig_load_all_backends();
    struct list list = {0};
    rig_list_foreach(model, &list);
    if (list.failed) { free(list.text); fail(env, "Cannot enumerate Hamlib models"); return NULL; }
    jstring result = (*env)->NewStringUTF(env, list.text ? list.text : "");
    free(list.text);
    return result;
}
JNIEXPORT jlong JNICALL JNI(open)(JNIEnv *env, jobject self, jint id, jint port, jstring civ) {
    rig_set_debug(RIG_DEBUG_NONE);
    RIG *rig = rig_init(id);
    if (!rig) { fail(env, "Hamlib model unavailable"); return 0; }
    if (rig->caps->port_type != RIG_PORT_SERIAL) {
        rig_cleanup(rig); fail(env, "This model does not use a serial CAT port"); return 0;
    }
    char endpoint[64];
    snprintf(endpoint, sizeof(endpoint), "127.0.0.1:%d", port);
    int code = rig_set_conf(rig, rig_token_lookup(rig, "rig_pathname"), endpoint);
    const char *address = (*env)->GetStringUTFChars(env, civ, NULL);
    if (!address) { rig_cleanup(rig); return 0; }
    if (code == RIG_OK && *address) code = rig_set_conf(rig, rig_token_lookup(rig, "civaddr"), address);
    (*env)->ReleaseStringUTFChars(env, civ, address);
    if (code == RIG_OK) code = rig_open(rig);
    if (!checked(env, code)) { rig_cleanup(rig); return 0; }
    return (jlong)(intptr_t)rig;
}
JNIEXPORT void JNICALL JNI(close)(JNIEnv *env, jobject self, jlong handle) {
    RIG *rig = (RIG *)(intptr_t)handle;
    if (rig) { rig_close(rig); rig_cleanup(rig); }
}
/* JNI entry points are serialized by HamlibNative.mutex, including enumeration. */
JNIEXPORT jlong JNICALL JNI(command)(JNIEnv *env, jobject self, jlong handle, jint op, jlong value, jstring arg) {
    RIG *rig = (RIG *)(intptr_t)handle;
    if (!rig) { fail(env, "Hamlib is not connected"); return 0; }
    const char *text = (*env)->GetStringUTFChars(env, arg, NULL);
    if (!text) return 0;
    int code = -RIG_EINVAL;
    freq_t freq = 0;
    rmode_t mode;
    pbwidth_t width;
    jlong result = 0;
    switch (op) {
        case 0: code = rig_set_freq(rig, RIG_VFO_CURR, (freq_t)value); break;
        case 1: code = rig_get_freq(rig, RIG_VFO_CURR, &freq); result = (jlong)freq; break;
        case 2: code = rig_set_mode(rig, RIG_VFO_CURR, rig_parse_mode(text), RIG_PASSBAND_NORMAL); break;
        case 3: code = rig_set_vfo(rig, rig_parse_vfo(text)); break;
        case 4: code = rig_set_split_vfo(rig, RIG_VFO_CURR, value ? RIG_SPLIT_ON : RIG_SPLIT_OFF, rig_parse_vfo(text)); break;
        case 5: code = rig_set_split_freq(rig, RIG_VFO_CURR, (freq_t)value); break;
        case 6: code = rig_get_split_freq(rig, RIG_VFO_CURR, &freq); result = (jlong)freq; break;
        case 7: code = rig_set_split_mode(rig, RIG_VFO_CURR, rig_parse_mode(text), RIG_PASSBAND_NORMAL); break;
        case 8: code = rig_set_ctcss_tone(rig, RIG_VFO_CURR, (tone_t)value); break;
        case 9: code = rig_set_func(rig, RIG_VFO_CURR, RIG_FUNC_TONE, value != 0); break;
        case 10: code = rig_set_ptt(rig, RIG_VFO_CURR, RIG_PTT_OFF); break;
        case 11: code = rig_get_mode(rig, RIG_VFO_CURR, &mode, &width); result = (jlong)mode; break;
    }
    (*env)->ReleaseStringUTFChars(env, arg, text);
    checked(env, code);
    return result;
}
JNIEXPORT jstring JNICALL JNI(modeName)(JNIEnv *env, jobject self, jlong mode) {
    return (*env)->NewStringUTF(env, rig_strrmode((rmode_t)mode));
}
