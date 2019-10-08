package com.projectswg.holocore.client

import com.projectswg.common.network.NetBuffer
import com.projectswg.common.network.packets.swg.holo.HoloConnectionStarted
import com.projectswg.common.network.packets.swg.holo.HoloConnectionStopped
import com.projectswg.common.network.packets.swg.holo.HoloConnectionStopped.ConnectionStoppedReason
import com.projectswg.common.network.packets.swg.holo.HoloSetProtocolVersion
import me.joshlarson.jlcommon.concurrency.Delay
import me.joshlarson.jlcommon.log.Log
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.*
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class HolocoreSocket @JvmOverloads constructor(addr: InetAddress, port: Int, private val verifyServer: Boolean = true, private val encryptionEnabled: Boolean = true) : Closeable {
	
	private val status: AtomicReference<ServerConnectionStatus> = AtomicReference(ServerConnectionStatus.DISCONNECTED)
	private val udpServer = DatagramSocket()
	private val socket = createSocket()
	
	private var callback: StatusChangedCallback? = null
	private var socketInputStream: HolocoreInputStream? = null
	private var socketOutputStream: HolocoreOutputStream? = null
	
	/**
	 * Returns the remote address this socket is pointing to
	 * @return the remote address as an InetSocketAddress
	 */
	var remoteAddress: InetSocketAddress? = null
		private set
	
	/**
	 * Gets the current connection state of the socket
	 * @return the connection state
	 */
	val connectionState: ServerConnectionStatus
		get() = status.get()
	
	/**
	 * Returns whether or not this socket is disconnected
	 * @return TRUE if disconnected, FALSE otherwise
	 */
	val isDisconnected: Boolean
		get() = status.get() == ServerConnectionStatus.DISCONNECTED
	
	/**
	 * Returns whether or not this socket is connecting
	 * @return TRUE if connecting, FALSE otherwise
	 */
	val isConnecting: Boolean
		get() = status.get() == ServerConnectionStatus.CONNECTING
	
	/**
	 * Returns whether or not this socket is connected
	 * @return TRUE if connected, FALSE otherwise
	 */
	val isConnected: Boolean
		get() = status.get() == ServerConnectionStatus.CONNECTED
	
	/**
	 * Retrieves the server status via a UDP query, with the default timeout of 2000ms
	 * @return the server status as a string
	 */
	val serverStatus: String
		get() = getServerStatus(2000)
	
	init {
		this.callback = null
		this.remoteAddress = InetSocketAddress(addr, port)
	}
	
	/**
	 * Shuts down any miscellaneous resources--such as the query UDP server
	 */
	override fun close() {
		udpServer.close()
		
		disconnect(ConnectionStoppedReason.APPLICATION)
		
		Log.t("Disconnecting from Holocore")
		val socket = this.socket
		socketOutputStream?.close()
		socketInputStream?.close()
		socket.close()
		
		socketOutputStream = null
		socketInputStream = null
	}
	
	/**
	 * Shuts down any miscellaneous resources--such as the query UDP server
	 */
	@Deprecated("should be replaced with close()", replaceWith=ReplaceWith("close()"))
	fun terminate() {
		close()
	}
	
	/**
	 * Sets a callback for when the status of the server socket changes
	 * @param callback the callback
	 */
	fun setStatusChangedCallback(callback: StatusChangedCallback) {
		this.callback = callback
	}
	
	/**
	 * Sets the remote address this socket will attempt to connect to
	 * @param addr the destination address
	 * @param port the destination port
	 */
	fun setRemoteAddress(addr: InetAddress, port: Int) {
		this.remoteAddress = InetSocketAddress(addr, port)
	}
	
	/**
	 * Retrives the server status via a UDP query, with the specified timeout
	 * @param timeout the timeout in milliseconds
	 * @return the server status as a string
	 */
	fun getServerStatus(timeout: Long): String {
		Log.t("Requesting server status from %s", remoteAddress!!)
		udpServer.send(DatagramPacket(byteArrayOf(1), 1, remoteAddress!!))
		val packet = DatagramPacket(ByteArray(512), 512)
		
		return try {
			udpServer.soTimeout = timeout.toInt()
			udpServer.receive(packet)
			udpServer.soTimeout = 0
			val data = NetBuffer.wrap(packet.data)
			data.byte
			data.ascii // status string
		} catch (e: Throwable) {
			"OFFLINE" // status string = OFFLINE
		}
	}
	
	/**
	 * Attempts to connect to the remote server. This call is a blocking function that will not
	 * return until it has either successfully connected or has failed with an exception. It
	 * starts by initializing a TCP connection, then initializes the Holocore connection, then
	 * returns.
	 * @param timeout the timeout for the connect call
	 */
	fun connect(timeout: Int) {
		try {
			Log.t("Connecting to Holocore: encryption=%b", encryptionEnabled)
			socket.connect(remoteAddress ?: throw IllegalStateException("no remote endpoint defined"))
			socketInputStream = HolocoreInputStream(socket.getInputStream())
			socketOutputStream = HolocoreOutputStream(socket.getOutputStream())
			
			updateStatus(ServerConnectionStatus.CONNECTING, ServerConnectionChangedReason.NONE)
			waitForConnect(timeout)
		} catch (e: Throwable) {
			updateStatus(ServerConnectionStatus.DISCONNECTED, getReason(e.message))
			socket.close()
			throw e
		}
	}
	
	/**
	 * Attempts to disconnect from the server with the specified reason. Before this socket is
	 * closed, it will send a HoloConnectionStopped packet to notify the remote server.
	 * @param reason the reason for disconnecting
	 * @return TRUE if successfully disconnected, FALSE on error
	 */
	fun disconnect(reason: ConnectionStoppedReason): Boolean {
		when (status.get()) {
			ServerConnectionStatus.CONNECTING, ServerConnectionStatus.DISCONNECTING, ServerConnectionStatus.DISCONNECTED -> socket.close()
			ServerConnectionStatus.CONNECTED -> {
				updateStatus(ServerConnectionStatus.DISCONNECTING, ServerConnectionChangedReason.CLIENT_DISCONNECT)
				send(HoloConnectionStopped(reason).encode().array())
			}
			else -> socket.close()
		}
		return true
	}
	
	/**
	 * Attempts to send a byte array to the remote server. This method blocks until it has
	 * completely sent or has failed.
	 * @param raw the byte array to send
	 * @return TRUE on success, FALSE on failure
	 */
	fun send(raw: ByteArray): Boolean {
		try {
			socketOutputStream?.write(raw)
			return true
		} catch (e: IOException) {
			return false
		}
	}
	
	/**
	 * Attempts to receive a packet from the remote server. This method blocks until a packet is
	 * recieved or has failed.
	 * @return the RawPacket containing the CRC of the SWG message and the raw data array, or NULL
	 * on error
	 */
	fun receive(): RawPacket? {
		try {
			val packet = socketInputStream?.read() ?: return null
			handlePacket(packet.crc, packet.data)
			return packet
		} catch (e: InterruptedException) {
			return null
		} catch (e: EOFException) {
			return null
		}
	}
	
	private fun waitForConnect(timeout: Int) {
		socket.soTimeout = timeout
		try {
			send(HoloSetProtocolVersion(HolocoreProtocol.VERSION).encode().array())
			while (isConnecting && !Delay.isInterrupted()) {
				receive() ?: throw IOException("socket closed")
			}
		} finally {
			socket.soTimeout = 0 // Reset back to how it was before the function
		}
	}
	
	private fun handlePacket(crc: Int, raw: ByteArray) {
		when (crc) {
			HoloConnectionStarted.CRC -> updateStatus(ServerConnectionStatus.CONNECTED, ServerConnectionChangedReason.NONE)
			HoloConnectionStopped.CRC -> {
				val packet = HoloConnectionStopped()
				packet.decode(NetBuffer.wrap(raw))
				updateStatus(ServerConnectionStatus.DISCONNECTING, ServerConnectionChangedReason.OTHER_SIDE_TERMINATED)
				disconnect(packet.reason)
			}
		}
	}
	
	private fun createSocket(): Socket {
		if (encryptionEnabled) {
			val sslContext = SSLContext.getInstance("TLSv1.3")
			val tm = if (verifyServer) null else arrayOf<TrustManager>(TrustingTrustManager())
			sslContext.init(null, tm, SecureRandom())
			return sslContext.socketFactory.createSocket()
		} else {
			return Socket()
		}
	}
	
	private fun updateStatus(status: ServerConnectionStatus, reason: ServerConnectionChangedReason) {
		val old = this.status.getAndSet(status)
		if (old != status && callback != null)
			callback!!.onConnectionStatusChanged(old, status, reason)
	}
	
	private fun getReason(message: String?): ServerConnectionChangedReason {
		var messageLower = message ?: return ServerConnectionChangedReason.UNKNOWN
		messageLower = messageLower.toLowerCase(Locale.US)
		if (messageLower.contains("broken pipe"))
			return ServerConnectionChangedReason.BROKEN_PIPE
		if (messageLower.contains("connection reset"))
			return ServerConnectionChangedReason.CONNECTION_RESET
		if (messageLower.contains("connection refused"))
			return ServerConnectionChangedReason.CONNECTION_REFUSED
		if (messageLower.contains("address in use"))
			return ServerConnectionChangedReason.ADDR_IN_USE
		if (messageLower.contains("socket closed"))
			return ServerConnectionChangedReason.SOCKET_CLOSED
		return if (messageLower.contains("no route to host")) ServerConnectionChangedReason.NO_ROUTE_TO_HOST else ServerConnectionChangedReason.UNKNOWN
	}
	
	interface StatusChangedCallback {
		fun onConnectionStatusChanged(oldStatus: ServerConnectionStatus, newStatus: ServerConnectionStatus, reason: ServerConnectionChangedReason)
	}
	
	private class TrustingTrustManager : X509TrustManager {
		
		override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
		
		override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
		
		override fun getAcceptedIssuers(): Array<X509Certificate>? {
			return null
		}
	}
}
