package com.oscplatform.adapter.webui

import java.io.PrintStream

/**
 * スキーマエディタ アダプター。
 *
 * Web ブラウザ上で Kotlin DSL スキーマを記述・可視化するためのエディタサーバーを起動する。
 *
 * @param out 標準出力ストリーム
 * @param err 標準エラー出力ストリーム
 */
class SchemaEditorAdapter(
    private val out: PrintStream = System.out,
    private val err: PrintStream = System.err,
) {

  /**
   * コマンドの使用方法サマリーを返す。
   *
   * @return コマンドライン使用方法の文字列
   */
  fun commandSummary(): String = "osc editor [--port 3000]"

  /**
   * エディタコマンドを実行する。
   *
   * 引数を解析し、スキーマエディタサーバーを起動してシャットダウンフックで停止する。
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
      val config = parseArgs(args)
      val server = SchemaEditorServer(config)
      server.start()

      out.println("OSC Schema Editor started")
      out.println("URL: http://localhost:${server.port}")

      // シャットダウンフックでサーバーを停止
      Runtime.getRuntime().addShutdownHook(Thread { server.stop() })

      // メインスレッドをブロックしてサーバーを維持
      kotlinx.coroutines.awaitCancellation()
    } catch (ex: kotlinx.coroutines.CancellationException) {
      throw ex
    } catch (ex: EditorUsageException) {
      err.println("error: ${ex.message}")
      out.println(commandSummary())
      1
    } catch (ex: Exception) {
      err.println("error: ${ex.message ?: "Unexpected error"}")
      1
    }
  }

  /**
   * コマンドライン引数を解析して [SchemaEditorServerConfig] を返す。
   *
   * @param args コマンドライン引数のリスト
   * @return 解析された設定
   * @throws EditorUsageException 不正な引数が指定された場合
   */
  private fun parseArgs(args: List<String>): SchemaEditorServerConfig {
    var httpPort = 3000

    var index = 0
    while (index < args.size) {
      val token = args[index]
      when {
        token == "--port" -> {
          httpPort =
              args.valueAfter(index, "--port").toIntOrNull()
                  ?: editorUsageError("Invalid --port value")
          index += 2
        }
        token.startsWith("--port=") -> {
          httpPort =
              token.substringAfter('=').toIntOrNull() ?: editorUsageError("Invalid --port value")
          index += 1
        }
        token.startsWith("--") -> editorUsageError("Unknown option for editor: $token")
        else -> editorUsageError("Unexpected token in editor command: $token")
      }
    }

    return SchemaEditorServerConfig(httpPort = httpPort)
  }

  /**
   * 指定インデックスの次の引数値を取得する拡張関数。
   *
   * @param index 現在のオプションのインデックス
   * @param option オプション名（エラーメッセージ用）
   * @return 次の引数の値
   * @throws EditorUsageException 値が存在しない場合
   */
  private fun List<String>.valueAfter(index: Int, option: String): String {
    if (index + 1 >= size) editorUsageError("$option requires a value")
    return this[index + 1]
  }
}

/**
 * 使用方法エラーを [EditorUsageException] としてスローする。
 *
 * @param message エラーメッセージ
 * @return Nothing（常に例外をスローする）
 * @throws EditorUsageException 常にスローされる
 */
private fun editorUsageError(message: String): Nothing = throw EditorUsageException(message)

/**
 * エディタコマンドの使用方法エラーを表す例外。
 *
 * @param message エラーメッセージ
 */
private class EditorUsageException(message: String) : IllegalArgumentException(message)
