package com.oscplatform.adapter.mcp

import com.oscplatform.core.transport.OscPacket
import com.oscplatform.core.transport.OscTarget
import com.oscplatform.core.transport.OscTransport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.KotlinModule

/**
 * prompts/list と prompts/get の統合テスト。
 * ツール強制ルーターのプロンプトが正しく公開されることを検証する。
 */
class McpPromptsIntegrationTest {

    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    private val schemaYaml =
        """
        messages:
          - path: /light/color
            description: set RGB color
            args:
              - name: r
                kind: scalar
                type: int
        """.trimIndent()

    // -------------------------------------------------------------------------
    // initialize
    // -------------------------------------------------------------------------

    @Test
    fun initializeAdvertisesPromptsCapability() {
        val schemaFile = Files.createTempFile("osc-test-schema", ".yaml")
        try {
            schemaFile.toFile().writeText(schemaYaml)
            val responses = runServer(
                schemaFile = schemaFile,
                inputJson = listOf(
                    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{}}}""",
                ),
            )
            assertEquals(1, responses.size)
            val caps = responses[0].path("result").path("capabilities")
            assertNotNull(caps.get("tools"), "capabilities.tools が存在すること")
            assertNotNull(caps.get("prompts"), "capabilities.prompts が存在すること")
        } finally {
            Files.deleteIfExists(schemaFile)
        }
    }

    // -------------------------------------------------------------------------
    // prompts/list
    // -------------------------------------------------------------------------

    @Test
    fun promptsListReturnsToolForceRouterPrompt() {
        val schemaFile = Files.createTempFile("osc-test-schema", ".yaml")
        try {
            schemaFile.toFile().writeText(schemaYaml)
            val responses = runServer(
                schemaFile = schemaFile,
                inputJson = listOf(
                    """{"jsonrpc":"2.0","id":1,"method":"prompts/list","params":{}}""",
                ),
            )
            assertEquals(1, responses.size)
            val response = responses[0]
            assertNull(response.get("error"), "エラーがないこと")

            val prompts = response.path("result").path("prompts")
            assertTrue(prompts.isArray, "prompts は配列であること")
            assertEquals(1, prompts.size(), "プロンプトが1件あること")

            val prompt = prompts[0]
            assertEquals(McpQueryRouter.PROMPT_NAME, prompt.path("name").stringValue())
            assertTrue(
                (prompt.path("description").stringValue() ?: "").isNotBlank(),
                "description が空でないこと",
            )
        } finally {
            Files.deleteIfExists(schemaFile)
        }
    }

    // -------------------------------------------------------------------------
    // prompts/get
    // -------------------------------------------------------------------------

    @Test
    fun promptsGetReturnsSystemPromptMessages() {
        val schemaFile = Files.createTempFile("osc-test-schema", ".yaml")
        try {
            schemaFile.toFile().writeText(schemaYaml)
            val promptName = McpQueryRouter.PROMPT_NAME
            val responses = runServer(
                schemaFile = schemaFile,
                inputJson = listOf(
                    """{"jsonrpc":"2.0","id":1,"method":"prompts/get","params":{"name":"$promptName"}}""",
                ),
            )
            assertEquals(1, responses.size)
            val response = responses[0]
            assertNull(response.get("error"), "エラーがないこと")

            val messages = response.path("result").path("messages")
            assertTrue(messages.isArray, "messages は配列であること")
            assertTrue(messages.size() > 0, "messages が1件以上あること")

            val first = messages[0]
            assertEquals("user", first.path("role").stringValue(), "role が user であること")
            val text = first.path("content").path("text").stringValue() ?: ""
            assertTrue(text.contains("最近"), "プロンプトに「最近」が含まれること")
            assertTrue(text.contains("禁止"), "プロンプトに禁止事項が含まれること")
        } finally {
            Files.deleteIfExists(schemaFile)
        }
    }

    @Test
    fun promptsGetWithUnknownNameReturnsError() {
        val schemaFile = Files.createTempFile("osc-test-schema", ".yaml")
        try {
            schemaFile.toFile().writeText(schemaYaml)
            val responses = runServer(
                schemaFile = schemaFile,
                inputJson = listOf(
                    """{"jsonrpc":"2.0","id":1,"method":"prompts/get","params":{"name":"nonexistent_prompt"}}""",
                ),
            )
            assertEquals(1, responses.size)
            val response = responses[0]
            assertNull(response.get("result"), "result がないこと")
            val error = response.path("error")
            assertEquals(-32602, error.path("code").asInt(), "エラーコードが -32602 であること")
            assertTrue(
                (error.path("message").stringValue() ?: "").contains("nonexistent_prompt"),
                "エラーメッセージに不明なプロンプト名が含まれること",
            )
        } finally {
            Files.deleteIfExists(schemaFile)
        }
    }

    // -------------------------------------------------------------------------
    // ヘルパー
    // -------------------------------------------------------------------------

    private fun buildFrame(json: String): ByteArray {
        val payload = json.toByteArray(StandardCharsets.UTF_8)
        val header = "Content-Length: ${payload.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
        return header + payload
    }

    private fun parseFrames(bytes: ByteArray): List<ObjectNode> {
        val input = ByteArrayInputStream(bytes)
        val frames = mutableListOf<ObjectNode>()
        while (true) {
            var contentLength = -1
            while (true) {
                val line = readHeaderLine(input) ?: return frames
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon >= 0) {
                    val key = line.substring(0, colon).trim()
                    val value = line.substring(colon + 1).trim()
                    if (key.equals("Content-Length", ignoreCase = true)) {
                        contentLength = value.toIntOrNull() ?: -1
                    }
                }
            }
            if (contentLength <= 0) return frames
            val payload = ByteArray(contentLength)
            var offset = 0
            while (offset < contentLength) {
                val n = input.read(payload, offset, contentLength - offset)
                if (n < 0) return frames
                offset += n
            }
            frames += mapper.readTree(payload) as ObjectNode
        }
    }

    private fun readHeaderLine(input: ByteArrayInputStream): String? {
        val bytes = mutableListOf<Byte>()
        while (true) {
            val b = input.read()
            if (b < 0) return if (bytes.isEmpty()) null else bytes.toByteArray().toString(StandardCharsets.US_ASCII)
            if (b == '\r'.code) {
                val next = input.read()
                // next が -1 の場合（\r が末尾）は \n がないため無視して続行
                if (next >= 0 && next != '\n'.code) {
                    // \r の後に \n 以外が来た場合は bytes に積み直す（異常ケース）
                    bytes += next.toByte()
                }
                return bytes.toByteArray().toString(StandardCharsets.US_ASCII)
            }
            bytes += b.toByte()
        }
    }

    private fun runServer(
        schemaFile: java.nio.file.Path,
        inputJson: List<String>,
    ): List<ObjectNode> {
        val inputBytes = inputJson.map { buildFrame(it) }.fold(ByteArray(0)) { acc, b -> acc + b }
        val input = ByteArrayInputStream(inputBytes)
        val output = ByteArrayOutputStream()
        runBlocking {
            McpAdapter().execute(
                args = listOf(
                    "--schema=${schemaFile.toAbsolutePath()}",
                    "--host=127.0.0.1",
                    "--port=9000",
                ),
                input = input,
                output = output,
                transport = NoopOscTransport(),
            )
        }
        return parseFrames(output.toByteArray())
    }
}

private class NoopOscTransport : OscTransport {
    override val incomingPackets: Flow<OscPacket> = MutableSharedFlow()
    override suspend fun start() = Unit
    override suspend fun stop() = Unit
    override suspend fun send(packet: OscPacket, target: OscTarget) = Unit
}
