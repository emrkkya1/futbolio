package app.futbolio.application.ports

/** Wall time for dates; monotonic elapsed time for process-local expiry. */
interface TimeSource {
    fun wallMillis(): Long
    fun monotonicMillis(): Long

    companion object {
        val System: TimeSource = object : TimeSource {
            override fun wallMillis() = java.lang.System.currentTimeMillis()
            override fun monotonicMillis() = java.lang.System.nanoTime() / 1_000_000
        }
    }
}
