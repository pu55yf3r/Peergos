package peergos.shared.user.fs;

import peergos.shared.util.*;

import java.util.concurrent.*;

public class BufferedAsyncReader implements AsyncReader {

    private final AsyncReader source;
    private final long fileSize;
    private final byte[] buffer;
    // bufferStartOffset <= readOffset <= bufferEndOffset at all times
    private long readOffsetInFile, bufferStartInFile, bufferEndInFile;
    private int startInBuffer; // index in buffer corresponding to bufferStartInFile
    private long lastReadEnd = -1;
    private volatile boolean closed = false;
    private final AsyncLock<Integer> lock = new AsyncLock<>(Futures.of(0));

    public BufferedAsyncReader(AsyncReader source, int nChunksToBuffer, long fileSize, long bufferStartInFile) {
        this.source = source;
        this.buffer = new byte[nChunksToBuffer * Chunk.MAX_SIZE];
        this.fileSize = fileSize;
        this.bufferStartInFile = bufferStartInFile;
        this.readOffsetInFile = bufferStartInFile;
        this.bufferEndInFile = bufferStartInFile;
        this.startInBuffer = 0;
    }

    public BufferedAsyncReader(AsyncReader source, int nChunksToBuffer, long fileSize) {
        this(source, nChunksToBuffer, fileSize, 0);
    }

    private void asyncBufferFill(int chunksToQueue) {
        if (chunksToQueue == 0 || buffered() == buffer.length)
            return;
        System.out.println("Async buffer fill " + chunksToQueue);
        ForkJoinPool.commonPool().execute(() ->
                lock.runWithLock(x -> bufferNextChunk())
                        .thenAccept(i -> asyncBufferFill(chunksToQueue - 1)));
    }

    private synchronized CompletableFuture<Integer> bufferNextChunk() {
        if (closed)
            return Futures.errored(new RuntimeException("Stream Closed!"));
        if (bufferEndInFile - bufferStartInFile >= buffer.length) {
            System.out.println("Buffer full!");
            return Futures.of(0);
        }

        long initialBufferEndOffset = bufferEndInFile;
        int writeFromBufferOffset = (buffered() + startInBuffer) % buffer.length;
        int toCopy = Math.min(buffer.length - writeFromBufferOffset, Chunk.MAX_SIZE);
        if (fileSize - bufferEndInFile < Chunk.MAX_SIZE)
            toCopy = (int) (fileSize - bufferEndInFile);
        if (toCopy == 0)
            return Futures.of(0);
        System.out.println("Buffering  " + toString() + " size " + toCopy);
        return source.readIntoArray(buffer, writeFromBufferOffset, toCopy)
                .thenApply(read -> {
                    synchronized (this) {
                        this.bufferEndInFile = initialBufferEndOffset + read;
                    }
                    return read;
                });
    }

    /**
     *
     * @return Number of buffered bytes
     */
    private synchronized int buffered() {
        return (int) (bufferEndInFile - bufferStartInFile);
    }

    /**
     *
     * @return Number of buffered bytes available to read
     */
    private synchronized int available() {
        return (int) (bufferEndInFile - readOffsetInFile);
    }

    /**
     *
     * @return Number of buffered bytes that have already been read
     */
    private synchronized int read() {
        return (int) (readOffsetInFile - bufferStartInFile);
    }

    @Override
    public CompletableFuture<Integer> readIntoArray(byte[] res, int offset, int length) {
        boolean twoConsecutiveReads;
        synchronized (this) {
            twoConsecutiveReads = lastReadEnd == readOffsetInFile;
            lastReadEnd = readOffsetInFile + length;
        }
        System.out.println("Read "+length+" from buffer " + toString());
        return internalReadIntoArray(res, offset, length).thenApply(r -> {
            // Only prefetch more chunks if we've done two consecutive reads, i.e. we're probably streaming
            int buffered = buffered();
            if (twoConsecutiveReads && buffered < buffer.length && buffered < fileSize && ! closed) {
                int nChunks = (buffer.length - available()) / Chunk.MAX_SIZE;
                asyncBufferFill(nChunks);
            }
            return r;
        });
    }

