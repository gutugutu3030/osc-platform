package com.example

import com.oscplatform.core.runtime.OscRuntime
import com.oscplatform.core.runtime.OscRuntimeEvent
import com.oscplatform.core.schema.loader.SchemaLoader
import com.oscplatform.core.transport.OscMessagePacket
import com.oscplatform.core.transport.OscTarget
import com.oscplatform.transport.udp.UdpOscTransport
import java.nio.file.Paths
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 構造化引数 (lengthFrom + タプル配列) と sendBundle を体験するサンプル。
 *
 * デモの流れ: Step 1 – /mesh/points を pointCount 省略で送信（配列長から自動導出を実演） Step 2 – bundle "set_scene" を
 * sendBundle でまとめて送信 Step 3 – 不正パスのパケットを直接送信して ValidationError を確認
 */
fun main(): Unit = runBlocking {
  val target = OscTarget("127.0.0.1", 19010)

  // --- スキーマ読み込み ---
  val schema = SchemaLoader().load(Paths.get("schema.yaml"))
  println("[Setup] スキーマ: ${schema.messages.size} メッセージ, ${schema.bundles.size} バンドル")

  val transport = UdpOscTransport(bindHost = "127.0.0.1", bindPort = 19010)
  val runtime = OscRuntime(schema = schema, transport = transport)

  // --- events フローを購読（UNDISPATCHED で即座に collect に入る）---
  val eventJob =
      launch(start = CoroutineStart.UNDISPATCHED) {
        runtime.events.collect { event ->
          when (event) {
            is OscRuntimeEvent.Received ->
                println(
                    "[Event.Received]         path=${event.spec.path}  namedArgs=${event.namedArgs}")

            is OscRuntimeEvent.ValidationError ->
                println("[Event.ValidationError]  addr=${event.address}  reason=${event.reason}")

            is OscRuntimeEvent.TransportErrorEvent ->
                println("[Event.TransportError]   ${event.error}")
          }
        }
      }

  runtime.start()
  println("[Runtime] 起動完了 UDP 127.0.0.1:19010\n")

  // -------------------------------------------------------------------------
  // Step 1: /mesh/points を points のみ指定（pointCount は配列長から自動導出）
  // -------------------------------------------------------------------------
  println("--- Step 1: /mesh/points 送信（pointCount 自動導出） ---")
  println("  ※ rawArgs に pointCount を渡さず points のリストだけ渡す")
  runtime.send(
      messageRef = "mesh.points",
      rawArgs =
          mapOf(
              // pointCount は省略 → send() が points の要素数 (3) から自動導出
              "points" to
                  listOf(
                      mapOf("x" to 10, "y" to 20, "z" to 30.0f),
                      mapOf("x" to 40, "y" to 50, "z" to 60.5f),
                      mapOf("x" to 70, "y" to 80, "z" to 90.0f),
                  )),
      target = target,
  )
  delay(300) // 受信イベントを待つ

  // -------------------------------------------------------------------------
  // Step 2: bundle "set_scene" を sendBundle でアトミック送信
  // -------------------------------------------------------------------------
  println("\n--- Step 2: bundle (set_scene) を sendBundle で送信 ---")
  runtime.sendBundle(
      messages =
          listOf(
              "mesh.points" to
                  mapOf(
                      "points" to
                          listOf(
                              mapOf("x" to 1, "y" to 2, "z" to 3.0f),
                          )),
              "/device/flag" to mapOf("enabled" to true),
          ),
      target = target,
  )
  delay(300)

  // -------------------------------------------------------------------------
  // Step 3: 不正パスのパケットを直接送信 → ValidationError を発生させる
  // -------------------------------------------------------------------------
  println("\n--- Step 3: 不正パスのパケットを直接送信（ValidationError を確認） ---")
  println("  ※ スキーマに存在しない '/invalid/path' を生パケットで送信")
  transport.send(
      packet = OscMessagePacket(address = "/invalid/path", arguments = listOf(1, 2, 3)),
      target = target,
  )
  delay(300)

  // -------------------------------------------------------------------------
  // 終了
  // -------------------------------------------------------------------------
  eventJob.cancelAndJoin()
  runtime.stop()
  println("\n[Done] デモ完了。ランタイム停止。")
}
