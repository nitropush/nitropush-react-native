/*
 * JNI wrapper for bspatch_apply.
 * Exposed as com.nitropush.sdk.BspatchJni.patch(String, String, String): Int
 */
#include <jni.h>
#include "bspatch/bspatch.h"

JNIEXPORT jint JNICALL
Java_com_nitropush_sdk_BspatchJni_patch(
    JNIEnv *env,
    jclass clazz,
    jstring basePath,
    jstring patchPath,
    jstring outPath
) {
    const char *base  = (*env)->GetStringUTFChars(env, basePath,  NULL);
    const char *patch = (*env)->GetStringUTFChars(env, patchPath, NULL);
    const char *out   = (*env)->GetStringUTFChars(env, outPath,   NULL);

    int rc = bspatch_apply(base, patch, out);

    (*env)->ReleaseStringUTFChars(env, basePath,  base);
    (*env)->ReleaseStringUTFChars(env, patchPath, patch);
    (*env)->ReleaseStringUTFChars(env, outPath,   out);

    return (jint)rc;
}
