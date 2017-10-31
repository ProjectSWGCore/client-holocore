package com.projectswg.connection;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.SocketChannel;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import com.projectswg.common.network.NetBuffer;
import com.projectswg.common.network.packets.swg.holo.HoloConnectionStarted;
import com.projectswg.common.network.packets.swg.holo.HoloConnectionStopped;
import com.projectswg.common.network.packets.swg.holo.HoloConnectionStopped.ConnectionStoppedReason;
import com.projectswg.common.network.packets.swg.holo.HoloSetProtocolVersion;
import com.projectswg.connection.UDPServer.UDPPacket;
import com.projectswg.connection.packets.RawPacket;

public class HolocoreSocket {
	
	private static final String PROTOCOL = "2016-04-13";
	
	private final Object socketMutex;
	private final ByteBuffer buffer;
	private final SWGProtocol swgProtocol;
	private final AtomicReference<ServerConnectionStatus> status;
	private final UDPServer udpServer;
	private SocketChannel socket;
	private StatusChangedCallback callback;
	private InetAddress addr;
	private int port;
	
	public HolocoreSocket(InetAddress addr, int port) {
		this.socketMutex = new Object();
		this.buffer = ByteBuffer.allocateDirect(128*1024);
		this.swgProtocol = new SWGProtocol();
		this.socket = null;
		this.status = new AtomicReference<>(ServerConnectionStatus.DISCONNECTED);
		UDPServer udpServer = null;
		try {
			udpServer = new UDPServer(0);
		} catch (SocketException e) {
			e.printStackTrace();
		}
		this.udpServer = udpServer;
		this.callback = null;
		this.addr = addr;
		this.port = port;
	}
	
	/**
	 * Shuts down any miscellaneous resources--such as the query UDP server
	 */
	public void terminate() {
		if (udpServer != null)
			udpServer.close();
	}
	
	/**
	 * Sets a callback for when the status of the server socket changes
	 * @param callback the callback
	 */
	public void setStatusChangedCallback(StatusChangedCallback callback) {
		this.callback = callback;
	}
	
	/**
	 * Sets the remote address this socket will attempt to connect to
	 * @param addr the destination address
	 * @param port the destination port
	 */
	public void setRemoteAddress(InetAddress addr, int port) {
		this.addr = addr;
		this.port = port;
	}
	
	/**
	 * Returns the remote address this socket is pointing to
	 * @return the remote address as an InetSocketAddress
	 */
	public InetSocketAddress getRemoteAddress() {
		return new InetSocketAddress(addr, port);
	}
	
	/**
	 * Gets the current connection state of the socket
	 * @return the connection state
	 */
	public ServerConnectionStatus getConnectionState() {
		return status.get();
	}
	
	/**
	 * Returns whether or not this socket is disconnected
	 * @return TRUE if disconnected, FALSE otherwise
	 */
	public boolean isDisconnected() {
		return status.get() == ServerConnectionStatus.DISCONNECTED;
	}
	
	/**
	 * Returns whether or not this socket is connecting
	 * @return TRUE if connecting, FALSE otherwise
	 */
	public boolean isConnecting() {
		return status.get() == ServerConnectionStatus.CONNECTING;
	}
	
	/**
	 * Returns whether or not this socket is connected
	 * @return TRUE if connected, FALSE otherwise
	 */
	public boolean isConnected() {
		return status.get() == ServerConnectionStatus.CONNECTED;
	}
	
	/**
	 * Retrieves the server status via a UDP query, with the default timeout of 2000ms
	 * @return the server status as a string
	 */
	public String getServerStatus() {
		return getServerStatus(2000);
	}
	
	/**
	 * Retrives the server status via a UDP query, with the specified timeout
	 * @param timeout the timeout in milliseconds
	 * @return the server status as a string
	 */
	public String getServerStatus(long timeout) {
		udpServer.send(port, addr, new byte[]{1});
		udpServer.waitForPacket(timeout);
		UDPPacket packet = udpServer.receive();
		if (packet == null)
			return "OFFLINE";
		NetBuffer data = NetBuffer.wrap(packet.getData());
		data.getByte();
		return data.getAscii();
	}
	
