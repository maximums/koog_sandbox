package io.github.maximums.web

import arrow.core.Either

class JinaReaderFetcher : PageFetcher {
    override suspend fun fetch(request: PageRequest): Either<ApiError, Map<String, Either<Unreadable, Page>>> =
        TODO("GET https://r.jina.ai/<url> for each URL and read the Markdown response")
}
