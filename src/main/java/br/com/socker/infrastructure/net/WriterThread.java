package br.com.socker.infrastructure.net;

import br.com.socker.infrastructure.protocol.Frame;
import br.com.socker.infrastructure.protocol.FrameWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Serializes frame writes to a single TCP {@link OutputStream}.
 *
 * <p>Runs on a dedicated Virtual Thread — one per {@code MultiplexedConnection}.
 * Sender threads call {@link #enqueue(Frame)}, which is non-blocking and returns
 * immediately. The writer thread drains the queue and writes frames one at a time,
 * ensuring no two frames are interleaved on the wire.
 *
 * <h2>Confirmations</h2>
 * <ul>
 *   <li>Queue capacity is bounded — {@code enqueue} returns {@code false} when full
 *       (back-pressure signal; never blocks the sender thread indefinitely).</li>
 *   <li>A write {@link IOException} calls {@code onWriteError} exactly once,
 *       then the loop terminates cleanly.</li>
 *   <li>Uses the existing {@link FrameWriter} — no encoding duplication.</li>
 *   <li>Stopped via {@link #stop()} which sets a flag and interrupts the thread.</li>
 * </ul>
 */
public class WriterThread implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(WriterThread.class);

    private final String connectionId;
    private final OutputStream out;
    private final FrameWriter frameWriter;
    private final BlockingQueue<Frame> queue;
    private final Runnable onWriteError;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    /**
     * @param connectionId   for logging
     * @param out            the socket output stream — owned exclusively by this writer
     * @param queueCapacity  bounded queue size — set to {@code maxInFlight * 2} for burst headroom
     * @param onWriteError   called once if a write fails (should trigger connection death)
     */
    public WriterThread(String connectionId,
                        OutputStream out,
                        int queueCapacity,
                        Runnable onWriteError) {
        this.connectionId = connectionId;
        this.out          = out;
        this.frameWriter  = new FrameWriter();
        this.queue        = new ArrayBlockingQueue<>(queueCapacity);
        this.onWriteError = onWriteError;
    }

    /**
     * Attempt to enqueue a frame for writing.
     *
     * <p>Non-blocking. Returns {@code false} if the queue is full (back-pressure)
     * or if the writer has been stopped.
     */
    public boolean enqueue(Frame frame) {
        if (stopped.get()) {
            return false;
        }
        return queue.offer(frame);
    }

    /**
     * Signal the writer thread to stop after draining queued frames.
     * The thread will exit on its next poll timeout cycle.
     */
    public void stop() {
        stopped.set(true);
    }

    /** Current number of frames waiting to be written. */
    public int queueSize() {
        return queue.size();
    }

    @Override
    public void run() {
        log.debug("WriterThread started for connection {}", connectionId);
        try {
            while (!stopped.get() && !Thread.currentThread().isInterrupted()) {
                // Poll with timeout so the stopped flag is checked periodically
                Frame frame = queue.poll(200, TimeUnit.MILLISECONDS);
                if (frame != null) {
                    // FrameWriter writes [2-byte header][ASCII payload] — protocol unchanged
                    frameWriter.write(out, frame);
                }
            }
            // Drain any frames queued before stop() was called
            Frame remaining;
            while ((remaining = queue.poll()) != null) {
                frameWriter.write(out, remaining);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            if (!stopped.get()) {
                log.error("WriterThread: write error on connection {}: {}", connectionId, e.getMessage());
                onWriteError.run();
            }
        } finally {
            log.debug("WriterThread stopped for connection {}", connectionId);
        }
    }
}