    private CompletableFuture<Integer> internalReadIntoArray(byte[] res, int offset, int length) {
        int available = available();
        if (available >= length) {
            synchronized (this) {
                // we already have all the data buffered
                int readStartInBuffer = (startInBuffer + read()) % buffer.length;
                int toCopy = Math.min(length, buffer.length - readStartInBuffer);
                System.arraycopy(buffer, readStartInBuffer, res, offset, toCopy);
                if (toCopy < length)
                    System.arraycopy(buffer, 0, res, offset + toCopy, length - toCopy);

                readOffsetInFile += length;
                while (read() >= Chunk.MAX_SIZE) {
                    bufferStartInFile += Chunk.MAX_SIZE;
                    startInBuffer += Chunk.MAX_SIZE;
                    startInBuffer %= buffer.length;
                }
                System.out.println("Finished read from buffer of " + length);
                return Futures.of(length);
            }
        }
        if (available > 0) {
            int toCopy;
            synchronized (this) {
                // drain the rest of the buffer
                int readStartInBuffer = (startInBuffer + read()) % buffer.length;
                toCopy = Math.min(available, buffer.length - readStartInBuffer);
                System.arraycopy(buffer, readStartInBuffer, res, offset, toCopy);
                if (toCopy < available)
                    System.arraycopy(buffer, 0, res, offset + toCopy, available - toCopy);

                readOffsetInFile += toCopy;
                while (read() >= Chunk.MAX_SIZE) {
                    bufferStartInFile += Chunk.MAX_SIZE;
                    startInBuffer += Chunk.MAX_SIZE;
                    startInBuffer %= buffer.length;
                }
                System.out.println("Partial read from buffer of " + toCopy);
            }
            return lock.runWithLock(x -> bufferNextChunk())
                    .thenCompose(x -> internalReadIntoArray(res, offset + toCopy, length - toCopy)
                            .thenApply(i -> length));
        }

        return lock.runWithLock(x -> {
            if (available() > 0) // A concurrent buffer load completed, no need to wait for another
                return Futures.of(0);
            System.out.println("Buffer empty, refilling...");
            return bufferNextChunk();
        }).thenCompose(x -> internalReadIntoArray(res, offset, length));
    }

    @Override
    public CompletableFuture<AsyncReader> seekJS(int high32, int low32) {
        long seek = ((long) (high32) << 32) | (low32 & 0xFFFFFFFFL);
        return seek(seek);
    }

    @Override
    public synchronized CompletableFuture<AsyncReader> seek(long offset) {
        System.out.println("BufferedReader.seek " + offset + " on " + toString());
        if (offset == readOffsetInFile)
            return Futures.of(this);

        close();
        long aligned = offset - offset % Chunk.MAX_SIZE;
        return source.seek(aligned)
                .thenCompose(r -> {
                    BufferedAsyncReader res = new BufferedAsyncReader(r, buffer.length / Chunk.MAX_SIZE, fileSize, aligned);
                    // do a dummy read into our buffer to get to correct position
                    return res.internalReadIntoArray(buffer, 0, (int) (offset - aligned))
                            .thenApply(n -> res);
                });
    }

    @Override
    public CompletableFuture<AsyncReader> reset() {
        System.out.println("BufferedReader.reset()");
        close();
        return source.reset()
                .thenApply(r -> new BufferedAsyncReader(r, buffer.length/Chunk.MAX_SIZE, fileSize));
    }

    @Override
    public void close() {
        System.out.println("BufferedReader.close()");
        this.closed = true;
    }

    @Override
    public String toString() {
        return "BufferedReader{" +
                "readOffsetInFile=" + readOffsetInFile +
                ", bufferStartInFile=" + bufferStartInFile +
                ", bufferEndInFile=" + bufferEndInFile +
                ", startInBuffer=" + startInBuffer +
                '}';
    }
}
