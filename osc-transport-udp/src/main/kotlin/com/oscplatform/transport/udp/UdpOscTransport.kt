package com.oscplatform.transport.udp

import com.oscplatform.core.transport.OscPacket
import com.oscplatform.core.transport.OscTarget
import com.oscplatform.core.transport.OscTransport
import com.oscplatform.core.transport.TransportError
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 連続受信失敗がこの回数を超えた場合に受信ループを停止するサーキットブレーカ閾値。 */
private const val CIRCUIT_BREAKER_THRESHOLD = 25

/**
 * UDP経由でOSCパケットを送受信するトランスポート実装。
 *
 * 指定されたホストとポートにバインドしてOSCパケットを受信し、 任意の [OscTarget] に対してOSCパケットを送信する。
 * サーキットブレーカにより、連続受信失敗が閾値を超えた場合に受信ループを自動停止する。
 *
 * @param bindHost バインドするホスト名またはIPアドレス
 * @param bindPort バインドするUDPポート番号
 * @param scope コルーチンスコープ（受信ループの起動に使用される）
 */
class UdpOscTransport(
    private val bindHost: String,
    private val bindPort: Int,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : OscTransport {
  private val _incomingPackets = MutableSharedFlow<OscPacket>(extraBufferCapacity = 256)

  /** 受信したOSCパケットのフロー。 */
  override val incomingPackets: Flow<OscPacket> = _incomingPackets.asSharedFlow()

  private val _errors = MutableSharedFlow<TransportError>(extraBufferCapacity = 64)

  /** トランスポート層で発生したエラーのフロー。 */
  override val errors: Flow<TransportError> = _errors.asSharedFlow()

  /** 現在までの連続受信失敗回数。正常受信時にリセットされる。 */
  @Volatile
  var consecutiveErrorCount: Int = 0
    private set

  /** 最後に発生した受信エラー。正常受信時にリセットされる。 */
  @Volatile
  var lastReceiveError: Throwable? = null
    private set

  @Volatile private var receiveSocket: DatagramSocket? = null
  private var receiveJob: Job? = null
  private val lifecycleMutex = Mutex()

  /**
   * トランスポートを開始し、UDPソケットをバインドして受信ループを起動する。
   *
   * stop完了後であれば同一インスタンスに対して再度開始できる。既に開始済みの場合は何もしない。
   */
  override suspend fun start() {
    lifecycleMutex.withLock {
      if (receiveSocket != null) {
        return
      }

      val hostAddress = InetAddress.getByName(bindHost)
      val socket = DatagramSocket(bindPort, hostAddress)
      try {
        receiveSocket = socket
        receiveJob = scope.launch { receiveLoop(socket) }
      } catch (ex: Throwable) {
        socket.close()
        throw ex
      }
    }
  }

  /**
   * トランスポートを停止し、UDPソケットを閉じて受信ループの終了まで待機する。
   *
   * このメソッドが戻った時点で受信リソースは解放済みであり、同一インスタンスに対して再度 [start] を呼び出せる。
   */
  override suspend fun stop() {
    lifecycleMutex.withLock {
      val socket = receiveSocket
      val job = receiveJob

      receiveSocket = null
      receiveJob = null

      // 1. 以降の start が古いソケット状態を参照しないように公開状態を先に切り離す。
      // 2. close() で receive() のブロックを解除し、cancelAndJoin() で受信ループ終了まで待機する。
      socket?.close()
      job?.cancelAndJoin()
    }
  }

  /**
   * OSCパケットを指定されたターゲットへUDP経由で送信する。
   *
   * 受信用ソケットが利用可能であればそれを再利用し、なければ一時ソケットを作成して送信する。
   *
   * @param packet 送信するOSCパケット
   * @param target 送信先の [OscTarget]（ホストとポート）
   */
  override suspend fun send(packet: OscPacket, target: OscTarget) {
    val bytes = OscCodec.encode(packet)
    withContext(Dispatchers.IO) {
      val socket = receiveSocket
      if (socket != null && !socket.isClosed) {
        sendDatagram(socket, bytes, target)
      } else {
        DatagramSocket().use { ephemeral -> sendDatagram(ephemeral, bytes, target) }
      }
    }
  }

  /**
   * UDPデータグラムの受信ループを実行する。
   *
   * ソケットからデータグラムを受信し、OSCパケットにデコードして [incomingPackets] フローへ発行する。 連続失敗回数が [CIRCUIT_BREAKER_THRESHOLD]
   * に達した場合、ループを停止する。
   *
   * @param socket 受信に使用する [DatagramSocket]
   * @throws IllegalStateException 連続受信失敗が閾値を超えた場合
   */
  private suspend fun receiveLoop(socket: DatagramSocket) {
    val buffer = ByteArray(65535)
    while (scope.isActive && !socket.isClosed) {
      val datagram = DatagramPacket(buffer, buffer.size)
      try {
        socket.receive(datagram)
        val payload = datagram.data.copyOf(datagram.length)
        val packet = OscCodec.decode(payload)
        consecutiveErrorCount = 0
        lastReceiveError = null
        _incomingPackets.emit(packet)
      } catch (socketClosed: SocketException) {
        if (!socket.isClosed) {
          throw socketClosed
        }
      } catch (ex: Exception) {
        consecutiveErrorCount++
        lastReceiveError = ex
        _errors.tryEmit(TransportError(cause = ex, consecutiveCount = consecutiveErrorCount))
        if (consecutiveErrorCount >= CIRCUIT_BREAKER_THRESHOLD) {
          // 連続失敗が閾値を超えたため受信ループを停止する
          throw IllegalStateException(
              "UDP receive loop stopped after $consecutiveErrorCount consecutive errors. Last error: ${ex.message}",
              ex,
          )
        }
        delay(2)
      }
    }
  }

  /**
   * 指定されたソケットを使用してUDPデータグラムを送信する。
   *
   * @param socket 送信に使用する [DatagramSocket]
   * @param payload 送信するバイト配列
   * @param target 送信先の [OscTarget]（ホストとポート）
   */
  private fun sendDatagram(
      socket: DatagramSocket,
      payload: ByteArray,
      target: OscTarget,
  ) {
    val targetAddress = InetAddress.getByName(target.host)
    val datagram = DatagramPacket(payload, payload.size, targetAddress, target.port)
    socket.send(datagram)
  }
}
