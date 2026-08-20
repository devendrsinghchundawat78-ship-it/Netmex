package com.nuvio.app.features.plugins

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import com.google.gson.Gson
import com.lagradost.api.setContext
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TorrentLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.tmdb.TmdbService
import dalvik.system.PathClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStreamReader
import java.net.URI
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private val cloudstreamJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class CloudstreamRepositoryManifest(
    val name: String,
    val description: String? = null,
    val manifestVersion: Int = 1,
    val pluginLists: List<String> = emptyList(),
)

@Serializable
private data class CloudstreamSitePlugin(
    val url: String,
    val status: Int = 1,
    val version: Int = 1,
    val apiVersion: Int = 1,
    val name: String,
    val internalName: String,
    val authors: List<String> = emptyList(),
    val description: String? = null,
    val repositoryUrl: String? = null,
    val tvTypes: List<String>? = null,
    val language: String? = null,
    val iconUrl: String? = null,
    val fileSize: Long? = null,
    val fileHash: String? = null,
)

internal object CloudstreamPluginBridge {
    private val loadMutex = Mutex()
    private val loadedProviders = mutableMapOf<String, List<MainAPI>>()
    private val loadedPlugins = mutableMapOf<String, BasePlugin>()
    private var appContext: Context? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        // Initializes NiceHttp's Android context used by the official Cloudstream API library.
        setContext(WeakReference(context.applicationContext as Any))
    }

    suspend fun providersFor(descriptor: CloudstreamExtensionDescriptor): List<MainAPI> =
        loadMutex.withLock {
            val key = descriptor.cacheKey()
            loadedProviders[key]?.let { return@withLock it }
            val context = checkNotNull(appContext) { "Cloudstream bridge is not initialized" }
            val file = extensionFile(context, descriptor)
            if (!file.isFile || !hashMatches(file, descriptor.fileHash)) {
                downloadExtension(descriptor, file)
            }
            val providers = loadExtension(context, file)
            loadedProviders[key] = providers
            providers
        }

    private suspend fun downloadExtension(descriptor: CloudstreamExtensionDescriptor, target: File) =
        withContext(Dispatchers.IO) {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "${target.name}.download")
            val request = Request.Builder().url(descriptor.pluginUrl).get().build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Cloudstream extension download failed: HTTP ${response.code}" }
                val body = checkNotNull(response.body) { "Cloudstream extension response was empty" }
                temp.outputStream().use { output -> body.byteStream().copyTo(output) }
            }
            check(hashMatches(temp, descriptor.fileHash)) { "Cloudstream extension SHA-256 check failed" }
            if (target.exists()) target.delete()
            check(temp.renameTo(target)) { "Could not store Cloudstream extension" }
        }

    private fun loadExtension(context: Context, file: File): List<MainAPI> {
        file.setReadOnly()
        val loader = PathClassLoader(file.absolutePath, context.classLoader)
        val manifest = loader.getResourceAsStream("manifest.json").use { stream ->
            checkNotNull(stream) { "Cloudstream extension has no manifest.json" }
            InputStreamReader(stream).use { Gson().fromJson(it, BasePlugin.Manifest::class.java) }
        }
        val className = checkNotNull(manifest.pluginClassName) { "Cloudstream pluginClassName is missing" }
        val plugin = loader.loadClass(className).getDeclaredConstructor().newInstance() as BasePlugin
        plugin.filename = file.absolutePath

        if (manifest.requiresResources && plugin is Plugin) {
            @Suppress("DEPRECATION")
            val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
            AssetManager::class.java.getMethod("addAssetPath", String::class.java)
                .invoke(assets, file.absolutePath)
            @Suppress("DEPRECATION")
            plugin.resources = Resources(
                assets,
                context.resources.displayMetrics,
                context.resources.configuration,
            )
        }

        if (plugin is Plugin) plugin.load(context) else plugin.load()
        loadedPlugins[file.absolutePath] = plugin
        return APIHolder.apis.filter { it.sourcePlugin == file.absolutePath }.onEach { it.init() }
    }

    fun removeRepository(manifestUrl: String) {
        val context = appContext ?: return
        val directory = File(context.filesDir, "nuvio_cloudstream/${sha256Text(manifestUrl).take(20)}")
        loadedProviders.keys.removeAll { it.startsWith("$manifestUrl|") }
        directory.deleteRecursively()
    }

    private fun extensionFile(context: Context, descriptor: CloudstreamExtensionDescriptor): File {
        val repo = sha256Text(descriptor.repositoryUrl).take(20)
        val safeName = descriptor.internalName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(context.filesDir, "nuvio_cloudstream/$repo/${safeName}-${descriptor.version}.cs3")
    }

    private fun CloudstreamExtensionDescriptor.cacheKey(): String =
        "$repositoryUrl|$internalName|$version"

    private fun hashMatches(file: File, expected: String?): Boolean {
        if (expected.isNullOrBlank()) return file.isFile && file.length() > 0
        val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        return expected.removePrefix("sha256-").equals(actual, ignoreCase = true)
    }

    private fun sha256Text(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
}

