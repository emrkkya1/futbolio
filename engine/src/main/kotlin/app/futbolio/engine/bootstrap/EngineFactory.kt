package app.futbolio.engine.bootstrap

import app.futbolio.application.api.*
import app.futbolio.engine.HttpUpstream
import app.futbolio.engine.api.EngineControl
import app.futbolio.engine.api.validate
import app.futbolio.engine.runtime.FutbolioEngine

/** Production composition root. Validation precedes acquisition of owned network resources. */
object EngineFactory {
    fun create(host: HostConfig, config: FutbolioConfig, poster: PosterRenderer, activity: MediaActivity = MediaActivity.None): EngineControl {
        config.validate()
        val upstream = HttpUpstream()
        return try { FutbolioEngine(host, config.sources, poster, upstream, activity, config.presentation) }
        catch (failure: Throwable) { upstream.close(); throw failure }
    }
}