	/**
	 * Attempts to connect to the remote server. This call is a blocking function that will not
	 * return until it has either successfully connected or has failed. It starts by initializing a
	 * TCP connection, then initializes the Holocore connection, then returns.
	 * @param timeout the timeout for the connect call
	 * @return TRUE if successful and connected, FALSE on error
	 */
	public boolean connect(int timeout) {
		synchronized (socketMutex) {
			if (!isDisconnected())
				throw new IllegalStateException("Socket must be disconnected when attempting to connect!");
			try {
				swgProtocol.reset();
				socket = SocketChannel.open();
				updateStatus(ServerConnectionStatus.CONNECTING, ServerConnectionChangedReason.NONE);
				socket.socket().setKeepAlive(true);
				socket.socket().setPerformancePreferences(0, 1, 2);
				socket.socket().setTrafficClass(0x10); // Low Delay bit
				socket.setOption(StandardSocketOptions.SO_LINGER, 1);
				socket.configureBlocking(true);
				socket.connect(new InetSocketAddress(addr, port));
				if (!socket.finishConnect())
					return false;
				waitForConnect(timeout);
				return true;
			} catch (IOException e) {
				if (e instanceof AsynchronousCloseException) {
					disconnect(ServerConnectionChangedReason.SOCKET_CLOSED);
				} else if (e instanceof SocketTimeoutException) {
					disconnect(ServerConnectionChangedReason.CONNECT_TIMEOUT);
				} else if (e.getMessage() == null) {
					disconnect(ServerConnectionChangedReason.UNKNOWN);
				} else {
					disconnect(getReason(e.getMessage()));
				}
				return false;
			}
		}
	}
	
	/**
	 * Attempts to disconnect from the server with the specified reason. Before this socket is
	 * closed, it will send a HoloConnectionStopped packet to notify the remote server.
	 * @param reason the reason for disconnecting
	 * @return TRUE if successfully disconnected, FALSE on error
	 */
	public boolean disconnect(ServerConnectionChangedReason reason) {
		synchronized (socketMutex) {
			if (isDisconnected())
				return true;
			if (socket == null)
				throw new NullPointerException("Socket cannot be null when disconnecting");
			updateStatus(ServerConnectionStatus.DISCONNECTED, reason);
			try {
				if (socket.isOpen()) {
					socket.write(swgProtocol.assemble(new HoloConnectionStopped(ConnectionStoppedReason.APPLICATION).encode().array()).getBuffer());
				}
				socket.close();
				socket = null;
				return true;
			} catch (IOException e) {
				return false;
			}
		}
	}
	
	/**
	 * Attempts to send a byte array to the remote server. This method blocks until it has
	 * completely sent or has failed.
	 * @param raw the byte array to send
	 * @return TRUE on success, FALSE on failure
	 */
	public boolean send(byte [] raw) {
		return sendRaw(swgProtocol.assemble(raw).getBuffer());
	}
	
	/**
	 * Attempts to receive a packet from the remote server. This method blocks until a packet is
	 * recieved or has failed.
	 * @return the RawPacket containing the CRC of the SWG message and the raw data array, or NULL
	 * on error
	 */
	public RawPacket receive() {
		RawPacket packet = null;
		do {
			packet = swgProtocol.disassemble();
			if (packet != null)
				return packet;
			readRaw(buffer, -1);
			swgProtocol.addToBuffer(buffer);
		} while (!isDisconnected());
		return null;
	}
	
	/**
	 * Returns whether or not there is a packet ready to be received without blocking
	 * @return TRUE if there is a packet, FALSE otherwise
	 */
	public boolean hasPacket() {
		readRaw(buffer, 1);
		return swgProtocol.hasPacket();
	}
	
