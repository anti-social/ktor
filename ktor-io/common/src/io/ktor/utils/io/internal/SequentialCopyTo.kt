package io.ktor.utils.io.internal

import io.ktor.utils.io.*
import io.ktor.utils.io.core.internal.*
import kotlinx.coroutines.*

internal suspend fun ByteChannelSequentialBase.joinToImpl(dst: ByteChannelSequentialBase, closeOnEnd: Boolean) {
    copyToSequentialImpl(dst, Long.MAX_VALUE)
    if (closeOnEnd) dst.close()
}

/**
 * Reads up to [limit] bytes from receiver channel and writes them to [dst] channel.
 * Closes [dst] channel if fails to read or write with cause exception.
 * @return a number of copied bytes
 */
internal suspend fun ByteChannelSequentialBase.copyToSequentialImpl(dst: ByteChannelSequentialBase, limit: Long): Long {
    require(this !== dst)

    val copyJob = Job()
    attachJob(copyJob)
    dst.attachJob(copyJob)

    if (closedCause != null) {
        dst.close(closedCause)
        return 0L
    }

    return try {
        var remainingLimit = limit

        while (remainingLimit > 0) {
            if (!awaitInternalAtLeast1()) {
                break
            }
            val transferred = transferTo(dst, remainingLimit)

            val copied = if (transferred == 0L) {
                val tail = copyToTail(dst, remainingLimit)
                if (tail == 0L) {
                    break
                }

                tail
            } else {
                if (dst.availableForWrite == 0) {
                    dst.awaitAtLeastNBytesAvailableForWrite(1)
                }

                transferred
            }

            remainingLimit -= copied
        }

        flush()
        limit - remainingLimit
    } catch (cause: Throwable) {
        if (closedCause == null) {
            close(cause)
        }

        if (dst.closedCause == null) {
            dst.cancel(cause)
        }
        throw cause
    }
}

private suspend fun ByteChannelSequentialBase.copyToTail(dst: ByteChannelSequentialBase, limit: Long): Long {
    val lastPiece = ChunkBuffer.Pool.borrow()
    try {
        lastPiece.resetForWrite(limit.coerceAtMost(lastPiece.capacity.toLong()).toInt())
        val rc = readAvailable(lastPiece)
        if (rc == -1) {
            lastPiece.release(ChunkBuffer.Pool)
            return 0
        }

        dst.writeFully(lastPiece)
        return rc.toLong()
    } finally {
        lastPiece.release(ChunkBuffer.Pool)
    }
}
