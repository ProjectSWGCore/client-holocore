package com.projectswg.connection;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.projectswg.connection.common.Compression;
import com.projectswg.connection.common.NetBufferStream;

class HolocoreProtocol {
	
	private static final byte [] EMPTY_PACKET = new byte[0];
	
	private final NetBufferStream inboundStream;
	
	public HolocoreProtocol() {
		this.inboundStream = new NetBufferStream();
	}
	
	public void reset() {
		inboundStream.reset();
	}
	
	public ByteBuffer assemble(byte [] raw) {
		int decompressedLength = raw.length;
		boolean compressed = raw.length >= 16;
		if (compressed) {
			byte [] compressedData = Compression.compress(raw);
			if (compressedData.length >= raw.length)
				compressed = false;
			else
				raw = compressedData;
		}
		ByteBuffer data = ByteBuffer.allocate(raw.length + 5).order(ByteOrder.LITTLE_ENDIAN);
		data.put(createBitmask(compressed, true));
		data.putShort((short) raw.length);
		data.putShort((short) decompressedLength);
		data.put(raw);
		data.flip();
		return data;
	}
	
	public boolean addToBuffer(ByteBuffer data) {
		synchronized (inboundStream) {
			inboundStream.write(data);
			inboundStream.mark();
			try {
				if (inboundStream.remaining() < 5)
					return false;
				inboundStream.getByte();
				short messageLength = inboundStream.getShort();
				inboundStream.getShort();
				if (inboundStream.remaining() < messageLength) {
					inboundStream.rewind();
					return false;
				}
				return true;
			} finally {
				inboundStream.rewind();
			}
		}
	}
	
	public byte [] disassemble() {
		synchronized (inboundStream) {
			inboundStream.mark();
			if (inboundStream.remaining() < 5) {
				inboundStream.rewind();
				return EMPTY_PACKET;
			}	
			byte bitmask = inboundStream.getByte();
			short messageLength = inboundStream.getShort();
			short decompressedLength = inboundStream.getShort();
			if (inboundStream.remaining() < messageLength) {
				inboundStream.rewind();
				return EMPTY_PACKET;
			}
			byte [] message = inboundStream.getArray(messageLength);
			if ((bitmask & 1) != 0) // Compressed
				message = Compression.decompress(message, decompressedLength);
			inboundStream.compact();
			return message;
		}
	}
	
	private byte createBitmask(boolean compressed, boolean swg) {
		byte bitfield = 0;
		bitfield |= (compressed?1:0) << 0;
		bitfield |= (swg?1:0) << 1;
		return bitfield;
	}
	
}
