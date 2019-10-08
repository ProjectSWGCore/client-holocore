package com.projectswg.holocore.client

import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class HolocoreInputStream(private val stream: InputStream): Closeable {
	
	private val lock = ReentrantLock(false)
	private val protocol = SWGProtocol()
	private val buffer = ByteArray(32*1024)
	
	fun read(): RawPacket {
		lock.withLock {
			if (protocol.hasPacket()) {
				val packet = protocol.disassemble()
				if (packet != null)
					return packet
			}
			do {
				val n = stream.read(buffer)
				if (n <= 0)
					throw EOFException("end of stream")
				if (protocol.addToBuffer(buffer, 0, n))
					return protocol.disassemble() ?: continue
			} while (true)
		}
		throw AssertionError("unreachable code")
	}
	
	override fun close() {
		stream.close()
	}
	
}
