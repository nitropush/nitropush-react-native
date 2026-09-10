package com.nitropush.sdk
fun main() {
    val disk = mutableMapOf<String, NPSequenceStamp>()
    fun ledger() = NPSequenceLedger({ disk[it] }, { key, stamp -> disk[key] = stamp })
    val first = ledger()
    first.seedAccepted("key/android/1.0", 10.0, "new")
    first.seedAccepted("key/android/1.0", 8.0, "previous")
    first.check("key/android/*", 1.0, "wildcard", remember = true)
    val rebooted = ledger()
    fun rejects(version: Double, id: String = "old") {
        check(runCatching { rebooted.check("key/android/1.0", version, id) }.isFailure)
    }
    listOf(9.0, 10.0, 0.0, -1.0, 10.5, Double.NaN, Double.POSITIVE_INFINITY,
        9_007_199_254_740_992.0, Long.MAX_VALUE.toDouble()).forEach { rejects(it) }
    rebooted.check("key/android/1.0", 10.0, "new")
    rebooted.check("key/android/1.0", 11.0, "later", remember = true)
    rebooted.check("key/android/2.0", 1.0, "new-binary", remember = true)
    rebooted.check("other-key/android/1.0", 1.0, "other-deployment", remember = true)
    rebooted.check("key/android/1.0", 9_007_199_254_740_991.0, "maximum")
    check(disk["key/android/1.0"]!!.version == 11.0)
    println("sequence replay, numeric bounds, independent runtimes and persistence passed")
}
