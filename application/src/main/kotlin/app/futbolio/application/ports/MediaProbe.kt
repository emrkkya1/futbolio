package app.futbolio.application.ports

import app.futbolio.application.api.SegmentResult

interface MediaProbe {
    suspend fun probe(choice: StreamChoice, verifyMedia: Boolean = false): List<SegmentResult>
}
