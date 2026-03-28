package com.oscplatform.adapter.mcp

import com.oscplatform.core.transport.OscMessagePacket
import com.oscplatform.core.transport.OscPacket
import com.oscplatform.core.transport.OscTarget
import com.oscplatform.core.transport.OscTransport
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking

/**
 * MCP 操作が WebUI SSE エンドポイントにイベントを発行することを検証する統合テスト。
 *
 * McpAdapter の execute() は webUiEventSink を作成し、`--webui` 指定時に WebUiServer へ additionalEvents
 * として渡す。MCP リクエスト（tools/list, tools/call）ごとに mcp_request / mcp_success / mcp_failure イベントが SSE
 * `/api/events` に流れることを確認する。
 */
class McpWebUiEventBridgeTest {

  private val schemaYaml =
      """
        messages:
          - path: /light/color
            description: set RGB color
            args:
              - name: r
                kind: scalar
                type: int
              - name: g
                kind: scalar
                type: int
              - name: b
                kind: scalar
                type: int
        """
          .trimIndent()

  /** tools/list リクエストが SSE イベントとして mcp_request / tools/list で配信されることを検証する。 */
  @Test
  fun toolsListEmitsMcpRequestEvent() = runBlocking {
    val webUiPort = allocateFreePort()
    val schemaFile = Files.createTempFile("osc-webui-event-schema", ".yaml")
    try {
      schemaFile.toFile().writeText(schemaYaml)
      val transport = WebUiRecordingTransport()

      withStdioMcpClient(
          schemaFile = schemaFile,
          transport = transport,
          extraArgs = listOf("--webui", "--webui-port", webUiPort.toString()),
      ) { client ->
        // SSE 接続をバックグラウンドスレッドで開始
        val sseThread = launchSseCollector(port = webUiPort, maxEvents = 5, timeoutMs = 8000)

        // WebUI サーバーの起動待ち
        Thread.sleep(1000)

        // tools/list を実行
        client.listTools()

        // イベント伝播待ち
        Thread.sleep(1500)

        val events = sseThread.awaitEvents()

        // mcp_request イベントで tools/list メッセージが含まれること
        val hasToolsListRequest =
            events.any { it.contains("mcp_request") && it.contains("tools/list") }
        assertTrue(
            hasToolsListRequest,
            "Expected SSE event with type=mcp_request and message=tools/list but got: $events",
        )
      }
    } finally {
      Files.deleteIfExists(schemaFile)
    }
  }

  /** tools/call の成功時に mcp_request と mcp_success の両方の SSE イベントが配信されることを検証する。 */
  @Test
  fun toolsCallSuccessEmitsMcpRequestAndSuccessEvents() = runBlocking {
    val webUiPort = allocateFreePort()
    val schemaFile = Files.createTempFile("osc-webui-event-schema", ".yaml")
    try {
      schemaFile.toFile().writeText(schemaYaml)
      val transport = WebUiRecordingTransport()

      withStdioMcpClient(
          schemaFile = schemaFile,
          transport = transport,
          extraArgs = listOf("--webui", "--webui-port", webUiPort.toString()),
      ) { client ->
        // SSE 接続をバックグラウンドスレッドで開始
        val sseThread = launchSseCollector(port = webUiPort, maxEvents = 8, timeoutMs = 8000)

        // WebUI サーバーの起動待ち
        Thread.sleep(1000)

        // tools/call で有効なツールを呼び出す
        client.callTool(
            name = "set_light_color",
            arguments = mapOf("r" to 255, "g" to 128, "b" to 0),
        )

        // イベント伝播待ち
        Thread.sleep(1500)

        val events = sseThread.awaitEvents()

        // mcp_request イベントで tools/call メッセージが含まれること
        val hasCallRequest = events.any { it.contains("mcp_request") && it.contains("tools/call") }
        assertTrue(
            hasCallRequest,
            "Expected SSE event with type=mcp_request and message containing tools/call but got: $events",
        )

        // mcp_success イベントが含まれること
        val hasSuccess = events.any { it.contains("mcp_success") }
        assertTrue(
            hasSuccess,
            "Expected SSE event with type=mcp_success but got: $events",
        )
      }
    } finally {
      Files.deleteIfExists(schemaFile)
    }
  }

