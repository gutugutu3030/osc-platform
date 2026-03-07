package com.oscplatform.core.runtime

import com.oscplatform.core.schema.OscSchema
import com.oscplatform.core.schema.dsl.FLOAT
import com.oscplatform.core.schema.dsl.INT
import com.oscplatform.core.schema.dsl.LENGTH
import com.oscplatform.core.schema.dsl.oscSchema
import com.oscplatform.core.transport.OscMessagePacket
import com.oscplatform.core.transport.OscPacket
import com.oscplatform.core.transport.OscTarget
import com.oscplatform.core.transport.OscTransport
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OscRuntimeStructuredArgsTest {
    @Test
    fun sendFlattensStructuredArgsAndDerivesLength(): Unit = runBlocking {
        val transport = FakeTransport()
        val runtime = OscRuntime(schema = meshSchema(), transport = transport)

        runtime.send(
            messageRef = "mesh.points",
            rawArgs = mapOf(
                "points" to listOf(
                    mapOf("x" to 1, "y" to 2, "z" to 3.0),
                    mapOf("x" to 4, "y" to 5, "z" to 6.5),
                ),
            ),
            target = OscTarget("127.0.0.1", 9000),
        )

        val packet = transport.sentMessages.single()
        assertEquals("/mesh/points", packet.address)
        assertEquals(listOf(2, 1, 2, 3.0f, 4, 5, 6.5f), packet.arguments)
    }

    @Test
    fun sendRejectsLengthMismatch(): Unit = runBlocking {
        val transport = FakeTransport()
        val runtime = OscRuntime(schema = meshSchema(), transport = transport)

        val ex = assertFailsWith<IllegalArgumentException> {
            runtime.send(
                messageRef = "mesh.points",
                rawArgs = mapOf(
                    "pointCount" to 3,
                    "points" to listOf(
                        mapOf("x" to 1, "y" to 2, "z" to 3.0),
                        mapOf("x" to 4, "y" to 5, "z" to 6.5),
                    ),
                ),
                target = OscTarget("127.0.0.1", 9000),
            )
        }

        assertTrue(ex.message?.contains("Invalid array size") == true)
    }

    @Test
    fun sendRejectsUnknownTupleFields(): Unit = runBlocking {
        val transport = FakeTransport()
        val runtime = OscRuntime(schema = meshSchema(), transport = transport)

        val ex = assertFailsWith<IllegalArgumentException> {
            runtime.send(
                messageRef = "mesh.points",
                rawArgs = mapOf(
                    "points" to listOf(
                        mapOf("x" to 1, "y" to 2, "z" to 3.0, "w" to 999),
                    ),
                ),
                target = OscTarget("127.0.0.1", 9000),
            )
        }

        assertTrue(ex.message?.contains("Unknown tuple fields") == true)
    }

    @Test
    fun sendRejectsNullScalarArrayElements(): Unit = runBlocking {
        val transport = FakeTransport()
        val runtime = OscRuntime(schema = scalarArraySchema(), transport = transport)

        val ex = assertFailsWith<IllegalArgumentException> {
            runtime.send(
                messageRef = "sensor.values",
                rawArgs = mapOf(
                    "values" to listOf(10, null),
                ),
                target = OscTarget("127.0.0.1", 9000),
            )
        }

        assertTrue(ex.message?.contains("Null array element") == true)
    }

    @Test
    fun sendDerivesSharedLengthForMultipleArrays(): Unit = runBlocking {
        val transport = FakeTransport()
        val runtime = OscRuntime(schema = dualArraySchema(), transport = transport)

        runtime.send(
            messageRef = "mesh.dual",
            rawArgs = mapOf(
                "left" to listOf(1, 2),
                "right" to listOf(3, 4),
            ),
            target = OscTarget("127.0.0.1", 9000),
        )

        val packet = transport.sentMessages.single()
        assertEquals("/mesh/dual", packet.address)
        assertEquals(listOf(2, 1, 2, 3, 4), packet.arguments)
    }

    @Test
    fun sendRejectsConflictingDerivedLengthAcrossArrays(): Unit = runBlocking {
        val transport = FakeTransport()
        val runtime = OscRuntime(schema = dualArraySchema(), transport = transport)

        val ex = assertFailsWith<IllegalArgumentException> {
            runtime.send(
                messageRef = "mesh.dual",
                rawArgs = mapOf(
                    "left" to listOf(1, 2),
                    "right" to listOf(3, 4, 5),
                ),
                target = OscTarget("127.0.0.1", 9000),
            )
        }

        assertTrue(ex.message?.contains("Conflicting derived length") == true)
    }

    @Test
    fun receiveUnflattensStructuredArgs(): Unit = runBlocking {
        val transport = FakeTransport()
        val runtime = OscRuntime(schema = meshSchema(), transport = transport)
        runtime.start()

        try {
            val receivedDeferred = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(1000) {
                    runtime.events.filterIsInstance<OscRuntimeEvent.Received>().first()
                }
            }

            transport.emit(
                OscMessagePacket(
                    address = "/mesh/points",
                    arguments = listOf(2, 1, 2, 3.0f, 4, 5, 6.5f),
                ),
            )

            val event = receivedDeferred.await()
            assertEquals(2, event.namedArgs["pointCount"])

            val points = assertIs<List<*>>(event.namedArgs["points"])
            assertEquals(2, points.size)

            val first = assertIs<Map<*, *>>(points[0])
            assertEquals(1, first["x"])
            assertEquals(2, first["y"])
            assertEquals(3.0f, first["z"])
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun receiveEmitsValidationErrorForShortPayload(): Unit = runBlocking {
        val transport = FakeTransport()
        val runtime = OscRuntime(schema = meshSchema(), transport = transport)
        runtime.start()

        try {
            val errorDeferred = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(1000) {
                    runtime.events.filterIsInstance<OscRuntimeEvent.ValidationError>().first()
                }
            }

            transport.emit(
                OscMessagePacket(
                    address = "/mesh/points",
                    arguments = listOf(2, 1, 2, 3.0f),
                ),
            )

            val error = errorDeferred.await()
            assertTrue(error.reason.contains("Missing value"))
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun receiveEmitsValidationErrorForExtraTrailingArgs(): Unit = runBlocking {
        val transport = FakeTransport()
        val runtime = OscRuntime(schema = meshSchema(), transport = transport)
        runtime.start()

        try {
            val errorDeferred = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(1000) {
                    runtime.events.filterIsInstance<OscRuntimeEvent.ValidationError>().first()
                }
            }

            transport.emit(
                OscMessagePacket(
                    address = "/mesh/points",
                    arguments = listOf(1, 1, 2, 3.0f, 999),
                ),
            )

            val error = errorDeferred.await()
            assertTrue(error.reason.contains("Invalid arg count"))
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun receiveEmitsValidationErrorForNegativeLength(): Unit = runBlocking {
        val transport = FakeTransport()
        val runtime = OscRuntime(schema = meshSchema(), transport = transport)
        runtime.start()

        try {
            val errorDeferred = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(1000) {
                    runtime.events.filterIsInstance<OscRuntimeEvent.ValidationError>().first()
                }
            }

            transport.emit(
                OscMessagePacket(
                    address = "/mesh/points",
                    arguments = listOf(-1),
                ),
            )

            val error = errorDeferred.await()
            assertTrue(error.reason.contains("must be >= 0"))
        } finally {
            runtime.stop()
        }
    }

    private fun meshSchema(): OscSchema {
        return oscSchema {
            message("/mesh/points") {
                scalar("pointCount", INT, role = LENGTH)
                array("points", lengthFrom = "pointCount") {
                    tuple {
                        field("x", INT)
                        field("y", INT)
                        field("z", FLOAT)
                    }
                }
            }
        }
    }

    private fun scalarArraySchema(): OscSchema {
        return oscSchema {
            message("/sensor/values") {
                scalar("valueCount", INT, role = LENGTH)
                array("values", lengthFrom = "valueCount") {
                    scalar(INT)
                }
            }
        }
    }

    private fun dualArraySchema(): OscSchema {
        return oscSchema {
            message("/mesh/dual") {
                scalar("count", INT, role = LENGTH)
                array("left", lengthFrom = "count") {
                    scalar(INT)
                }
                array("right", lengthFrom = "count") {
                    scalar(INT)
                }
            }
        }
    }
}

private class FakeTransport : OscTransport {
    override val incomingPackets: MutableSharedFlow<OscPacket> = MutableSharedFlow(extraBufferCapacity = 16)
    val sentMessages: MutableList<OscMessagePacket> = mutableListOf()

    override suspend fun start() {
        // no-op for tests
    }

    override suspend fun stop() {
        // no-op for tests
    }

    override suspend fun send(packet: OscPacket, target: OscTarget) {
        sentMessages += assertIs<OscMessagePacket>(packet)
    }

    suspend fun emit(packet: OscPacket) {
        incomingPackets.emit(packet)
    }
}
