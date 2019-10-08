package com.projectswg.holocore.client

import com.projectswg.common.network.NetBuffer

import java.nio.ByteBuffer

internal class SWGProtocol {
	
	private val holocore: HolocoreProtocol = HolocoreProtocol()
	
	fun reset() {
		holocore.reset()
	}
	
	fun assemble(packet: ByteArray): NetBuffer {
		return holocore.assemble(packet)
	}
	
	fun addToBuffer(data: ByteArray): Boolean {
		return holocore.addToBuffer(data)
	}
	
	fun addToBuffer(data: ByteArray, offset: Int, length: Int): Boolean {
		return holocore.addToBuffer(data, offset, length)
	}
	
	fun addToBuffer(data: ByteBuffer): Boolean {
		return holocore.addToBuffer(data)
	}
	
	fun disassemble(): RawPacket? {
		val packet = holocore.disassemble()
		if (packet.size < 6)
			return null
		val data = NetBuffer.wrap(packet)
		data.position(2)
		return RawPacket(data.int, packet)
	}
	
	fun hasPacket(): Boolean {
		return holocore.hasPacket()
	}
	
}
