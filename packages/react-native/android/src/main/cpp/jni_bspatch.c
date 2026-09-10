/*
 * JNI wrapper for bspatch_apply.
 * Exposed as com.nitropush.sdk.BspatchJni.patch(String, String, String, Long): Int
 */
#include <jni.h>
#include "bspatch/bspatch.h"

JNIEXPORT jint JNICALL
Java_com_nitropush_sdk_BspatchJni_patch(
    JNIEnv *env,
    jclass clazz,
    jstring basePath,
    jstring patchPath,
    jstring outPath,
    jlong expectedSize
) {
    (void)clazz;
    if (expectedSize < 0) return 1;
    const char *base  = (*env)->GetStringUTFChars(env, basePath,  NULL);
    if (!base) return 1;
    const char *patch = (*env)->GetStringUTFChars(env, patchPath, NULL);
    if (!patch) { (*env)->ReleaseStringUTFChars(env, basePath, base); return 1; }
    const char *out   = (*env)->GetStringUTFChars(env, outPath,   NULL);

    int rc = out ? bspatch_apply(base, patch, out, (uint64_t)expectedSize) : 1;

    (*env)->ReleaseStringUTFChars(env, basePath,  base);
    (*env)->ReleaseStringUTFChars(env, patchPath, patch);
    if (out) (*env)->ReleaseStringUTFChars(env, outPath, out);

    return (jint)rc;
}
