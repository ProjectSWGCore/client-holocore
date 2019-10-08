package com.projectswg.holocore.client

import com.projectswg.common.network.NetBuffer
import com.projectswg.common.network.NetBufferStream

import java.nio.ByteBuffer

internal class HolocoreProtocol {
	
	private val inboundStream: NetBufferStream = NetBufferStream()
	
	fun reset() {
		inboundStream.reset()
	}
	
	fun assemble(raw: ByteArray): NetBuffer {
		val data = NetBuffer.allocate(raw.size + 4) // large array
		data.addArrayLarge(raw)
		data.flip()
		return data
	}
	
	fun addToBuffer(data: ByteBuffer): Boolean {
		synchronized(inboundStream) {
			inboundStream.write(data)
			return hasPacket()
		}
	}
	
	fun addToBuffer(data: ByteArray): Boolean {
		synchronized(inboundStream) {
			inboundStream.write(data)
			return hasPacket()
		}
	}
	
	fun addToBuffer(data: ByteArray, offset: Int, length: Int): Boolean {
		synchronized(inboundStream) {
			inboundStream.write(data, offset, length)
			return hasPacket()
		}
	}
	
	fun disassemble(): ByteArray {
		synchronized(inboundStream) {
			if (inboundStream.remaining() < 4) {
				return EMPTY_PACKET
			}
			inboundStream.mark()
			val messageLength = inboundStream.int
			if (inboundStream.remaining() < messageLength) {
				inboundStream.rewind()
				return EMPTY_PACKET
			}
			val data = inboundStream.getArray(messageLength)
			inboundStream.compact()
			return data
		}
	}
	
	fun hasPacket(): Boolean {
		synchronized(inboundStream) {
			if (inboundStream.remaining() < 4)
				return false
			inboundStream.mark()
			try {
				val messageLength = inboundStream.int
				return inboundStream.remaining() >= messageLength
			} finally {
				inboundStream.rewind()
			}
		}
	}
	
	companion object {
		
		const val VERSION = "2018-02-04"
		
		private val EMPTY_PACKET = ByteArray(0)
	}
	
}
