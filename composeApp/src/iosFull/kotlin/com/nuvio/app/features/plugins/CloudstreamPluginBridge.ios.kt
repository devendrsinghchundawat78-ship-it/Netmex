package com.nuvio.app.features.plugins

/** Cloudstream extensions are Android/JVM dex files and cannot execute on iOS. */
internal suspend fun parseCloudstreamRepository(
    manifestUrl: String,
    payload: String,
    previousScrapers: Map<String, PluginScraper>,
): Pair<PluginRepositoryItem, List<PluginScraper>>? = null

internal suspend fun executeCloudstreamExtension(
    descriptor: CloudstreamExtensionDescriptor,
    tmdbId: String,
    mediaType: String,
    season: Int?,
    episode: Int?,
): List<PluginRuntimeResult> = throw UnsupportedOperationException(
    "Cloudstream extensions are available only in the Android full build.",
)

internal fun removeCloudstreamRepository(manifestUrl: String) = Unit
