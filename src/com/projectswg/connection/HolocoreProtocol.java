package com.projectswg.connection;

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
//		NetBuffer data = NetBuffer.allocate(raw.length + 4); // large array
//		data.addArrayLarge(raw);
		NetBuffer data = NetBuffer.allocate(raw.length + 5);
		data.addByte(0);
		data.addShort(raw.length);
		data.addShort(raw.length);
		data.addRawArray(raw);
		data.flip();
		return data;
	}
	
	public boolean addToBuffer(byte [] data) {
		synchronized (inboundStream) {
			inboundStream.write(data);
			return hasPacket();
		}
	}
	
	public byte [] disassemble() {
		synchronized (inboundStream) {
//			if (inboundStream.remaining() < 4) {
			if (inboundStream.remaining() < 5) {
				return EMPTY_PACKET;
			}
//			int messageLength = inboundStream.getInt();
			inboundStream.getByte();
			int messageLength = inboundStream.getShort();
			inboundStream.getShort();
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
			try {
//				if (inboundStream.remaining() < 4)
				if (inboundStream.remaining() < 5)
					return false;
				inboundStream.mark();
				inboundStream.getByte();
				int messageLength = inboundStream.getShort();
				inboundStream.getShort();
				return inboundStream.remaining() >= messageLength;
			} finally {
				inboundStream.rewind();
			}
		}
	}
	
}
