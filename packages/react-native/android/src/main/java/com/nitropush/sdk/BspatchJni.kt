package com.nitropush.sdk

/**
 * JNI wrapper for the vendored bspatch C implementation.
 * Loaded by the nitropush native library (libreact_codegen_NitroPush.so or similar).
 */
internal object BspatchJni {
    /**
     * Apply a bsdiff4 patch.
     *
     * @param basePath  Absolute path to the base (old) file.
     * @param patchPath Absolute path to the bsdiff4 patch file.
     * @param outPath   Absolute path where the patched output should be written.
     * @return 0 on success, non-zero on failure.
     */
    external fun patch(basePath: String, patchPath: String, outPath: String, expectedSize: Long): Int

    init {
        System.loadLibrary("NitroPush")
    }
}
