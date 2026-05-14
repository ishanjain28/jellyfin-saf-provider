package me.ishan.jellyfin_saf

import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousFileChannel
import java.nio.channels.CompletionHandler
import java.nio.channels.FileChannel
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


fun FileChannel.writeAt(
    data: ByteArray, fileOffset: Long, bufferOffset: Int = 0, length: Int = data.size - bufferOffset
): Int {
    val buffer = ByteBuffer.wrap(data, bufferOffset, length)
    var totalWritten = 0

    while (buffer.hasRemaining()) {
        val written: Int = write(buffer, fileOffset + totalWritten)
        totalWritten += written
    }

    return totalWritten
}

fun FileChannel.readAt(
    data: ByteArray, fileOffset: Long, bufferOffset: Int = 0, length: Int = data.size - bufferOffset
): Int {
    val buffer = ByteBuffer.wrap(data, bufferOffset, length)
    var totalRead = 0

    while (buffer.hasRemaining()) {
        val read = read(buffer, fileOffset + totalRead)
        if (read <= 0) break
        totalRead += read
    }

    return totalRead
}