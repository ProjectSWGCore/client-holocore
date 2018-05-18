package com.projectswg.connection.packets;

public class RawPacket {
	
	private final int crc;
	private final byte[] data;
	
	public RawPacket(int crc, byte[] data) {
		this.crc = crc;
		this.data = data;
	}
	
	public int getCrc() {
		return crc;
	}
	
	public byte[] getData() {
		return data;
	}
	
}
