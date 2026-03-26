package com.oscplatform.core.runtime

import com.oscplatform.core.schema.ArrayArgNode
import com.oscplatform.core.schema.ArrayItemSpec
import com.oscplatform.core.schema.LengthSpec
import com.oscplatform.core.schema.OscMessageSpec
import com.oscplatform.core.schema.OscSchema
import com.oscplatform.core.schema.OscType
import com.oscplatform.core.schema.ScalarArgNode
import com.oscplatform.core.schema.ScalarRole
import com.oscplatform.core.transport.OscBundlePacket
import com.oscplatform.core.transport.OscMessagePacket
import com.oscplatform.core.transport.OscPacket
import com.oscplatform.core.transport.OscTarget
import com.oscplatform.core.transport.OscTransport
import com.oscplatform.core.transport.TransportError
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/** OSC ランタイムで発生するイベントを表す sealed インターフェース。 */
sealed interface OscRuntimeEvent {
  /**
   * OSC メッセージを受信したイベント。
   *
   * @property spec 受信メッセージに対応するスキーマ仕様
   * @property packet 受信した生パケット
   * @property namedArgs パースされた名前付き引数マップ
   */
  data class Received(
      val spec: OscMessageSpec,
      val packet: OscMessagePacket,
      val namedArgs: Map<String, Any?>,
  ) : OscRuntimeEvent

  /**
   * 受信メッセージのバリデーションに失敗したイベント。
   *
   * @property reason バリデーション失敗の理由
   * @property address 失敗した OSC アドレス（不明の場合は null）
   */
  data class ValidationError(
      val reason: String,
      val address: String?,
  ) : OscRuntimeEvent

  /**
   * Transport受信ループで発生したエラー。連続失敗回数を含む。
   *
   * @property error 発生したトランスポートエラー
   */
  data class TransportErrorEvent(
      val error: TransportError,
  ) : OscRuntimeEvent

  /**
   * OSCメッセージ送信を開始したイベント。
   *
   * @property messageRef メッセージ参照名
   * @property args 送信する引数マップ
   * @property target 送信先ターゲット
   */
  data class SendStarted(
      val messageRef: String,
      val args: Map<String, Any?>,
      val target: OscTarget,
  ) : OscRuntimeEvent

  /**
   * OSCメッセージ送信が成功したイベント。
   *
   * @property messageRef メッセージ参照名
   * @property args 送信した引数マップ
   * @property target 送信先ターゲット
   */
  data class SendSucceeded(
      val messageRef: String,
      val args: Map<String, Any?>,
      val target: OscTarget,
  ) : OscRuntimeEvent

  /**
   * OSCメッセージ送信が失敗したイベント。
   *
   * @property messageRef メッセージ参照名
   * @property args 送信しようとした引数マップ
   * @property target 送信先ターゲット
   * @property cause 送信失敗の原因となった例外
   */
  data class SendFailed(
      val messageRef: String,
      val args: Map<String, Any?>,
      val target: OscTarget,
      val cause: Throwable,
  ) : OscRuntimeEvent
}

/**
 * OSC スキーマに基づいてメッセージの送受信を管理するランタイムエンジン。
 *
 * スキーマ定義に従い、受信パケットのバリデーション・名前付き引数への変換、 および送信時の引数フラット化・型変換を行う。
 *
 * @param schema メッセージ定義を提供する OSC スキーマ
 * @param transport パケットの送受信を担うトランスポート層
 * @param scope コルーチンの実行スコープ
 */
