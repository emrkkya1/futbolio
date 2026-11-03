package app.futbolio.application.resolution

import app.futbolio.application.ports.StreamChoice
import app.futbolio.domain.identity.StableIdentity
import app.futbolio.domain.provider.SourceUrl

fun deduplicateStreams(choices: List<StreamChoice>): List<StreamChoice> {
    fun identity(choice: StreamChoice): String {
        if (choice.externalUrl.isNotEmpty()) return "website|${choice.externalUrl}"
        val uri = SourceUrl.parse(choice.url)
        val query = uri.rawQuery?.split('&')?.sorted()?.joinToString("&").orEmpty()
        val headers = choice.headers
        val profile = StableIdentity.hash("${headers.referer}\n${headers.origin}\n${headers.extra.toSortedMap()}\n${headers.authOrigin}\n${headers.authToken}")
        return "${uri.scheme.lowercase()}://${uri.rawAuthority.lowercase()}${uri.rawPath}?$query|$profile|${choice.session?.id}"
    }
    return choices.groupBy(::identity).values.map { group -> group.first().copy(providers = group.flatMap { it.providers }.distinct()) }
}
