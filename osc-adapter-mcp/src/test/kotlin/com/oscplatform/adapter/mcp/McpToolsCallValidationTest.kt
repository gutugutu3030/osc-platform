package com.oscplatform.adapter.mcp

import com.oscplatform.core.transport.OscPacket
import com.oscplatform.core.transport.OscTarget
import com.oscplatform.core.transport.OscTransport
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking

/**
 * tools/call における引数バリデーションを検証する統合テスト。
 *
 * stdio transport を通して MCP クライアントからツールを呼び出し、 不正な引数が渡された場合に isError = true の結果が返ることを確認する。
 */
class McpToolsCallValidationTest {

  private val schemaYaml =
      """
        messages:
          - path: /synth/volume
            description: set synth volume level
            args:
              - name: level
                kind: scalar
                type: int
              - name: channel
                kind: scalar
                type: int
          - path: /display/brightness
            description: set display brightness
            args:
              - name: value
                kind: scalar
                type: float
          - path: /device/flag
            description: set device flag
            args:
              - name: enabled
                kind: scalar
                type: bool
    """
          .trimIndent()

  /**
   * required 引数がすべて欠落している場合、isError = true の結果が返る。
   *
   * 空の引数マップを送り、必須パラメータの検証ロジックを確認する。
   */
  @Test
  fun callToolWithAllRequiredArgsMissingReturnsError() = runBlocking {
    val schemaFile = Files.createTempFile("osc-test-schema", ".yaml")
    try {
      schemaFile.toFile().writeText(schemaYaml)

      withStdioMcpClient(
          schemaFile = schemaFile,
          transport = RecordingTransportForValidation(),
      ) { client ->
        val toolResult =
            client.callTool(
                name = "set_synth_volume",
                arguments = emptyMap<String, Any>(),
            )

        assertTrue(toolResult.isError == true)
      }
    } finally {
      Files.deleteIfExists(schemaFile)
    }
  }

  /**
   * required 引数の一部だけ指定した場合、isError = true の結果が返る。
   *
   * level のみ指定し、channel を欠落させるケースを検証する。
   */
  @Test
  fun callToolWithPartialRequiredArgsMissingReturnsError() = runBlocking {
    val schemaFile = Files.createTempFile("osc-test-schema", ".yaml")
    try {
      schemaFile.toFile().writeText(schemaYaml)

      withStdioMcpClient(
          schemaFile = schemaFile,
          transport = RecordingTransportForValidation(),
      ) { client ->
        val toolResult =
            client.callTool(
                name = "set_synth_volume",
                arguments = mapOf("level" to 80),
            )

        assertTrue(toolResult.isError == true)
      }
    } finally {
      Files.deleteIfExists(schemaFile)
    }
  }

  /**
   * int 型引数に文字列を渡した場合、isError = true の結果が返る。
   *
   * 型変換が失敗してエラーとなるケースを検証する。
   */
  @Test
  fun callToolWithTypeMismatchStringForIntReturnsError() = runBlocking {
    val schemaFile = Files.createTempFile("osc-test-schema", ".yaml")
    try {
      schemaFile.toFile().writeText(schemaYaml)

      withStdioMcpClient(
          schemaFile = schemaFile,
          transport = RecordingTransportForValidation(),
      ) { client ->
        val toolResult =
            client.callTool(
                name = "set_synth_volume",
                arguments = mapOf("level" to "not_a_number", "channel" to 1),
            )

        assertTrue(toolResult.isError == true)
        val content = assertIs<TextContent>(toolResult.content.single())
        assertTrue(content.text.isNotEmpty())
      }
    } finally {
      Files.deleteIfExists(schemaFile)
    }
  }

  /**
   * float 型引数に文字列を渡した場合、isError = true の結果が返る。
   *
   * float への型変換失敗を検証する。
   */
  @Test
  fun callToolWithTypeMismatchStringForFloatReturnsError() = runBlocking {
    val schemaFile = Files.createTempFile("osc-test-schema", ".yaml")
    try {
      schemaFile.toFile().writeText(schemaYaml)

      withStdioMcpClient(
          schemaFile = schemaFile,
          transport = RecordingTransportForValidation(),
      ) { client ->
        val toolResult =
            client.callTool(
                name = "set_display_brightness",
                arguments = mapOf("value" to "bright"),
            )

        assertTrue(toolResult.isError == true)
        val content = assertIs<TextContent>(toolResult.content.single())
        assertTrue(content.text.isNotEmpty())
      }
    } finally {
      Files.deleteIfExists(schemaFile)
    }
  }

  /**
   * bool 型引数に文字列を渡した場合、isError = true の結果が返る。
   *
   * bool への型変換失敗を検証する。
   */
  @Test
  fun callToolWithTypeMismatchStringForBoolReturnsError() = runBlocking {
    val schemaFile = Files.createTempFile("osc-test-schema", ".yaml")
    try {
      schemaFile.toFile().writeText(schemaYaml)

      withStdioMcpClient(
          schemaFile = schemaFile,
          transport = RecordingTransportForValidation(),
      ) { client ->
        val toolResult =
            client.callTool(
                name = "set_device_flag",
                arguments = mapOf("enabled" to "not_a_bool"),
            )

        assertTrue(toolResult.isError == true)
        val content = assertIs<TextContent>(toolResult.content.single())
        assertTrue(content.text.isNotEmpty())
      }
    } finally {
      Files.deleteIfExists(schemaFile)
    }
  }

  /**
   * 正しい引数をすべて指定した場合、isError でない成功結果が返る。
   *
   * バリデーション異常系と対比するための正常系テスト。
   */
  @Test
  fun callToolWithAllValidArgsReturnsSuccess() = runBlocking {
    val schemaFile = Files.createTempFile("osc-test-schema", ".yaml")
    try {
      schemaFile.toFile().writeText(schemaYaml)

      withStdioMcpClient(
          schemaFile = schemaFile,
          transport = RecordingTransportForValidation(),
      ) { client ->
        val toolResult =
            client.callTool(
                name = "set_synth_volume",
                arguments = mapOf("level" to 80, "channel" to 1),
            )

        assertTrue(toolResult.isError != true)
        val content = assertIs<TextContent>(toolResult.content.single())
        assertTrue(content.text.contains("/synth/volume"))
      }
    } finally {
      Files.deleteIfExists(schemaFile)
    }
  }
}

/** 送信パケットを記録するフェイクトランスポート。 */
private class RecordingTransportForValidation : OscTransport {
  override val incomingPackets: Flow<OscPacket> = MutableSharedFlow()

  override suspend fun start() = Unit

  override suspend fun stop() = Unit

  override suspend fun send(packet: OscPacket, target: OscTarget) = Unit
}
