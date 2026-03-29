package com.oscplatform.adapter.webui

import io.ktor.server.http.content.staticResources
import io.ktor.server.routing.Routing
import java.net.HttpURLConnection
import java.net.URI
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

private val webUiJsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

/**
 * Web UI 用の静的アセット配信ルートを登録する。
 *
 * `/assets` 配下の classpath リソースを Ktor から配信する。
 */
internal fun Routing.installWebUiStaticAssets() {
  staticResources("/assets", "assets")
}

/**
 * ローカルの HTTP サーバーがリクエストを受け付けられるまで待機する。
 *
 * @param port 待機対象のポート番号
 * @param path ヘルスチェックに利用するパス
 * @param maxRetries 最大リトライ回数
 * @param intervalMs リトライ間隔（ミリ秒）
 * @throws IllegalStateException 指定時間内に応答が得られない場合
 */
internal fun awaitLocalHttpReady(
    port: Int,
    path: String = "/",
    maxRetries: Int = 20,
    intervalMs: Long = 100,
) {
  if (port <= 0) {
    return
  }

  // サーバー起動直後の非同期初期化を吸収するため、軽量な GET をリトライする。
  repeat(maxRetries) {
    try {
      val conn = URI("http://localhost:$port$path").toURL().openConnection() as HttpURLConnection
      conn.connectTimeout = 200
      conn.readTimeout = 200
      conn.responseCode
      conn.disconnect()
      return
    } catch (_: Exception) {
      Thread.sleep(intervalMs)
    }
  }

  error("Server on port $port did not become ready within ${maxRetries * intervalMs}ms")
}

/**
 * Web UI 向けにオブジェクトを JSON 文字列へ変換する。
 *
 * @return JSON 文字列
 * @receiver シリアライズ対象のオブジェクト
 */
internal fun Any?.toWebUiJson(): String {
  return webUiJsonMapper.writeValueAsString(this)
}

/**
 * Web UI のリクエストボディを JSON オブジェクトとして読み取る。
 *
 * @return 連想配列として読み取った JSON オブジェクト
 * @receiver JSON 文字列
 */
internal fun String.parseWebUiJsonObject(): Map<*, *> {
  return webUiJsonMapper.readValue(this, Map::class.java)
}

/**
 * HTML script タグへ安全に埋め込める JSON 文字列へ変換する。
 *
 * @return HTML 埋め込み向けにエスケープ済みの JSON 文字列
 * @receiver シリアライズ対象のオブジェクト
 */
internal fun Any?.toWebUiHtmlSafeJson(): String {
  return toWebUiJson().replace("</", "<\\/")
}
