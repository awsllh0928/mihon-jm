package eu.kanade.tachiyomi.extension.zh.jm

import eu.kanade.tachiyomi.source.SourceFactory

class JmFactory : SourceFactory {
    override fun createSources() = listOf(Jm())
}
