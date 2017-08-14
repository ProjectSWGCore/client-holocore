package com.projectswg.connection;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.projectswg.connection.packets.RawPacket;

class SWGProtocol {
	
	private final HolocoreProtocol holocore;
	
	public SWGProtocol() {
		holocore = new HolocoreProtocol();
	}
	
	public void reset() {
		holocore.reset();
	}
	
	public ByteBuffer assemble(byte [] packet) {
		return holocore.assemble(packet);
	}
	
	public boolean addToBuffer(ByteBuffer network) {
		return holocore.addToBuffer(network);
	}
	
	public RawPacket disassemble() {
		byte [] packet = holocore.disassemble();
		if (packet.length < 6)
			return null;
		ByteBuffer data = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
		return new RawPacket(data.getInt(2), packet);
	}
	
}
