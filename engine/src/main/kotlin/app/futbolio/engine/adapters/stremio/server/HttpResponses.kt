package app.futbolio.engine.adapters.stremio.server

import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.serialization.json.*

private val jsonCodec = Json { prettyPrint = true }

internal suspend fun respondHead(call: ApplicationCall, code: Int, type: ContentType, length: Long?) {
    call.respond(object : OutgoingContent.NoContent() {
        override val status = HttpStatusCode.fromValue(code)
        override val contentType = type
        override val contentLength = length
    })
}

internal suspend fun text(call: ApplicationCall, code: Int, value: String, type: ContentType = ContentType.Text.Plain) {
    if (call.request.httpMethod == HttpMethod.Head) respondHead(call, code, type, value.toByteArray().size.toLong())
    else call.respondText(value, type, HttpStatusCode.fromValue(code))
}

internal suspend fun json(call: ApplicationCall, value: JsonElement, code: Int = 200) =
    text(call, code, jsonCodec.encodeToString(JsonElement.serializer(), value), ContentType.Application.Json)
