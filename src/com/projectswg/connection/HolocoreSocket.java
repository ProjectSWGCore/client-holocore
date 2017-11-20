package com.projectswg.connection;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import com.projectswg.common.debug.Log;
import com.projectswg.common.network.NetBuffer;
import com.projectswg.common.network.TCPSocket;
import com.projectswg.common.network.TCPSocket.TCPSocketCallback;
import com.projectswg.common.network.packets.swg.holo.HoloConnectionStarted;
import com.projectswg.common.network.packets.swg.holo.HoloConnectionStopped;
import com.projectswg.common.network.packets.swg.holo.HoloSetProtocolVersion;
import com.projectswg.connection.UDPServer.UDPPacket;
import com.projectswg.connection.packets.RawPacket;

public class HolocoreSocket {
	
	private static final String PROTOCOL = "2016-04-13";
	private static final int BUFFER_SIZE = 128 * 1024;
	
	private final SWGProtocol swgProtocol;
	private final AtomicReference<ServerConnectionStatus> status;
	private final UDPServer udpServer;
	private final BlockingQueue<RawPacket> inboundQueue;
	
	private TCPSocket socket;
	private StatusChangedCallback callback;
	private InetSocketAddress address;
	
	public HolocoreSocket(InetAddress addr, int port) {
		this.swgProtocol = new SWGProtocol();
		this.status = new AtomicReference<>(ServerConnectionStatus.DISCONNECTED);
		this.udpServer = createUDPServer();
		this.socket = null;
		this.inboundQueue = new LinkedBlockingQueue<>();
		this.callback = null;
		this.address = new InetSocketAddress(addr, port);
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
		udpServer.send(address, new byte[]{1});
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
		TCPSocket socket = new TCPSocket(address, BUFFER_SIZE);
		return finishConnection(socket, timeout);
	}
	
	/**
	 * Attempts to connect to the remote server securely. This call is a blocking function that will not
	 * return until it has either successfully connected or has failed. It starts by initializing a
	 * TCP connection, then initializes the Holocore connection, then returns.
	 * @param timeout the timeout for the connect call
	 * @param keystoreFile the keystore file
	 * @param password the password for the keystore
	 * @return TRUE if successful and connected, FALSE on error
	 * @throws KeyStoreException if KeyManagerFactory.init or TrustManagerFactory.init fails
	 * @throws NoSuchAlgorithmException if the algorithm for the keystore or key manager could not be found
	 * @throws CertificateException if any of the certificates in the keystore could not be loaded
	 * @throws FileNotFoundException if the keystore file does not exist
	 * @throws IOException if there is an I/O or format problem with the keystore data, if a password is required but not given, or if the given password was incorrect. If the error is due to a wrong password, the cause of the IOException should be an UnrecoverableKeyException
	 * @throws KeyManagementException if SSLContext.init fails
	 * @throws UnrecoverableKeyException if the key cannot be recovered (e.g. the given password is wrong).
	 */
	public boolean connectSecure(int timeout, File keystoreFile, char [] password) throws KeyManagementException, UnrecoverableKeyException, KeyStoreException, NoSuchAlgorithmException, CertificateException, FileNotFoundException, IOException {
		TCPSocket socket = new TCPSocket(address, BUFFER_SIZE);
//		socket.setupEncryption(keystoreFile, password);
		return finishConnection(socket, timeout);
	}
	
	private boolean finishConnection(TCPSocket socket, int timeout) {
		updateStatus(ServerConnectionStatus.CONNECTING, ServerConnectionChangedReason.NONE);
		try {
			socket.createConnection();
			
			socket.getSocket().setKeepAlive(true);
			socket.getSocket().setPerformancePreferences(0, 1, 2);
			socket.getSocket().setTrafficClass(0x10); // Low Delay bit
			socket.getSocket().setSoLinger(true, 3);
			socket.startConnection();
			
			socket.setCallback(new TCPSocketCallback() {
				@Override
				public void onIncomingData(TCPSocket socket, byte[] data) {
					swgProtocol.addToBuffer(data);
					while (true) {
						RawPacket packet = swgProtocol.disassemble();
						if (packet != null)
							inboundQueue.offer(packet);
						else
							break;
					}
				}
				@Override
				public void onDisconnected(TCPSocket socket) { updateStatus(ServerConnectionStatus.DISCONNECTED, ServerConnectionChangedReason.UNKNOWN); }
				@Override
				public void onConnected(TCPSocket socket) { updateStatus(ServerConnectionStatus.CONNECTED, ServerConnectionChangedReason.NONE); }
			});
			this.socket = socket;
			waitForConnect(timeout);
			return true;
		} catch (IOException e) {
			Log.e(e);
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
	public boolean disconnect(ServerConnectionChangedReason reason) {
		TCPSocket socket = this.socket;
		if (socket != null)
			return socket.disconnect();
		return true;
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
			return socket.send(swgProtocol.assemble(raw));
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
	
	private void waitForConnect(int timeout) throws SocketException {
		send(new HoloSetProtocolVersion(PROTOCOL).encode().array());
		socket.getSocket().setSoTimeout(timeout);
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
			socket.getSocket().setSoTimeout(0);
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
	
	private static UDPServer createUDPServer() {
		try {
			return new UDPServer(0);
		} catch (SocketException e) {
			Log.e(e);
		}
		return null;
	}
	
}
