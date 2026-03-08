package com.example

import com.oscplatform.core.runtime.OscRuntime
import com.oscplatform.core.schema.loader.SchemaLoader
import com.oscplatform.core.transport.OscTarget
import com.oscplatform.transport.udp.UdpOscTransport
import java.nio.file.Paths
import kotlinx.coroutines.runBlocking

/**
 * Java から利用するための同期ラッパークライアント。
 *
 * OscRuntime は suspend 関数中心の Kotlin API であるため、 Java コードから直接呼び出すには runBlocking で suspend
 * 呼び出しをブロッキング化する ラッパークラスを用意するのが現実的なアプローチです。
 *
 * @param host 送信先ホスト
 * @param port 送信先ポート
 */
class OscBlockingClient(
    private val host: String,
    private val port: Int,
) {
  private val schema = SchemaLoader().load(Paths.get("schema.yaml"))
  private val transport = UdpOscTransport(bindHost = "0.0.0.0", bindPort = 0)
  private val runtime = OscRuntime(schema = schema, transport = transport)

  /** ランタイムを起動する（内部で runBlocking を使用）。 */
  fun start() = runBlocking {
    runtime.start()
    println("[OscBlockingClient] 起動完了  target=$host:$port")
  }

  /**
   * /device/flag メッセージを送信する。
   *
   * @param enabled フラグ値
   */
  fun sendFlag(enabled: Boolean) = runBlocking {
    runtime.send(
        messageRef = "/device/flag",
        rawArgs = mapOf("enabled" to enabled),
        target = OscTarget(host, port),
    )
    println("[OscBlockingClient] 送信: /device/flag  enabled=$enabled")
  }

  /** ランタイムを停止する（内部で runBlocking を使用）。 */
  fun stop() = runBlocking {
    runtime.stop()
    println("[OscBlockingClient] 停止")
  }
}
