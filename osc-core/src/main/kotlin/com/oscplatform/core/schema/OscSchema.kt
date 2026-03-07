package com.oscplatform.core.schema

data class OscMessageSpec(
    val path: String,
    val name: String,
    val description: String?,
    val args: List<OscArgNode>,
)

data class OscBundleSpec(
    val name: String,
    val description: String?,
    val messageRefs: List<String>,
)

data class OscSchema(
    val messages: List<OscMessageSpec>,
    val bundles: List<OscBundleSpec> = emptyList(),
) {
    private val byPath: Map<String, OscMessageSpec> = messages.associateBy { normalizePath(it.path) }
    private val byName: Map<String, OscMessageSpec> = messages.associateBy { it.name }
    private val bundleByName: Map<String, OscBundleSpec> = bundles.associateBy { it.name }

    init {
        require(messages.isNotEmpty()) { "Schema must define at least one message" }

        val duplicatePaths = messages.groupBy { normalizePath(it.path) }.filterValues { it.size > 1 }.keys
        require(duplicatePaths.isEmpty()) { "Duplicate OSC paths: ${duplicatePaths.joinToString()}" }

        val duplicateNames = messages.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicateNames.isEmpty()) { "Duplicate message names: ${duplicateNames.joinToString()}" }

        messages.forEach { message ->
            OscArgNodeValidator.validate(normalizePath(message.path), message.args)
        }

        val duplicateBundleNames = bundles.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicateBundleNames.isEmpty()) { "Duplicate bundle names: ${duplicateBundleNames.joinToString()}" }

        bundles.forEach { bundle ->
            require(bundle.name.isNotBlank()) { "Bundle name cannot be blank" }
            require(bundle.messageRefs.isNotEmpty()) { "Bundle '${bundle.name}' must reference at least one message" }

            val seenArgNames = mutableSetOf<String>()
            bundle.messageRefs.forEach { ref ->
                val spec = resolveMessage(ref)
                    ?: throw IllegalArgumentException(
                        "Bundle '${bundle.name}' references unknown message '$ref'",
                    )
                spec.args.forEach { argNode ->
                    require(seenArgNames.add(argNode.name)) {
                        "Bundle '${bundle.name}': arg name collision '${argNode.name}' across messages"
                    }
                }
            }
        }
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

    fun findBundle(name: String): OscBundleSpec? = bundleByName[name]

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

    fun bundleToolName(name: String): String {
        require(name.isNotBlank()) { "Bundle name cannot be blank" }
        return "bundle_" + name.trim().replace(Regex("[^a-zA-Z0-9_]"), "_")
    }
}
