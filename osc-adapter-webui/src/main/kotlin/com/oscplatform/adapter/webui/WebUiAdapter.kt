package com.oscplatform.adapter.webui

import com.oscplatform.core.runtime.OscRuntime
import com.oscplatform.core.schema.loader.SchemaLoader
import com.oscplatform.core.schema.loader.SchemaPathResolver
import com.oscplatform.transport.udp.UdpOscTransport
import java.io.PrintStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking

/**
 * Web UI アダプター。
 *
 * スキーマファイルを読み込み、OSC ランタイムと Web UI サーバーを起動して ブラウザベースの送受信インターフェースを提供する。
 *
 * @param out 標準出力ストリーム
 * @param err 標準エラー出力ストリーム
 */
class WebUiAdapter(
    private val out: PrintStream = System.out,
    private val err: PrintStream = System.err,
) {
  private val schemaLoader = SchemaLoader()

  /**
   * コマンドの使用方法サマリーを返す。
   *
   * @return コマンドライン使用方法の文字列
   */
  fun commandSummary(): String =
      "osc webui [schemaPath] [--schema path] [--port 8080] [--osc-host 0.0.0.0] [--osc-port 9000] (deprecated)"

  /**
   * Web UI コマンドを実行する。
   *
   * 引数を解析し、スキーマ・ランタイム・Web UI サーバーを起動してキャンセルされるまで待機する。
   *
   * @param args コマンドライン引数のリスト
   * @return 終了コード（0: 正常、1: エラー）
   */
  suspend fun execute(args: List<String>): Int {
    if (args.firstOrNull() in setOf("help", "-h", "--help")) {
      out.println(commandSummary())
      return 0
    }

    return try {
      err.println(
          "warning: 'osc webui' is deprecated. Use 'osc run --webui', 'osc send --webui', or 'osc mcp --webui'.",
      )
      val config = parseArgs(args)
      val schemaPath =
          SchemaPathResolver.resolve(
              config.schemaPath, warn = { message -> err.println("warning: $message") })
      val schema = schemaLoader.load(schemaPath)

      val transport = UdpOscTransport(bindHost = config.oscHost, bindPort = config.oscPort)
      val runtime = OscRuntime(schema = schema, transport = transport)
      runtime.start()

      val server =
          WebUiServer(
              schema = schema,
              runtime = runtime,
              config =
                  WebUiServerConfig(
                      mode = WebUiMode.SENDER,
                      httpPort = config.httpPort,
                      defaultTargetHost = "127.0.0.1",
                      defaultTargetPort = config.oscPort,
                  ),
          )
      server.start()

      out.println("OSC Web UI started")
      out.println("schema:     $schemaPath")
      out.println("OSC listen: ${config.oscHost}:${config.oscPort}")
      out.println("Web UI:     http://localhost:${server.port}")

      Runtime.getRuntime()
          .addShutdownHook(
              Thread {
                runBlocking {
                  server.stop()
                  runtime.stop()
                }
              })

      try {
        awaitCancellation()
      } finally {
        server.stop()
        runtime.stop()
      }

      0
    } catch (ex: CancellationException) {
      throw ex
    } catch (ex: WebUiUsageException) {
      err.println("error: ${ex.message}")
      out.println(commandSummary())
      1
    } catch (ex: Exception) {
      err.println("error: ${ex.message ?: "Unexpected error"}")
      1
    }
  }

  /**
   * コマンドライン引数を解析して [WebUiConfig] を返す。
   *
   * @param args コマンドライン引数のリスト
   * @return 解析された設定
   * @throws WebUiUsageException 不正な引数が指定された場合
   */
  private fun parseArgs(args: List<String>): WebUiConfig {
    var schemaPath: String? = null
    var httpPort = 8080
    var oscHost = "0.0.0.0"
    var oscPort = 9000

    var index = 0
    while (index < args.size) {
      val token = args[index]
      when {
        token == "--schema" -> {
          schemaPath = args.valueAfter(index, "--schema")
          index += 2
        }
        token.startsWith("--schema=") -> {
          schemaPath = token.substringAfter('=')
          index += 1
        }
        token == "--port" -> {
          httpPort =
              args.valueAfter(index, "--port").toIntOrNull()
                  ?: webUiUsageError("Invalid --port value")
          index += 2
        }
        token.startsWith("--port=") -> {
          httpPort =
              token.substringAfter('=').toIntOrNull() ?: webUiUsageError("Invalid --port value")
          index += 1
        }
        token == "--osc-host" -> {
          oscHost = args.valueAfter(index, "--osc-host")
          index += 2
        }
        token.startsWith("--osc-host=") -> {
          oscHost = token.substringAfter('=')
          index += 1
        }
        token == "--osc-port" -> {
          oscPort =
              args.valueAfter(index, "--osc-port").toIntOrNull()
                  ?: webUiUsageError("Invalid --osc-port value")
          index += 2
        }
        token.startsWith("--osc-port=") -> {
          oscPort =
              token.substringAfter('=').toIntOrNull() ?: webUiUsageError("Invalid --osc-port value")
          index += 1
        }
        token.startsWith("--") -> webUiUsageError("Unknown option for webui: $token")
        schemaPath == null -> {
          schemaPath = token
          index += 1
        }
        else -> webUiUsageError("Unexpected token in webui command: $token")
      }
    }

    return WebUiConfig(
        schemaPath = schemaPath,
        httpPort = httpPort,
        oscHost = oscHost,
        oscPort = oscPort,
    )
  }

  /**
   * 指定インデックスの次の引数値を取得する拡張関数。
   *
   * @param index 現在のオプションのインデックス
   * @param option オプション名（エラーメッセージ用）
   * @return 次の引数の値
   * @throws WebUiUsageException 値が存在しない場合
   */
  private fun List<String>.valueAfter(index: Int, option: String): String {
    if (index + 1 >= size) webUiUsageError("$option requires a value")
    return this[index + 1]
  }
}

/**
 * Web UI コマンドの設定。
 *
 * @property schemaPath スキーマファイルのパス（null の場合はデフォルト解決）
 * @property httpPort HTTP サーバーのポート番号
 * @property oscHost OSC 受信ホストアドレス
 * @property oscPort OSC 受信ポート番号
 */
private data class WebUiConfig(
    val schemaPath: String?,
    val httpPort: Int,
    val oscHost: String,
    val oscPort: Int,
)

/**
 * 使用方法エラーを [WebUiUsageException] としてスローする。
 *
 * @param message エラーメッセージ
 * @return Nothing（常に例外をスローする）
 * @throws WebUiUsageException 常にスローされる
 */
private fun webUiUsageError(message: String): Nothing = throw WebUiUsageException(message)

/**
 * Web UI コマンドの使用方法エラーを表す例外。
 *
 * @param message エラーメッセージ
 */
private class WebUiUsageException(message: String) : IllegalArgumentException(message)