	private boolean sendRaw(ByteBuffer data) {
		if (isDisconnected())
			return false;
		try {
			synchronized (socketMutex) {
				while (data.hasRemaining())
					socket.write(data);
			}
			return !data.hasRemaining();
		} catch (IOException e) {
			disconnect(ServerConnectionChangedReason.OTHER_SIDE_TERMINATED);
		}
		return false;
	}
	
	/**
	 * Reads data from the socket, returning true if there is data to read
	 * @param data the buffer to read into
	 * @param timeout the optional timeout for this operation, -1 for default
	 * @return TRUE if data has been read, FALSE otherwise
	 */
	private boolean readRaw(ByteBuffer data, int timeout) {
		try {
			data.position(0);
			data.limit(data.capacity());
			
			int returnTimeout = socket.socket().getSoTimeout();
			if (timeout != returnTimeout && timeout != -1)
				socket.socket().setSoTimeout(timeout);
			int n = socket.read(data);
			if (timeout != returnTimeout && timeout != -1)
				socket.socket().setSoTimeout(returnTimeout);
			
			if (n < 0) {
				disconnect(ServerConnectionChangedReason.OTHER_SIDE_TERMINATED);
			} else {
				data.flip();
				return true;
			}
		} catch (Exception e) {
			if (e instanceof AsynchronousCloseException) {
				disconnect(ServerConnectionChangedReason.SOCKET_CLOSED);
			} else if (e.getMessage() != null) {
				disconnect(getReason(e.getMessage()));
			} else {
				disconnect(ServerConnectionChangedReason.UNKNOWN);
			}
		}
		return false;
	}
	
	private void waitForConnect(int timeout) throws SocketException {
		send(new HoloSetProtocolVersion(PROTOCOL).encode().array());
		socket.socket().setSoTimeout(timeout);
		try {
			while (isConnecting()) {
				RawPacket packet = receive();
				if (packet == null)
					continue;
				handlePacket(packet.getCrc(), packet.getData());
			}
			if (isConnected())
				send(new HoloConnectionStarted().encode().array());
		} finally {
			socket.socket().setSoTimeout(0);
		}
	}
	
	private void handlePacket(int crc, byte [] raw) {
		if (crc == HoloConnectionStarted.CRC) {
			updateStatus(ServerConnectionStatus.CONNECTED, ServerConnectionChangedReason.NONE);
		} else if (crc == HoloConnectionStopped.CRC) {
			HoloConnectionStopped packet = new HoloConnectionStopped();
			packet.decode(NetBuffer.wrap(raw));
			switch (packet.getReason()) {
				case INVALID_PROTOCOL:
					disconnect(ServerConnectionChangedReason.INVALID_PROTOCOL);
					break;
				default:
					disconnect(ServerConnectionChangedReason.NONE);
					break;
			}
		}
	}
	
	private void updateStatus(ServerConnectionStatus status, ServerConnectionChangedReason reason) {
		ServerConnectionStatus old = this.status.getAndSet(status);
		if (old != status && callback != null)
			callback.onConnectionStatusChanged(old, status, reason);
	}
	
	private ServerConnectionChangedReason getReason(String message) {
		message = message.toLowerCase(Locale.US);
		if (message.contains("broken pipe"))
			return ServerConnectionChangedReason.BROKEN_PIPE;
		if (message.contains("connection reset"))
			return ServerConnectionChangedReason.CONNECTION_RESET;
		if (message.contains("connection refused"))
			return ServerConnectionChangedReason.CONNECTION_REFUSED;
		if (message.contains("address in use"))
			return ServerConnectionChangedReason.ADDR_IN_USE;
		if (message.contains("socket closed"))
			return ServerConnectionChangedReason.SOCKET_CLOSED;
		if (message.contains("no route to host"))
			return ServerConnectionChangedReason.NO_ROUTE_TO_HOST;
		return ServerConnectionChangedReason.UNKNOWN;
	}
	
	public interface StatusChangedCallback {
		void onConnectionStatusChanged(ServerConnectionStatus oldStatus, ServerConnectionStatus newStatus, ServerConnectionChangedReason reason);
	}
	
}
