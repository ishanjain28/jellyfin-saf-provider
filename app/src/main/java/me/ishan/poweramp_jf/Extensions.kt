package me.ishan.poweramp_jf

import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousFileChannel
import java.nio.channels.CompletionHandler
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun AsynchronousFileChannel.readSuspend(
    buffer: ByteBuffer, position: Long
): Int = suspendCancellableCoroutine { continuation ->
    read(buffer, position, continuation, object : CompletionHandler<Int, Any> {
        override fun completed(result: Int, attachment: Any) {
            continuation.resume(result)
        }

        override fun failed(exc: Throwable, attachment: Any) {
            continuation.resumeWithException(exc)
        }
    })
}

suspend fun AsynchronousFileChannel.writeSuspend(
    buffer: ByteBuffer, position: Long
): Int = suspendCancellableCoroutine { continuation ->
    write(buffer, position, continuation, object : CompletionHandler<Int, Any> {
        override fun completed(result: Int, attachment: Any) {
            continuation.resume(result)
        }

        override fun failed(exc: Throwable, attachment: Any) {
            continuation.resumeWithException(exc)
        }
    })
}


suspend fun AsynchronousFileChannel.writeAt(
    data: ByteArray, fileOffset: Long, bufferOffset: Int = 0, length: Int = data.size - bufferOffset
): Int {
    val buffer = ByteBuffer.wrap(data, bufferOffset, length)
    var totalWritten = 0
    var currentOffset = fileOffset

    while (buffer.hasRemaining()) {
        val written: Int = writeSuspend(buffer, currentOffset)
        totalWritten += written
        currentOffset += written
    }

    return totalWritten
}

suspend fun AsynchronousFileChannel.readAt(
    data: ByteArray, fileOffset: Long, bufferOffset: Int = 0, length: Int = data.size - bufferOffset
): Int {
    val buffer = ByteBuffer.wrap(data, bufferOffset, length)
    var totalRead = 0
    var currentOffset = fileOffset

    while (buffer.hasRemaining()) {
        val read = readSuspend(buffer, currentOffset)
        if (read <= 0) break
        totalRead += read
        currentOffset += read
    }

    return totalRead
}