internal suspend fun parseCloudstreamRepository(
    manifestUrl: String,
    payload: String,
    previousScrapers: Map<String, PluginScraper>,
): Pair<PluginRepositoryItem, List<PluginScraper>>? {
    val manifest = runCatching {
        cloudstreamJson.decodeFromString<CloudstreamRepositoryManifest>(payload)
    }.getOrNull() ?: return null
    if (manifest.pluginLists.isEmpty()) return null

    val plugins = manifest.pluginLists.flatMap { listUrl ->
        val resolvedListUrl = resolveCloudstreamUrl(manifestUrl, listUrl)
        runCatching {
            cloudstreamJson.decodeFromString<List<CloudstreamSitePlugin>>(httpGetText(resolvedListUrl))
                .map { plugin -> plugin to resolvedListUrl }
        }.getOrElse { emptyList() }
    }.distinctBy { (plugin, _) -> plugin.internalName }

    val scrapers = plugins.map { (site, pluginListUrl) ->
        val id = "${manifestUrl.lowercase()}:cloudstream:${site.internalName}"
        val descriptor = CloudstreamExtensionDescriptor(
            pluginUrl = resolveCloudstreamUrl(pluginListUrl, site.url),
            internalName = site.internalName,
            displayName = site.name,
            version = site.version,
            fileHash = site.fileHash,
            repositoryUrl = manifestUrl,
            language = site.language,
            tvTypes = site.tvTypes.orEmpty(),
        )
        val available = site.status != 0
        PluginScraper(
            id = id,
            repositoryUrl = manifestUrl,
            name = site.name,
            description = site.description.orEmpty(),
            version = site.version.toString(),
            filename = site.url,
            supportedTypes = cloudstreamTypes(site.tvTypes),
            enabled = available && (previousScrapers[id]?.enabled ?: true),
            manifestEnabled = available,
            logo = site.iconUrl,
            contentLanguage = listOfNotNull(site.language),
            code = descriptor.toPluginCode(),
        )
    }

    return PluginRepositoryItem(
        manifestUrl = manifestUrl,
        name = manifest.name,
        description = manifest.description,
        version = manifest.manifestVersion.toString(),
        scraperCount = scrapers.size,
        lastUpdated = System.currentTimeMillis(),
    ) to scrapers
}

