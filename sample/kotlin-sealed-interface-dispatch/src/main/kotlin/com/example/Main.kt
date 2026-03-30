package com.example

import com.example.osc.generated.LightColor
import com.example.osc.generated.OscMessages
import com.example.osc.generated.SensorValue
import com.example.osc.generated.on
import com.oscplatform.core.runtime.OscRuntime
import com.oscplatform.core.schema.loader.SchemaLoader
import com.oscplatform.core.transport.OscTarget
import com.oscplatform.transport.udp.UdpOscTransport
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking

/**
 * sealed interface と generated receive helper を使った受信デモのエントリーポイント。
 *
 * `runtime.on<OscMessages> { ... }` で複数メッセージをひとつの入口に束ね、 `when` 式の網羅性チェックを使って型安全に分岐する。
 */
fun main(): Unit = runBlocking {
  // 1. スキーマとランタイムを初期化する。
  val schemaPath = Paths.get("schema.yaml")
  val schema = SchemaLoader().load(schemaPath)
  val transport = UdpOscTransport(bindHost = "127.0.0.1", bindPort = 19030)
  val runtime = OscRuntime(schema = schema, transport = transport)
  val target = OscTarget("127.0.0.1", 19030)

  // 2. sealed interface 単位で受信登録し、2件受信したら終了する。
  val received = CompletableDeferred<Unit>()
  val receivedCount = AtomicInteger(0)
  runtime.on<OscMessages> { msg ->
    println("[Received] ${handleMessage(msg)}")
    if (receivedCount.incrementAndGet() == 2) {
      received.complete(Unit)
    }
  }

  // 3. 送信元ごとに個別クラスを作っても、受信側は union 型で扱える。
  runtime.start()
  runtime.send(
      companion = LightColor,
      msg = LightColor(r = 255, g = 64, b = 32),
      target = target,
  )
  runtime.send(
      companion = SensorValue,
      msg = SensorValue(v = 1.25f),
      target = target,
  )

  received.await()
  runtime.stop()
}

/**
 * 受信した generated sealed interface を文字列へ整形する。
 *
 * @param msg 受信した generated メッセージ
 * @return ログ出力用の整形済み文字列
 */
fun handleMessage(msg: OscMessages): String =
    when (msg) {
      is LightColor -> "${LightColor.PATH} r=${msg.r}, g=${msg.g}, b=${msg.b}"
      is SensorValue -> "${SensorValue.PATH} v=${msg.v}"
    }
