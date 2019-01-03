package com.projectswg.holocore.client;

import com.projectswg.common.network.NetBuffer;
import com.projectswg.common.network.packets.swg.holo.HoloConnectionStarted;
import com.projectswg.common.network.packets.swg.holo.HoloConnectionStopped;
import com.projectswg.common.network.packets.swg.holo.HoloConnectionStopped.ConnectionStoppedReason;
import com.projectswg.common.network.packets.swg.holo.HoloSetProtocolVersion;
import me.joshlarson.jlcommon.concurrency.Delay;
import me.joshlarson.jlcommon.log.Log;
import me.joshlarson.jlcommon.network.SSLEngineWrapper.SSLClosedException;
import me.joshlarson.jlcommon.network.SecureTCPSocket;
import me.joshlarson.jlcommon.network.TCPSocket;
import me.joshlarson.jlcommon.network.TCPSocket.TCPSocketCallback;
import me.joshlarson.jlcommon.network.UDPServer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class HolocoreSocket implements AutoCloseable {
	
	private final SWGProtocol swgProtocol;
	private final AtomicReference<ServerConnectionStatus> status;
	private final UDPServer udpServer;
	private final BlockingQueue<DatagramPacket> udpInboundQueue;
	private final BlockingQueue<RawPacket> inboundQueue;
	private final boolean verifyServer;
	
	private SecureTCPSocket socket;
	private StatusChangedCallback callback;
	private InetSocketAddress address;
	
	public HolocoreSocket(InetAddress addr, int port) {
		this(addr, port, true);
	}
	
	public HolocoreSocket(InetAddress addr, int port, boolean verifyServer) {
		this.swgProtocol = new SWGProtocol();
		this.status = new AtomicReference<>(ServerConnectionStatus.DISCONNECTED);
		this.udpInboundQueue = new LinkedBlockingQueue<>();
		this.inboundQueue = new LinkedBlockingQueue<>();
		this.verifyServer = verifyServer;
		this.udpServer = createUDPServer();
		this.socket = null;
		this.callback = null;
		this.address = new InetSocketAddress(addr, port);
	}
	
	/**
	 * Shuts down any miscellaneous resources--such as the query UDP server
	 */
	public void close() {
		if (udpServer != null)
			udpServer.close();
		udpInboundQueue.clear();
		
		disconnect(ConnectionStoppedReason.APPLICATION);
		
		TCPSocket socket = this.socket;
		if (socket != null)
			socket.disconnect();
	}
	
	/**
	 * Shuts down any miscellaneous resources--such as the query UDP server
	 */
	@Deprecated
	public void terminate() {
		close();
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
		this.address = new InetSocketAddress(addr, port);
	}
	
	/**
	 * Returns the remote address this socket is pointing to
	 * @return the remote address as an InetSocketAddress
	 */
	public InetSocketAddress getRemoteAddress() {
		return address;
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
		Log.t("Requesting server status from %s", address);
		if (!udpServer.isRunning()) {
			try {
				udpServer.bind();
			} catch (SocketException e) {
				return "UNKNOWN";
			}
		}
		udpServer.send(address, new byte[]{1});
		try {
			DatagramPacket packet = udpInboundQueue.poll(timeout, TimeUnit.MILLISECONDS);
			if (packet == null)
				return "OFFLINE";
			NetBuffer data = NetBuffer.wrap(packet.getData());
			data.getByte();
			return data.getAscii();
		} catch (InterruptedException e) {
			Log.w("Interrupted while waiting for server status response");
			return "UNKNOWN";
		}
	}
	
	/**
	 * Attempts to connect to the remote server. This call is a blocking function that will not
	 * return until it has either successfully connected or has failed. It starts by initializing a
	 * TCP connection, then initializes the Holocore connection, then returns.
	 * @param timeout the timeout for the connect call
	 * @return TRUE if successful and connected, FALSE on error
	 */
	public boolean connect(int timeout) {
		try {
			SSLContext sslContext = SSLContext.getInstance("TLSv1.3");
			TrustManager [] tm = verifyServer ? null : new TrustManager[]{new TrustingTrustManager()};
			sslContext.init(null, tm, new SecureRandom());
			socket = new SecureTCPSocket(address, sslContext, Runnable::run);
		} catch (NoSuchAlgorithmException | KeyManagementException e) {
			throw new RuntimeException(e);
		}
		inboundQueue.clear();
		return finishConnection(socket, timeout);
	}
	
	private boolean finishConnection(SecureTCPSocket socket, int timeout) {
		updateStatus(ServerConnectionStatus.CONNECTING, ServerConnectionChangedReason.NONE);
		try {
			socket.createConnection();
			
			socket.setCallback(new TCPSocketCallback() {
				@Override
				public void onIncomingData(TCPSocket socket, ByteBuffer data) {
					swgProtocol.addToBuffer(data);
					while (true) {
						RawPacket packet = swgProtocol.disassemble();
						if (packet != null) {
							handlePacket(packet.getCrc(), packet.getData());
							inboundQueue.offer(packet);
						} else
							break;
					}
				}
				@Override
				public void onError(TCPSocket socket, Throwable t) {
					if (t instanceof ClosedChannelException || t instanceof SSLClosedException)
						return;
					Log.e(t);
				}
				@Override
				public void onDisconnected(TCPSocket socket) { updateStatus(ServerConnectionStatus.DISCONNECTED, ServerConnectionChangedReason.OTHER_SIDE_TERMINATED); }
				@Override
				public void onConnected(TCPSocket socket) { updateStatus(ServerConnectionStatus.CONNECTED, ServerConnectionChangedReason.NONE); }
			});
			this.socket = socket;
			waitForConnect(timeout);
			return true;
		} catch (IOException e) {
			updateStatus(ServerConnectionStatus.DISCONNECTED, getReason(e.getMessage()));
			socket.disconnect();
		}
		return false;
	}
	
	/**
	 * Attempts to disconnect from the server with the specified reason. Before this socket is
	 * closed, it will send a HoloConnectionStopped packet to notify the remote server.
	 * @param reason the reason for disconnecting
	 * @return TRUE if successfully disconnected, FALSE on error
	 */
	public boolean disconnect(ConnectionStoppedReason reason) {
		ServerConnectionStatus status = this.status.get();
		TCPSocket socket = this.socket;
		if (socket == null)
			return true;
		switch (status) {
			case CONNECTING:
			case DISCONNECTING:
			case DISCONNECTED:
			default:
				return socket.disconnect();
			case CONNECTED:
				updateStatus(ServerConnectionStatus.DISCONNECTING, ServerConnectionChangedReason.CLIENT_DISCONNECT);
				send(new HoloConnectionStopped(reason).encode().array());
				return true;
		}
	}
	
	/**
	 * Attempts to send a byte array to the remote server. This method blocks until it has
	 * completely sent or has failed.
	 * @param raw the byte array to send
	 * @return TRUE on success, FALSE on failure
	 */
	public boolean send(byte [] raw) {
		TCPSocket socket = this.socket;
		if (socket != null)
			return socket.send(swgProtocol.assemble(raw).getBuffer()) > 0;
		return false;
	}
	
	/**
	 * Attempts to receive a packet from the remote server. This method blocks until a packet is
	 * recieved or has failed.
	 * @return the RawPacket containing the CRC of the SWG message and the raw data array, or NULL
	 * on error
	 */
	public RawPacket receive() {
		try {
			return inboundQueue.take();
		} catch (InterruptedException e) {
			return null;
		}
	}
	
	/**
	 * Returns whether or not there is a packet ready to be received without blocking
	 * @return TRUE if there is a packet, FALSE otherwise
	 */
	public boolean hasPacket() {
		return !inboundQueue.isEmpty();
	}
	
	private void waitForConnect(int timeout) throws IOException {
		Socket rawSocket = socket.getSocket();
		rawSocket.setSoTimeout(timeout);
		try {
			socket.startConnection();
			send(new HoloSetProtocolVersion(HolocoreProtocol.VERSION).encode().array());
			while (isConnecting() && !Delay.isInterrupted()) {
				Delay.sleepMilli(50);
			}
		} finally {
			rawSocket.setSoTimeout(0); // Reset back to how it was before the function
		}
	}
	
	private void handlePacket(int crc, byte [] raw) {
		if (crc == HoloConnectionStarted.CRC) {
			updateStatus(ServerConnectionStatus.CONNECTED, ServerConnectionChangedReason.NONE);
		} else if (crc == HoloConnectionStopped.CRC) {
			HoloConnectionStopped packet = new HoloConnectionStopped();
			packet.decode(NetBuffer.wrap(raw));
			updateStatus(ServerConnectionStatus.DISCONNECTING, ServerConnectionChangedReason.OTHER_SIDE_TERMINATED);
			disconnect(packet.getReason());
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
	
	private UDPServer createUDPServer() {
		try {
			UDPServer server = new UDPServer(new InetSocketAddress(0), 1500, udpInboundQueue::add);
			server.bind();
			return server;
		} catch (SocketException e) {
			Log.e(e);
		}
		return null;
	}
	
	private static class TrustingTrustManager implements X509TrustManager {
		
		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType) { }
		
		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType) { }
		
		@Override
		public X509Certificate[] getAcceptedIssuers() { return null; }
	}
}
