package com.projectswg.holocore.client

import java.io.Closeable
import java.io.OutputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class HolocoreOutputStream(private val stream: OutputStream): Closeable {
	
	private val lock = ReentrantLock(false)
	private val protocol = SWGProtocol()
	
	fun write(buffer: ByteArray) {
		lock.withLock {
			stream.write(protocol.assemble(buffer).array())
		}
	}
	
	override fun close() {
		stream.close()
	}
	
}