  /** 存在しないツール名で tools/call を呼んだ場合に mcp_request と mcp_failure の SSE イベントが配信されることを検証する。 */
  @Test
  fun toolsCallUnknownToolEmitsMcpRequestAndFailureEvents() = runBlocking {
    val webUiPort = allocateFreePort()
    val schemaFile = Files.createTempFile("osc-webui-event-schema", ".yaml")
    try {
      schemaFile.toFile().writeText(schemaYaml)
      val transport = WebUiRecordingTransport()

      withStdioMcpClient(
          schemaFile = schemaFile,
          transport = transport,
          extraArgs = listOf("--webui", "--webui-port", webUiPort.toString()),
      ) { client ->
        // SSE 接続をバックグラウンドスレッドで開始
        val sseThread = launchSseCollector(port = webUiPort, maxEvents = 8, timeoutMs = 8000)

        // WebUI サーバーの起動待ち
        Thread.sleep(1000)

        // 存在しないツール名で tools/call を実行
        client.callTool(
            name = "nonexistent_tool",
            arguments = emptyMap<String, Any>(),
        )

        // イベント伝播待ち
        Thread.sleep(1500)

        val events = sseThread.awaitEvents()

        // mcp_request イベントが含まれること
        val hasCallRequest = events.any { it.contains("mcp_request") && it.contains("tools/call") }
        assertTrue(
            hasCallRequest,
            "Expected SSE event with type=mcp_request and message containing tools/call but got: $events",
        )

        // mcp_failure イベントで Unknown tool メッセージが含まれること
        val hasFailure = events.any { it.contains("mcp_failure") && it.contains("Unknown tool") }
        assertTrue(
            hasFailure,
            "Expected SSE event with type=mcp_failure and message containing 'Unknown tool' but got: $events",
        )
      }
    } finally {
      Files.deleteIfExists(schemaFile)
    }
  }
}

/**
 * 利用可能なローカルポートを動的に取得する。
 *
 * @return 空きポート番号
 */
private fun allocateFreePort(): Int = ServerSocket(0).use { it.localPort }

/**
 * SSE イベントをバックグラウンドスレッドで収集するヘルパーを起動する。
 *
 * 指定ポートの `/api/events` へ接続し、`data:` 行を最大 [maxEvents] 件または [timeoutMs] ミリ秒まで収集する。
 *
 * @param port WebUI サーバーのポート番号
 * @param maxEvents 収集するイベントの最大数
 * @param timeoutMs タイムアウト（ミリ秒）
 * @return 収集結果を保持する [SseCollectorThread]
 */
private fun launchSseCollector(port: Int, maxEvents: Int, timeoutMs: Long): SseCollectorThread {
  val thread = SseCollectorThread(port = port, maxEvents = maxEvents, timeoutMs = timeoutMs)
  thread.isDaemon = true
  thread.start()
  return thread
}

/**
 * SSE イベントをバックグラウンドで収集するスレッド。
 *
 * [awaitEvents] を呼ぶとスレッド終了を待って収集結果を返す。
 *
 * @param port WebUI サーバーのポート番号
 * @param maxEvents 収集するイベントの最大数
 * @param timeoutMs タイムアウト（ミリ秒）
 */
private class SseCollectorThread(
    private val port: Int,
    private val maxEvents: Int,
    private val timeoutMs: Long,
) : Thread("sse-collector") {

  private val collected = mutableListOf<String>()

  override fun run() {
    // SSE エンドポイントへ接続し data: 行を収集する
    try {
      val conn =
          URI("http://localhost:$port/api/events").toURL().openConnection() as HttpURLConnection
      conn.connectTimeout = 3000
      conn.readTimeout = timeoutMs.toInt()
      conn.setRequestProperty("Accept", "text/event-stream")
      val reader = conn.inputStream.bufferedReader()
      val deadline = System.currentTimeMillis() + timeoutMs
      while (collected.size < maxEvents && System.currentTimeMillis() < deadline) {
        val line = reader.readLine() ?: break
        if (line.startsWith("data: ")) {
          synchronized(collected) { collected.add(line.removePrefix("data: ").trim()) }
        }
      }
    } catch (_: java.net.SocketTimeoutException) {
      // 読み取りタイムアウトは正常終了として扱う
    } catch (_: java.io.IOException) {
      // 接続切断・リセットは正常終了として扱う
    }
  }

  /**
   * スレッドの終了を待ち、収集した SSE イベント文字列のリストを返す。
   *
   * @return 収集された SSE data ペイロードのリスト
   */
  fun awaitEvents(): List<String> {
    join(timeoutMs + 2000)
    return synchronized(collected) { collected.toList() }
  }
}

/** 送信パケットを記録するだけで実際に送信しないフェイクトランスポート。 */
private class WebUiRecordingTransport : OscTransport {
  override val incomingPackets: Flow<OscPacket> = MutableSharedFlow()
  val sentPackets: MutableList<OscMessagePacket> = mutableListOf()

  /** @see OscTransport.start */
  override suspend fun start() = Unit

  /** @see OscTransport.stop */
  override suspend fun stop() = Unit

  /**
   * パケットを記録する。
   *
   * @param packet 送信される OSC パケット
   * @param target 送信先
   */
  override suspend fun send(packet: OscPacket, target: OscTarget) {
    sentPackets += packet as OscMessagePacket
  }
}
