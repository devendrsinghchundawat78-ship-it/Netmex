# Cloudstream repository compatibility (Android Full)

Netmex's Android **Full** distribution can import Cloudstream repository manifests alongside Netmex JavaScript scraper repositories.

## Supported input

Paste an explicit Cloudstream repository JSON URL in **Settings → Plugins**, for example:

```text
https://raw.githubusercontent.com/recloudstream/extensions/master/repo.json
```

The importer supports the standard Cloudstream repository format:

- `name`, `description`, `manifestVersion`
- one or more `pluginLists`
- standard `plugins.json` entries and `.cs3` URLs
- SHA-256 verification through `fileHash`
- `cloudstreamrepo://` and `https://cs.repo/` deep-link forms when they contain a full repository URL

Every repository manifest using this standard format can be listed. Extensions are downloaded lazily only when Netmex requests streams from them.

## Runtime behavior

1. Netmex resolves the selected title from its TMDB ID.
2. The `.cs3` file is downloaded and its SHA-256 hash is checked.
3. The extension is loaded with Android `PathClassLoader` using Cloudstream's official binary-compatible API.
4. Registered `MainAPI` providers are searched by title.
5. Movie data or the requested season/episode is loaded.
6. Links, headers, quality, format and subtitles are converted to Netmex stream results.

A TMDB API key is required because Cloudstream providers generally search by title while Netmex's plugin pipeline starts with an ID.

## Compatibility boundary

The bridge targets Cloudstream 4.7's stable provider ABI (Kotlin 2.3, matching Netmex). Standard provider/extractor extensions are the target. Extensions that depend on Cloudstream app UI internals, custom activities/fragments, app-only settings APIs, click actions, authentication UI, or newer prerelease-only APIs may not work. Android dex extensions cannot run on iOS.

Netmex does not bundle third-party repositories or extensions. Users are responsible for the repositories they add and for complying with applicable laws and service terms.
