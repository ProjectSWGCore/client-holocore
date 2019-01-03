package com.projectswg.holocore.client;

import com.projectswg.common.network.NetBuffer;

import java.nio.ByteBuffer;

class SWGProtocol {
	
	private final HolocoreProtocol holocore;
	
	public SWGProtocol() {
		holocore = new HolocoreProtocol();
	}
	
	public void reset() {
		holocore.reset();
	}
	
	public NetBuffer assemble(byte [] packet) {
		return holocore.assemble(packet);
	}
	
	public boolean addToBuffer(ByteBuffer data) {
		return holocore.addToBuffer(data);
	}
	
	public RawPacket disassemble() {
		byte [] packet = holocore.disassemble();
		if (packet.length < 6)
			return null;
		NetBuffer data = NetBuffer.wrap(packet);
		data.getShort();
		return new RawPacket(data.getInt(), packet);
	}
	
	public boolean hasPacket() {
		return holocore.hasPacket();
	}
	
}
