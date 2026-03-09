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

sealed interface OscRuntimeEvent {
  data class Received(
      val spec: OscMessageSpec,
      val packet: OscMessagePacket,
      val namedArgs: Map<String, Any?>,
  ) : OscRuntimeEvent

  data class ValidationError(
      val reason: String,
      val address: String?,
  ) : OscRuntimeEvent

  /** Transport受信ループで発生したエラー。連続失敗回数を含む。 */
  data class TransportErrorEvent(
      val error: TransportError,
  ) : OscRuntimeEvent
}

class OscRuntime(
    private val schema: OscSchema,
    private val transport: OscTransport,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
  private val _events = MutableSharedFlow<OscRuntimeEvent>(extraBufferCapacity = 128)
  val events: Flow<OscRuntimeEvent> = _events.asSharedFlow()

  private val handlers =
      ConcurrentHashMap<String, CopyOnWriteArrayList<suspend (OscRuntimeEvent.Received) -> Unit>>()
  private var receiveJob: Job? = null

  fun on(spec: OscMessageSpec, handler: suspend (OscRuntimeEvent.Received) -> Unit) {
    val registered = resolveKnownMessageSpec(spec)
    handlers.computeIfAbsent(registered.path) { CopyOnWriteArrayList() }.add(handler)
  }

  fun <T : OscMessage> on(companion: OscMessageCompanion<T>, handler: suspend (T) -> Unit) {
    val spec =
        schema.resolveMessage(companion.NAME)
            ?: error("Schema has no message: ${companion.NAME}")
    on(spec) { event -> handler(companion.fromNamedArgs(event.namedArgs)) }
  }

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

  suspend fun stop() {
    receiveJob?.cancelAndJoin()
    receiveJob = null
    transport.stop()
  }

  suspend fun send(
      messageRef: String,
      rawArgs: Map<String, Any?>,
      target: OscTarget,
  ) {
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
  }

  suspend fun <T : OscMessage> send(
      companion: OscMessageCompanion<T>,
      msg: T,
      target: OscTarget,
  ) = send(messageRef = companion.NAME, rawArgs = msg.toNamedArgs(), target = target)

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

  private suspend fun processPacket(packet: OscPacket) {
    when (packet) {
      is OscMessagePacket -> processMessage(packet)
      is OscBundlePacket -> packet.elements.forEach { processPacket(it) }
    }
  }

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

  private fun isTypeCompatible(value: Any?, type: OscType): Boolean {
    return when (type) {
      OscType.INT -> value is Int
      OscType.FLOAT -> value is Float || value is Double
      OscType.STRING -> value is String
      OscType.BOOL -> value is Boolean
      OscType.BLOB -> value is ByteArray
    }
  }

  private fun resolveMessageSpec(messageRef: String): OscMessageSpec {
    return schema.resolveMessage(messageRef)
        ?: throw IllegalArgumentException("Unknown message reference: $messageRef")
  }

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
