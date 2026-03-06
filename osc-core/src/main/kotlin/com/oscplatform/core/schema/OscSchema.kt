package com.oscplatform.core.schema

data class OscArgSpec(
    val name: String,
    val type: OscType,
)

data class OscMessageSpec(
    val path: String,
    val name: String,
    val description: String?,
    val args: List<OscArgSpec>,
)

data class OscSchema(
    val messages: List<OscMessageSpec>,
) {
    private val byPath: Map<String, OscMessageSpec> = messages.associateBy { normalizePath(it.path) }
    private val byName: Map<String, OscMessageSpec> = messages.associateBy { it.name }

    init {
        require(messages.isNotEmpty()) { "Schema must define at least one message" }

        val duplicatePaths = messages.groupBy { normalizePath(it.path) }.filterValues { it.size > 1 }.keys
        require(duplicatePaths.isEmpty()) { "Duplicate OSC paths: ${duplicatePaths.joinToString()}" }

        val duplicateNames = messages.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicateNames.isEmpty()) { "Duplicate message names: ${duplicateNames.joinToString()}" }
    }

    fun resolveMessage(ref: String): OscMessageSpec? {
        val normalizedRef = ref.trim()
        return if (normalizedRef.startsWith("/")) {
            byPath[normalizePath(normalizedRef)]
        } else {
            byName[normalizedRef]
        }
    }

    fun findByPath(path: String): OscMessageSpec? = byPath[normalizePath(path)]

    companion object {
        fun normalizePath(path: String): String {
            val cleaned = path.trim().trimEnd('/').ifEmpty { "/" }
            return if (cleaned.startsWith("/")) cleaned else "/$cleaned"
        }
    }
}

object OscNaming {
    fun defaultMessageName(path: String): String {
        val normalized = OscSchema.normalizePath(path)
        val body = normalized.removePrefix("/")
        require(body.isNotBlank()) { "OSC path '/' cannot be converted into a message name" }
        return body.split('/').filter { it.isNotBlank() }.joinToString(".")
    }

    fun mcpToolName(path: String): String {
        val normalized = OscSchema.normalizePath(path)
        val body = normalized.removePrefix("/")
        require(body.isNotBlank()) { "OSC path '/' cannot be converted into an MCP tool name" }
        return "set_" + body.split('/').filter { it.isNotBlank() }.joinToString("_")
    }
}
