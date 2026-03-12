package com.example

import com.example.osc.generated.LightColor
import com.oscplatform.core.runtime.OscRuntime
import com.oscplatform.core.schema.loader.SchemaLoader
import com.oscplatform.core.transport.OscTarget
import com.oscplatform.transport.udp.UdpOscTransport
import java.nio.file.Paths
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking

/**
 * ループバック最小サンプル（codegen 版）。
 * 1. SchemaLoader で schema.kts を読み込む
 * 2. UdpOscTransport を 127.0.0.1:19000 にバインド
 * 3. OscRuntime を start()
 * 4. 生成型 [LightColor] を使って /light/color を自分自身へ送信
 * 5. 受信ハンドラで [LightColor.fromNamedArgs] により型安全に受け取り、stop() して終了
 *
 * `LightColor` は `schema.yaml` から `generateOscSources` タスクが自動生成するクラスです。
 */
fun main(): Unit = runBlocking {
  // --- スキーマ読み込み ---
  val schemaPath = Paths.get("schema.kts")
  println("[Setup] スキーマ読み込み中: ${schemaPath.toAbsolutePath()}")
  val schema = SchemaLoader().load(schemaPath)
  println("[Setup] スキーマ読み込み完了: ${schema.messages.size} メッセージ定義")

  // --- トランスポート & ランタイム構築 ---
  val transport = UdpOscTransport(bindHost = "127.0.0.1", bindPort = 19000)
  val runtime = OscRuntime(schema = schema, transport = transport)

  // --- 受信ハンドラ登録: 生成型で型安全に受け取る ---
  val received = CompletableDeferred<Unit>()
  runtime.on(LightColor) { color ->
    println("[Received] ${LightColor.PATH}  r=${color.r}, g=${color.g}, b=${color.b}")
    received.complete(Unit)
  }

  // --- 起動 ---
  runtime.start()
  println("[Runtime] 起動完了 UDP 127.0.0.1:19000")

  // --- 送信: 生成型で型安全に送る ---
  val msg = LightColor(r = 255, g = 0, b = 128)
  runtime.send(
      companion = LightColor,
      msg = msg,
      target = OscTarget("127.0.0.1", 19000),
  )
  println("[Send]    ${LightColor.PATH} r=${msg.r}, g=${msg.g}, b=${msg.b}")

  // --- 受信待ち & 終了 ---
  received.await()
  runtime.stop()
  println("[Done]    1 件受信確認。ランタイム停止。")
}
