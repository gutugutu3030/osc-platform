package com.example

import com.oscplatform.core.runtime.OscRuntime
import com.oscplatform.core.runtime.OscRuntimeEvent
import com.oscplatform.core.schema.loader.SchemaLoader
import com.oscplatform.core.transport.OscTarget
import com.oscplatform.transport.udp.UdpOscTransport
import java.nio.file.Paths
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking

/**
 * ループバック最小サンプル。
 * 1. SchemaLoader で schema.kts を読み込む
 * 2. UdpOscTransport を 127.0.0.1:19000 にバインド
 * 3. OscRuntime を start()
 * 4. /light/color を自分自身 (127.0.0.1:19000) へ送信
 * 5. 受信ハンドラで 1 件確認後に stop() して終了
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
  val lightColorSpec = schema.resolveMessage("light.color") ?: error("Missing message: light.color")

  // --- 受信ハンドラ登録 (suspend ではない通常関数) ---
  val received = CompletableDeferred<Unit>()
  runtime.on(lightColorSpec) { event: OscRuntimeEvent.Received ->
    println("[Received] /light/color  namedArgs=${event.namedArgs}")
    received.complete(Unit)
  }

  // --- 起動 ---
  runtime.start()
  println("[Runtime] 起動完了 UDP 127.0.0.1:19000")

  // --- 送信 ---
  runtime.send(
      messageRef = "light.color", // パス "/light/color" でも可
      rawArgs = mapOf("r" to 255, "g" to 0, "b" to 128),
      target = OscTarget("127.0.0.1", 19000),
  )
  println("[Send]    /light/color r=255, g=0, b=128")

  // --- 受信待ち & 終了 ---
  received.await()
  runtime.stop()
  println("[Done]    1 件受信確認。ランタイム停止。")
}