internal suspend fun executeCloudstreamExtension(
    descriptor: CloudstreamExtensionDescriptor,
    tmdbId: String,
    mediaType: String,
    season: Int?,
    episode: Int?,
): List<PluginRuntimeResult> = withContext(Dispatchers.IO) {
    val metadata = TmdbService.pluginMediaMetadata(tmdbId, mediaType)
        ?: error("TMDB API key and valid media metadata are required for Cloudstream providers")
    val providers = CloudstreamPluginBridge.providersFor(descriptor)
    check(providers.isNotEmpty()) { "The Cloudstream extension did not register a provider" }

    val imdbId = tmdbId.toIntOrNull()?.let { id ->
        runCatching { TmdbService.tmdbToImdb(id, mediaType) }.getOrNull()
    }
    val results = mutableListOf<PluginRuntimeResult>()
    val failures = mutableListOf<Throwable>()
    for (provider in providers) {
        runCatching {
            // Prefer a provider's stable sync-ID route. It avoids fragile title matching when supported.
            val directLoadUrl = imdbId
                ?.takeIf { SyncIdName.Imdb in provider.supportedSyncNames }
                ?.let { id -> runCatching { provider.getLoadUrl(SyncIdName.Imdb, id) }.getOrNull() }
            val loaded = if (!directLoadUrl.isNullOrBlank()) {
                provider.load(directLoadUrl)
            } else {
                val queries = listOfNotNull(
                    metadata.title,
                    metadata.year?.let { "${metadata.title} $it" },
                ).distinct()
                val searchResults = mutableListOf<com.lagradost.cloudstream3.SearchResponse>()
                for (query in queries) {
                    searchResults += runCatching { provider.search(query).orEmpty() }
                        .getOrDefault(emptyList())
                }
                val search = searchResults.distinctBy { it.url }
                val selected = search.firstOrNull {
                    normalizeTitle(it.name) == normalizeTitle(metadata.title)
                } ?: search.firstOrNull()
                    ?: error("${provider.name}: no search match for ${metadata.title}")
                provider.load(selected.url)
            } ?: error("${provider.name}: details could not be loaded")
            val data = when (loaded) {
                is MovieLoadResponse -> loaded.dataUrl
                is LiveStreamLoadResponse -> loaded.dataUrl
                is TvSeriesLoadResponse -> loaded.episodes.selectEpisode(season, episode)?.data
                is AnimeLoadResponse -> loaded.episodes.values.flatten().selectEpisode(season, episode)?.data
                is TorrentLoadResponse -> loaded.magnet ?: loaded.torrent
                else -> null
            } ?: return@runCatching

            val subtitles = mutableListOf<PluginSubtitleResult>()
            val links = mutableListOf<ExtractorLink>()
            provider.loadLinks(
                data = data,
                isCasting = false,
                subtitleCallback = { subtitle ->
                    subtitles += PluginSubtitleResult(
                        url = subtitle.url,
                        language = subtitle.lang,
                        headers = subtitle.headers,
                    )
                },
                callback = { links += it },
            )
            check(links.isNotEmpty()) { "${provider.name}: no playable links were extracted" }
            results += links.map { link -> link.toNetmexResult(provider.name, subtitles) }
        }.onFailure(failures::add)
    }
    if (results.isEmpty() && failures.isNotEmpty()) throw failures.first()
    results.distinctBy { it.url }
}

internal fun removeCloudstreamRepository(manifestUrl: String) {
    CloudstreamPluginBridge.removeRepository(manifestUrl)
}

private fun List<com.lagradost.cloudstream3.Episode>.selectEpisode(
    season: Int?,
    episode: Int?,
): com.lagradost.cloudstream3.Episode? =
    firstOrNull { candidate ->
        (season == null || candidate.season == season) &&
            (episode == null || candidate.episode == episode)
    } ?: firstOrNull { candidate -> episode != null && candidate.episode == episode }
        ?: firstOrNull()

private fun ExtractorLink.toNetmexResult(
    providerName: String,
    subtitles: List<PluginSubtitleResult>,
): PluginRuntimeResult {
    val allHeaders = if (referer.isBlank() || headers.keys.any { it.equals("referer", true) }) {
        headers
    } else {
        headers + ("Referer" to referer)
    }
    val format = when (type) {
        ExtractorLinkType.M3U8 -> "hls"
        ExtractorLinkType.DASH -> "dash"
        ExtractorLinkType.TORRENT -> "torrent"
        ExtractorLinkType.MAGNET -> "magnet"
        else -> "video"
    }
    return PluginRuntimeResult(
        title = name,
        name = name,
        url = url,
        quality = quality.takeIf { it > 0 }?.let { "${it}p" },
        provider = providerName,
        type = format,
        headers = allHeaders,
        subtitles = subtitles,
    )
}

private fun cloudstreamTypes(values: List<String>?): List<String> {
    if (values.isNullOrEmpty()) return listOf("movie", "tv")
    val types = values.mapNotNull { value ->
        when (value.lowercase()) {
            "movie", "asian drama", "documentary", "torrent" -> "movie"
            "tvseries", "tv series", "series", "anime", "ova", "cartoon" -> "tv"
            else -> null
        }
    }.distinct()
    return types.ifEmpty { listOf("movie", "tv") }
}

private fun resolveCloudstreamUrl(baseUrl: String, child: String): String {
    if (child.startsWith("http://") || child.startsWith("https://")) return child
    return URI(baseUrl).resolve(child).toString()
}

private fun normalizeTitle(value: String): String =
    value.lowercase().filter(Char::isLetterOrDigit)
