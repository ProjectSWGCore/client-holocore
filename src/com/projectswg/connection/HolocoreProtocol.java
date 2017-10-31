package com.projectswg.connection;

import java.nio.ByteBuffer;

import com.projectswg.common.network.NetBuffer;
import com.projectswg.common.network.NetBufferStream;

class HolocoreProtocol {
	
	private static final byte [] EMPTY_PACKET = new byte[0];
	
	private final NetBufferStream inboundStream;
	
	public HolocoreProtocol() {
		this.inboundStream = new NetBufferStream();
	}
	
	public void reset() {
		inboundStream.reset();
	}
	
	public NetBuffer assemble(byte [] raw) {
		NetBuffer data = NetBuffer.allocate(raw.length + 4); // large array
		data.addArrayLarge(raw);
		data.flip();
		return data;
	}
	
	public boolean addToBuffer(ByteBuffer data) {
		synchronized (inboundStream) {
			inboundStream.write(data);
			return hasPacket();
		}
	}
	
	public byte [] disassemble() {
		synchronized (inboundStream) {
			inboundStream.mark();
			if (inboundStream.remaining() < 4) {
				inboundStream.rewind();
				return EMPTY_PACKET;
			}
			int messageLength = inboundStream.getInt();
			if (inboundStream.remaining() < messageLength) {
				inboundStream.rewind();
				return EMPTY_PACKET;
			}
			byte [] data = inboundStream.getArray(messageLength);
			inboundStream.compact();
			return data;
		}
	}
	
	public boolean hasPacket() {
		synchronized (inboundStream) {
			inboundStream.mark();
			try {
				if (inboundStream.remaining() < 4)
					return false;
				int messageLength = inboundStream.getInt();
				return inboundStream.remaining() >= messageLength;
			} finally {
				inboundStream.rewind();
			}
		}
	}
	
}
