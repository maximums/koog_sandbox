package io.github.maximums.web

class JinaReaderFetcher : PageFetcher {
    override suspend fun fetch(url: String): Page =
        TODO("GET https://r.jina.ai/<url> and read the Markdown response")
}