class OscRuntime(
    private val schema: OscSchema,
    private val transport: OscTransport,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
  private val _events = MutableSharedFlow<OscRuntimeEvent>(extraBufferCapacity = 128)
  /** ランタイムで発生する全イベントを流す [Flow]。 */
  val events: Flow<OscRuntimeEvent> = _events.asSharedFlow()

  private val handlers =
      ConcurrentHashMap<String, CopyOnWriteArrayList<suspend (OscRuntimeEvent.Received) -> Unit>>()
  private var receiveJob: Job? = null

  /**
   * 指定されたメッセージ仕様に対する受信ハンドラを登録する。
   *
   * @param spec 受信対象のメッセージ仕様
   * @param handler 受信時に呼び出されるコールバック
   */
  fun on(spec: OscMessageSpec, handler: suspend (OscRuntimeEvent.Received) -> Unit) {
    val registered = resolveKnownMessageSpec(spec)
    handlers.computeIfAbsent(registered.path) { CopyOnWriteArrayList() }.add(handler)
  }

  /**
   * codegen 生成メッセージクラスのコンパニオンを使って型安全な受信ハンドラを登録する。
   *
   * @param T メッセージの型
   * @param companion メッセージクラスのコンパニオンオブジェクト
   * @param handler 受信時にデシリアライズ済みメッセージで呼び出されるコールバック
   */
  fun <T : OscMessage> on(companion: OscMessageCompanion<T>, handler: suspend (T) -> Unit) {
    val spec =
        schema.resolveMessage(companion.NAME) ?: error("Schema has no message: ${companion.NAME}")
    on(spec) { event -> handler(companion.fromNamedArgs(event.namedArgs)) }
  }

  /**
   * ランタイムを開始し、トランスポートの受信ループを起動する。
   *
   * 既に開始済みの場合は何もしない。
   */
  suspend fun start() {
    if (receiveJob != null) {
      return
    }

    transport.start()
    receiveJob =
        scope.launch { transport.incomingPackets.collect { packet -> processPacket(packet) } }
    scope.launch {
      transport.errors.collect { err -> _events.emit(OscRuntimeEvent.TransportErrorEvent(err)) }
    }
  }

  /** ランタイムを停止し、受信ループおよびトランスポートを終了する。 */
  suspend fun stop() {
    receiveJob?.cancelAndJoin()
    receiveJob = null
    transport.stop()
  }

  /**
   * メッセージ参照名と引数マップを使って OSC メッセージを送信する。
   *
   * スキーマに基づいて引数のバリデーション・型変換・フラット化を行い、 トランスポート経由で送信する。
   *
   * @param messageRef メッセージ参照名（スキーマ定義名またはパス）
   * @param rawArgs 名前付き引数マップ
   * @param target 送信先ターゲット
   * @throws IllegalArgumentException メッセージ参照が不明、または引数が不正な場合
   */
  suspend fun send(
      messageRef: String,
      rawArgs: Map<String, Any?>,
      target: OscTarget,
  ) {
    _events.emit(
        OscRuntimeEvent.SendStarted(messageRef = messageRef, args = rawArgs, target = target))
    try {
      val spec = resolveMessageSpec(messageRef)

      val specArgNames = spec.args.map { it.name }.toSet()
      val unknownArgs = rawArgs.keys - specArgNames
      require(unknownArgs.isEmpty()) {
        "Unknown args for '${spec.name}': ${unknownArgs.joinToString()}"
      }

      val orderedArgs = flattenArgs(spec = spec, rawArgs = rawArgs)

      transport.send(
          packet = OscMessagePacket(address = spec.path, arguments = orderedArgs),
          target = target,
      )
      _events.emit(
          OscRuntimeEvent.SendSucceeded(messageRef = messageRef, args = rawArgs, target = target))
    } catch (ex: Exception) {
      _events.emit(
          OscRuntimeEvent.SendFailed(
              messageRef = messageRef, args = rawArgs, target = target, cause = ex))
      throw ex
    }
  }

  /**
   * codegen 生成メッセージクラスのインスタンスを型安全に送信する。
   *
   * @param T メッセージの型
   * @param companion メッセージクラスのコンパニオンオブジェクト
   * @param msg 送信するメッセージインスタンス
   * @param target 送信先ターゲット
   */
  suspend fun <T : OscMessage> send(
      companion: OscMessageCompanion<T>,
      msg: T,
      target: OscTarget,
  ) = send(messageRef = companion.NAME, rawArgs = msg.toNamedArgs(), target = target)

  /**
   * 複数メッセージを OSC バンドルとしてアトミックに送信する。
   *
   * @param messages メッセージ参照名と名前付き引数マップのペアリスト
   * @param target 送信先ターゲット
   * @param timeTag OSC タイムタグ（デフォルトは即時送信）
   * @throws IllegalArgumentException メッセージリストが空、または引数が不正な場合
   */
  suspend fun sendBundle(
      messages: List<Pair<String, Map<String, Any?>>>,
      target: OscTarget,
      timeTag: Long = OscTimeTag.IMMEDIATE,
  ) {
    require(messages.isNotEmpty()) { "sendBundle requires at least one message" }

    val elements =
        messages.map { (messageRef, rawArgs) ->
          val spec = resolveMessageSpec(messageRef)

          val specArgNames = spec.args.map { it.name }.toSet()
          val unknownArgs = rawArgs.keys - specArgNames
          require(unknownArgs.isEmpty()) {
            "Unknown args for '${spec.name}': ${unknownArgs.joinToString()}"
          }

          val orderedArgs = flattenArgs(spec = spec, rawArgs = rawArgs)
          OscMessagePacket(address = spec.path, arguments = orderedArgs)
        }

    transport.send(
        packet = OscBundlePacket(timeTag = timeTag, elements = elements),
        target = target,
    )
  }

  /**
   * codegen 生成バンドルクラスのインスタンスを型安全にバンドル送信する。
   *
   * @param T バンドルの型
   * @param bundle 送信するバンドルインスタンス
   * @param target 送信先ターゲット
   * @param timeTag OSC タイムタグ（デフォルトは即時送信）
   */
  suspend fun <T : OscBundle> sendBundle(
      bundle: T,
      target: OscTarget,
      timeTag: Long = OscTimeTag.IMMEDIATE,
  ) = sendBundle(messages = bundle.toMessages(), target = target, timeTag = timeTag)

  /**
   * 受信パケットを種別に応じて処理する。
   *
   * @param packet 受信した OSC パケット
   */
  private suspend fun processPacket(packet: OscPacket) {
    when (packet) {
      is OscMessagePacket -> processMessage(packet)
      is OscBundlePacket -> packet.elements.forEach { processPacket(it) }
    }
  }

  /**
   * 受信した OSC メッセージパケットをスキーマに基づいてバリデーションし、ハンドラに振り分ける。
   *
   * @param packet 受信した OSC メッセージパケット
   */
  private suspend fun processMessage(packet: OscMessagePacket) {
    val spec = schema.findByPath(packet.address)
    if (spec == null) {
      _events.emit(OscRuntimeEvent.ValidationError("Unknown OSC path", packet.address))
      return
    }

    val namedArgs =
        try {
          unflattenArgs(spec = spec, wireArgs = packet.arguments)
        } catch (ex: IllegalArgumentException) {
          _events.emit(
              OscRuntimeEvent.ValidationError(
                  ex.message ?: "Invalid OSC payload",
                  spec.path,
              ),
          )
          return
        }

    val event = OscRuntimeEvent.Received(spec = spec, packet = packet, namedArgs = namedArgs)
    _events.emit(event)
    handlers[spec.path].orEmpty().forEach { handler -> scope.launch { handler(event) } }
  }

  /**
   * 名前付き引数マップをスキーマ定義順にフラット化したリストに変換する。
   *
   * @param spec メッセージ仕様
   * @param rawArgs 名前付き引数マップ
   * @return フラット化された引数リスト
   */
  private fun flattenArgs(spec: OscMessageSpec, rawArgs: Map<String, Any?>): List<Any?> {
    val flattened = mutableListOf<Any?>()

    val lengthContext = mutableMapOf<String, Int>()
    val derivedLengths = deriveLengthsFromArrayValues(spec = spec, rawArgs = rawArgs)

    for (argNode in spec.args) {
      when (argNode) {
        is ScalarArgNode -> {
          val raw =
              rawArgs[argNode.name]
                  ?: when {
                    argNode.role == ScalarRole.LENGTH && derivedLengths.containsKey(argNode.name) ->
                        derivedLengths.getValue(argNode.name)
                    else ->
                        throw IllegalArgumentException(
                            "Missing required arg '${argNode.name}' for '${spec.name}'",
                        )
                  }

          val converted = convertToType(raw = raw, type = argNode.type)
          if (argNode.role == ScalarRole.LENGTH) {
            val lengthValue = converted as Int
            require(lengthValue >= 0) {
              "Length arg '${argNode.name}' must be >= 0 for '${spec.name}'"
            }
            lengthContext[argNode.name] = lengthValue
          }
          flattened += converted
        }

        is ArrayArgNode -> {
          val raw =
              rawArgs[argNode.name]
                  ?: throw IllegalArgumentException(
                      "Missing required arg '${argNode.name}' for '${spec.name}'")
          val expectedLength =
              resolveExpectedLengthForSend(
                  argNode = argNode,
                  lengthContext = lengthContext,
                  spec = spec,
              )
          flattened.addAll(
              flattenArrayValue(
                  argNode = argNode,
                  rawValue = raw,
                  expectedLength = expectedLength,
                  spec = spec,
              ),
          )
        }
      }
    }

    return flattened
  }

  /**
   * 配列引数の値からフィールド参照型の長さを導出する。
   *
   * @param spec メッセージ仕様
   * @param rawArgs 名前付き引数マップ
   * @return フィールド名をキー、導出された長さを値とするマップ
   */
  private fun deriveLengthsFromArrayValues(
      spec: OscMessageSpec,
      rawArgs: Map<String, Any?>,
  ): Map<String, Int> {
    val derivedLengths = mutableMapOf<String, Int>()

    spec.args.forEach { argNode ->
      if (argNode is ArrayArgNode && argNode.length is LengthSpec.FromField) {
        val fieldName = argNode.length.fieldName
        val rawValue = rawArgs[argNode.name] ?: return@forEach
        val count = asListLike(rawValue, argNode.name, spec.name).size
        val existing = derivedLengths[fieldName]
        if (existing != null && existing != count) {
          throw IllegalArgumentException(
              "Conflicting derived length for '$fieldName' in '${spec.name}': $existing vs $count",
          )
        }
        derivedLengths[fieldName] = count
      }
    }

    return derivedLengths
  }

  /**
   * 送信時に配列の期待長を解決する。
   *
   * @param argNode 配列引数ノード
   * @param lengthContext 既に解決されたフィールド長のコンテキスト
   * @param spec メッセージ仕様
   * @return 期待される配列長
   */
  private fun resolveExpectedLengthForSend(
      argNode: ArrayArgNode,
      lengthContext: Map<String, Int>,
      spec: OscMessageSpec,
  ): Int {
    return when (val length = argNode.length) {
      is LengthSpec.Fixed -> length.size
      is LengthSpec.FromField ->
          lengthContext[length.fieldName]
              ?: throw IllegalArgumentException(
                  "Length source '${length.fieldName}' must be resolved before '${argNode.name}' in '${spec.name}'",
              )
    }
  }

  /**
   * 配列引数の値をフラット化してスカラーのリストに変換する。
   *
   * @param argNode 配列引数ノード
   * @param rawValue 配列の生値
   * @param expectedLength 期待される配列長
   * @param spec メッセージ仕様
   * @return フラット化されたスカラー値のリスト
   */
  private fun flattenArrayValue(
      argNode: ArrayArgNode,
      rawValue: Any,
      expectedLength: Int,
      spec: OscMessageSpec,
  ): List<Any?> {
    val list = asListLike(rawValue, argNode.name, spec.name)
    require(list.size == expectedLength) {
      "Invalid array size for '${argNode.name}' in '${spec.name}': expected $expectedLength, got ${list.size}"
    }

    val flattened = mutableListOf<Any?>()
    when (val item = argNode.item) {
      is ArrayItemSpec.ScalarItem -> {
        list.forEach { element ->
          require(element != null) {
            "Null array element is not allowed for '${argNode.name}' in '${spec.name}'"
          }
          flattened += convertToType(raw = element, type = item.type)
        }
      }

      is ArrayItemSpec.TupleItem -> {
        list.forEachIndexed { index, element ->
          val tupleMap =
              asMapLike(
                  value = element,
                  fieldName = "${argNode.name}[$index]",
                  specName = spec.name,
              )
          val unknownKeys = tupleMap.keys - item.fields.map { it.name }.toSet()
          require(unknownKeys.isEmpty()) {
            "Unknown tuple fields for '${argNode.name}[$index]' in '${spec.name}': ${unknownKeys.joinToString()}"
          }

          item.fields.forEach { field ->
            val rawField =
                tupleMap[field.name]
                    ?: throw IllegalArgumentException(
                        "Missing tuple field '${field.name}' in '${argNode.name}[$index]' for '${spec.name}'",
                    )
            flattened += convertToType(raw = rawField, type = field.type)
          }
        }
      }
    }

    return flattened
  }

  /**
   * ワイヤ上のフラットな引数リストをスキーマ定義に基づき名前付き引数マップに復元する。
   *
   * @param spec メッセージ仕様
   * @param wireArgs ワイヤ上のフラット引数リスト
   * @return 名前付き引数マップ
   * @throws IllegalArgumentException 引数の数や型が仕様と一致しない場合
   */
  private fun unflattenArgs(spec: OscMessageSpec, wireArgs: List<Any?>): Map<String, Any?> {
    val namedArgs = LinkedHashMap<String, Any?>()
    val lengthContext = mutableMapOf<String, Int>()
    var cursor = 0

    fun consumeScalar(type: OscType, contextName: String): Any {
      if (cursor >= wireArgs.size) {
        throw IllegalArgumentException("Missing value for '$contextName' in '${spec.path}'")
      }
      val value = wireArgs[cursor]
      cursor += 1
      if (!isTypeCompatible(value, type)) {
        throw IllegalArgumentException(
            "Invalid type for '$contextName' in '${spec.path}': expected $type, got ${value?.let { it::class.simpleName } ?: "null"}",
        )
      }
      return value!!
    }

    spec.args.forEach { argNode ->
      when (argNode) {
        is ScalarArgNode -> {
          val scalar = consumeScalar(type = argNode.type, contextName = argNode.name)
          namedArgs[argNode.name] = scalar
          if (argNode.role == ScalarRole.LENGTH) {
            val length = scalar as Int
            require(length >= 0) { "Length arg '${argNode.name}' must be >= 0 in '${spec.path}'" }
            lengthContext[argNode.name] = length
          }
        }

        is ArrayArgNode -> {
          val itemCount =
              when (val length = argNode.length) {
                is LengthSpec.Fixed -> length.size
                is LengthSpec.FromField ->
                    lengthContext[length.fieldName]
                        ?: throw IllegalArgumentException(
                            "Length source '${length.fieldName}' is missing before '${argNode.name}' in '${spec.path}'",
                        )
              }

          val value =
              when (val item = argNode.item) {
                is ArrayItemSpec.ScalarItem -> {
                  buildList(itemCount) {
                    repeat(itemCount) {
                      add(consumeScalar(type = item.type, contextName = argNode.name))
                    }
                  }
                }

                is ArrayItemSpec.TupleItem -> {
                  buildList(itemCount) {
                    repeat(itemCount) { tupleIndex ->
                      val tuple = LinkedHashMap<String, Any?>()
                      item.fields.forEach { field ->
                        tuple[field.name] =
                            consumeScalar(
                                type = field.type,
                                contextName = "${argNode.name}[$tupleIndex].${field.name}",
                            )
                      }
                      add(tuple)
                    }
                  }
                }
              }
          namedArgs[argNode.name] = value
        }
      }
    }

    if (cursor != wireArgs.size) {
      throw IllegalArgumentException(
          "Invalid arg count for '${spec.path}': expected $cursor, got ${wireArgs.size}",
      )
    }

    return namedArgs
  }

  /**
   * 値をリスト形式に変換する。List、Array、Iterable に対応する。
   *
   * @param value 変換対象の値
   * @param fieldName エラーメッセージに含めるフィールド名
   * @param specName エラーメッセージに含めるスキーマ定義名
   * @return リスト形式に変換された値
   * @throws IllegalArgumentException 値がリスト互換でない場合
   */
  private fun asListLike(value: Any, fieldName: String, specName: String): List<Any?> {
    return when (value) {
      is List<*> -> value
      is Array<*> -> value.toList()
      is Iterable<*> -> value.toList()
      else ->
          throw IllegalArgumentException(
              "Arg '$fieldName' for '$specName' must be an array value",
          )
    }
  }

  /**
   * 値を文字列キーのマップ形式に変換する。
   *
   * @param value 変換対象の値
   * @param fieldName エラーメッセージに含めるフィールド名
   * @param specName エラーメッセージに含めるスキーマ定義名
   * @return 文字列キーのマップ
   * @throws IllegalArgumentException 値が Map でない、またはキーが文字列でない場合
   */
  private fun asMapLike(value: Any?, fieldName: String, specName: String): Map<String, Any?> {
    require(value is Map<*, *>) { "Arg '$fieldName' for '$specName' must be an object value" }
    val result = LinkedHashMap<String, Any?>()
    value.forEach { (rawKey, rawValue) ->
      require(rawKey is String) {
        "Arg '$fieldName' for '$specName' contains non-string object key"
      }
      result[rawKey] = rawValue
    }
    return result
  }

  /**
   * 生の値を指定された [OscType] に対応する Kotlin 型に変換する。
   *
   * @param raw 変換対象の生値
   * @param type 変換先の OSC 型
   * @return 変換後の値
   * @throws IllegalArgumentException 値が指定された型に変換できない場合
   */
  private fun convertToType(raw: Any, type: OscType): Any {
    return when (type) {
      OscType.INT ->
          when (raw) {
            is Int -> raw
            is Number -> raw.toInt()
            is String ->
                raw.toIntOrNull()
                    ?: throw IllegalArgumentException("Value '$raw' is not a valid INT")
            else -> throw IllegalArgumentException("Value '$raw' is not a valid INT")
          }

      OscType.FLOAT ->
          when (raw) {
            is Float -> raw
            is Double -> raw.toFloat()
            is Number -> raw.toFloat()
            is String ->
                raw.toFloatOrNull()
                    ?: throw IllegalArgumentException("Value '$raw' is not a valid FLOAT")
            else -> throw IllegalArgumentException("Value '$raw' is not a valid FLOAT")
          }

      OscType.STRING -> raw.toString()

      OscType.BOOL ->
          when (raw) {
            is Boolean -> raw
            is Int -> {
              require(raw == 0 || raw == 1) {
                "Value '$raw' is not a valid BOOL: integer must be 0 or 1"
              }
              raw != 0
            }
            is String ->
                when (raw.lowercase()) {
                  "true",
                  "1",
                  "yes" -> true
                  "false",
                  "0",
                  "no" -> false
                  else ->
                      throw IllegalArgumentException(
                          "Value '$raw' is not a valid BOOL: expected true/false/1/0/yes/no",
                      )
                }
            else -> throw IllegalArgumentException("Value '$raw' is not a valid BOOL")
          }

      OscType.BLOB ->
          when (raw) {
            is ByteArray -> raw
            is String -> java.util.Base64.getDecoder().decode(raw)
            else -> throw IllegalArgumentException("Value '$raw' is not a valid BLOB")
          }
    }
  }

  /**
   * 値が指定された [OscType] と互換性があるかを判定する。
   *
   * @param value 判定対象の値
   * @param type 期待する OSC 型
   * @return 互換性がある場合は true
   */
  private fun isTypeCompatible(value: Any?, type: OscType): Boolean {
    return when (type) {
      OscType.INT -> value is Int
      OscType.FLOAT -> value is Float || value is Double
      OscType.STRING -> value is String
      OscType.BOOL -> value is Boolean
      OscType.BLOB -> value is ByteArray
    }
  }

  /**
   * メッセージ参照名をスキーマから解決して仕様を返す。
   *
   * @param messageRef メッセージ参照名（スキーマ定義名またはパス）
   * @return 解決されたメッセージ仕様
   * @throws IllegalArgumentException メッセージ参照が不明な場合
   */
  private fun resolveMessageSpec(messageRef: String): OscMessageSpec {
    return schema.resolveMessage(messageRef)
        ?: throw IllegalArgumentException("Unknown message reference: $messageRef")
  }

  /**
   * 既知のメッセージ仕様をスキーマで検証し、正規化された仕様を返す。
   *
   * @param spec 検証対象のメッセージ仕様
   * @return スキーマから解決された正規化済みメッセージ仕様
   * @throws IllegalArgumentException パスが不明、または名前が一致しない場合
   */
  private fun resolveKnownMessageSpec(spec: OscMessageSpec): OscMessageSpec {
    val normalizedPath = OscSchema.normalizePath(spec.path)
    val resolved =
        schema.findByPath(normalizedPath)
            ?: throw IllegalArgumentException("Unknown message spec path: $normalizedPath")

    require(resolved.name == spec.name) {
      "Unknown message spec identity: expected name '${resolved.name}' for path '$normalizedPath', got '${spec.name}'"
    }

    return resolved
  }
}
