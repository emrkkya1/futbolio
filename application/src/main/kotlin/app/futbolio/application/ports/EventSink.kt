package app.futbolio.application.ports

fun interface EventSink { fun event(message: String) }
