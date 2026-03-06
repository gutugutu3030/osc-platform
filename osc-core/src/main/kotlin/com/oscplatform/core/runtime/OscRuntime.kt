package com.oscplatform.core.runtime

import com.oscplatform.core.schema.OscMessageSpec
import com.oscplatform.core.schema.OscSchema
import com.oscplatform.core.schema.OscType
import com.oscplatform.core.transport.OscBundlePacket
import com.oscplatform.core.transport.OscMessagePacket
import com.oscplatform.core.transport.OscPacket
import com.oscplatform.core.transport.OscTarget
import com.oscplatform.core.transport.OscTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

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
}

class OscRuntime(
    private val schema: OscSchema,
    private val transport: OscTransport,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _events = MutableSharedFlow<OscRuntimeEvent>(extraBufferCapacity = 128)
    val events: Flow<OscRuntimeEvent> = _events.asSharedFlow()

    private val handlers = ConcurrentHashMap<String, CopyOnWriteArrayList<suspend (OscRuntimeEvent.Received) -> Unit>>()
    private var receiveJob: Job? = null

    fun on(path: String, handler: suspend (OscRuntimeEvent.Received) -> Unit) {
        val normalizedPath = OscSchema.normalizePath(path)
        handlers.computeIfAbsent(normalizedPath) { CopyOnWriteArrayList() }.add(handler)
    }

    suspend fun start() {
        if (receiveJob != null) {
            return
        }

        transport.start()
        receiveJob = scope.launch {
            transport.incomingPackets.collect { packet ->
                processPacket(packet)
            }
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
        val spec = schema.resolveMessage(messageRef)
            ?: throw IllegalArgumentException("Unknown message reference: $messageRef")

        val specArgNames = spec.args.map { it.name }.toSet()
        val unknownArgs = rawArgs.keys - specArgNames
        require(unknownArgs.isEmpty()) {
            "Unknown args for '${spec.name}': ${unknownArgs.joinToString()}"
        }

        val orderedArgs = spec.args.map { arg ->
            val raw = rawArgs[arg.name]
            if (raw == null) {
                throw IllegalArgumentException("Missing required arg '${arg.name}' for '${spec.name}'")
            }
            convertToType(raw, arg.type)
        }

        transport.send(
            packet = OscMessagePacket(address = spec.path, arguments = orderedArgs),
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

        if (packet.arguments.size != spec.args.size) {
            _events.emit(
                OscRuntimeEvent.ValidationError(
                    "Invalid arg count for '${spec.path}': expected ${spec.args.size}, got ${packet.arguments.size}",
                    spec.path,
                ),
            )
            return
        }

        val namedArgs = LinkedHashMap<String, Any?>()
        for ((index, argSpec) in spec.args.withIndex()) {
            val rawValue = packet.arguments[index]
            if (!isTypeCompatible(rawValue, argSpec.type)) {
                _events.emit(
                    OscRuntimeEvent.ValidationError(
                        "Invalid type for '${argSpec.name}' in '${spec.path}': expected ${argSpec.type}, got ${rawValue?.let { it::class.simpleName } ?: "null"}",
                        spec.path,
                    ),
                )
                return
            }
            namedArgs[argSpec.name] = rawValue
        }

        val event = OscRuntimeEvent.Received(spec = spec, packet = packet, namedArgs = namedArgs)
        _events.emit(event)
        handlers[spec.path].orEmpty().forEach { handler ->
            scope.launch {
                handler(event)
            }
        }
    }

    private fun convertToType(raw: Any, type: OscType): Any {
        return when (type) {
            OscType.INT -> when (raw) {
                is Int -> raw
                is Number -> raw.toInt()
                is String -> raw.toIntOrNull()
                    ?: throw IllegalArgumentException("Value '$raw' is not a valid INT")
                else -> throw IllegalArgumentException("Value '$raw' is not a valid INT")
            }

            OscType.FLOAT -> when (raw) {
                is Float -> raw
                is Double -> raw.toFloat()
                is Number -> raw.toFloat()
                is String -> raw.toFloatOrNull()
                    ?: throw IllegalArgumentException("Value '$raw' is not a valid FLOAT")
                else -> throw IllegalArgumentException("Value '$raw' is not a valid FLOAT")
            }

            OscType.STRING -> raw.toString()
        }
    }

    private fun isTypeCompatible(value: Any?, type: OscType): Boolean {
        return when (type) {
            OscType.INT -> value is Int
            OscType.FLOAT -> value is Float || value is Double
            OscType.STRING -> value is String
        }
    }
}
