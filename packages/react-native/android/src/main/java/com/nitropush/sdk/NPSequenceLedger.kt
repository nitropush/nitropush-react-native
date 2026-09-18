package com.nitropush.sdk

internal data class NPSequenceStamp(val version: Double, val releaseId: String)

internal class NPSequenceLedger(
    private val load: (String) -> NPSequenceStamp?,
    private val save: (String, NPSequenceStamp) -> Unit,
) {
    /** Only app-private installed records, never fresh network metadata. */
    @Synchronized fun seedAccepted(scope: String, version: Double, releaseId: String) {
        if (load(scope)?.let { it.version >= version } == true) return
        check(scope, version, releaseId, remember = true)
    }
    @Synchronized fun check(scope: String, version: Double, releaseId: String, remember: Boolean = false) {
        check(version.isFinite() && version >= 1 && version <= 9_007_199_254_740_991.0 && version % 1.0 == 0.0) { "invalid OTA sequence" }
        load(scope)?.let { current ->
            check(version > current.version || (version == current.version && releaseId == current.releaseId)) { "refusing a replayed OTA release" }
        }
        if (remember) save(scope, NPSequenceStamp(version, releaseId))
    }
}
