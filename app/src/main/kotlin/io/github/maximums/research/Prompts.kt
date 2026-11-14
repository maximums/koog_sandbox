package io.github.maximums.research

/**
 * Loads the prompt [name] from the `prompts` resource folder. A missing prompt is a packaging mistake, not an
 * expected failure, so it throws.
 */
internal fun loadPrompt(name: String): String {
    val path = "/prompts/$name"
    val resource = object {}.javaClass.getResource(path) ?: error("Prompt resource $path is missing")
    return resource.readText().trim()
}
