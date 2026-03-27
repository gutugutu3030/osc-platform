package com.oscplatform.adapter.mcp

import com.oscplatform.core.transport.OscTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

/** MCP 統合テストで使うデフォルトの OSC 送信先ホスト。 */
private const val TEST_TARGET_HOST = "127.0.0.1"

/** MCP 統合テストで使うデフォルトの OSC 送信先ポート。 */
private const val TEST_TARGET_PORT = 9000

/**
 * stdio transport で MCP サーバーへ接続したクライアントを使ってテストを実行する。
 *
 * @param schemaFile テスト対象のスキーマファイル
 * @param transport OSC 送信に使用するトランスポート
 * @param extraArgs 追加のコマンドライン引数
 * @param block 接続済みクライアントで実行するテスト処理
 * @return テスト処理の戻り値
 */
internal suspend fun <T> withStdioMcpClient(
    schemaFile: Path,
    transport: OscTransport,
    extraArgs: List<String> = emptyList(),
    block: suspend (Client) -> T,
): T {
  val serverInput = PipedInputStream()
  val clientOutput = PipedOutputStream(serverInput)
  val clientInput = PipedInputStream()
  val serverOutput = PipedOutputStream(clientInput)
  val scope = CoroutineScope(Dispatchers.IO)

  // クライアントとサーバーの標準入出力をパイプで直結し、実際の stdio transport をそのまま流す。
  val serverJob =
      scope.async {
        McpAdapter()
            .execute(
                args = buildBaseArgs(schemaFile = schemaFile, extraArgs = extraArgs),
                input = serverInput,
                output = serverOutput,
                transport = transport,
            )
      }
  val client = Client(Implementation(name = "test-client", version = "0.1.0"))
  val stdioTransport =
      StdioClientTransport(
          input = clientInput.asSource().buffered(),
          output = clientOutput.asSink().buffered(),
          error = ByteArrayInputStream(byteArrayOf()).asSource().buffered(),
      )

  return try {
    client.connect(stdioTransport)
    block(client)
  } finally {
    client.close()
    withTimeout(5.seconds) { serverJob.await() }
  }
}

/**
 * streamable HTTP transport で MCP サーバーへ接続したクライアントを使ってテストを実行する。
 *
 * @param schemaFile テスト対象のスキーマファイル
 * @param transport OSC 送信に使用するトランスポート
 * @param listenHost streamable HTTP の待受ホスト
 * @param extraArgs 追加のコマンドライン引数
 * @param block 接続済みクライアントと待受情報で実行するテスト処理
 * @return テスト処理の戻り値
 */
internal suspend fun <T> withStreamableHttpMcpClient(
    schemaFile: Path,
    transport: OscTransport,
    listenHost: String = TEST_TARGET_HOST,
    extraArgs: List<String> = emptyList(),
    block: suspend (Client, McpHttpServerHandle) -> T,
): T {
  val scope = CoroutineScope(Dispatchers.IO)
  val handleReady = kotlinx.coroutines.CompletableDeferred<McpHttpServerHandle>()
  val serverJob =
      scope.async {
        McpAdapter()
            .execute(
                args =
                    buildBaseArgs(
                        schemaFile = schemaFile,
                        extraArgs =
                            listOf(
                                "--streamable-http-port=0",
                                "--listen-host=$listenHost",
                            ) + extraArgs,
                    ),
                input = ByteArrayInputStream(byteArrayOf()),
                output = ByteArrayOutputStream(),
                transport = transport,
                httpServerLifecycle = { handle ->
                  handleReady.complete(handle)
                  handle.stopSignal.await()
                },
            )
      }
  val handle = withTimeout(5.seconds) { handleReady.await() }
  val httpClient = HttpClient(CIO) { install(SSE) }
  val client = Client(Implementation(name = "test-client", version = "0.1.0"))
  val streamableHttpTransport =
      StreamableHttpClientTransport(
          client = httpClient,
          url = "http://${handle.host}:${handle.port}${handle.path}",
      )

  return try {
    client.connect(streamableHttpTransport)
    block(client, handle)
  } finally {
    client.close()
    httpClient.close()
    handle.stopSignal.complete(Unit)
    withTimeout(5.seconds) { serverJob.await() }
  }
}

/**
 * MCP アダプター実行用のベース引数を組み立てる。
 *
 * @param schemaFile テスト対象のスキーマファイル
 * @param extraArgs 追加引数
 * @return 実行用の引数配列
 */
private fun buildBaseArgs(schemaFile: Path, extraArgs: List<String>): List<String> =
    listOf(
        "--schema=${schemaFile.toAbsolutePath()}",
        "--host=$TEST_TARGET_HOST",
        "--port=$TEST_TARGET_PORT",
    ) + extraArgs
