package io.kestra.plugin.datadog.deployments;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** A monotonic budget shared by requests and sleeps. In-flight requests are cancelled at the deadline. */
public final class Deadline implements AutoCloseable {
    private final long started = System.nanoTime();
    private final long budget;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public Deadline(Duration maxWait) {
        validate(maxWait, "maxWait");
        budget = maxWait.toNanos();
    }

    public static void validate(Duration duration, String name) {
        if (duration == null || duration.compareTo(Duration.ofMillis(1)) < 0 || duration.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("`" + name + "` must be between PT0.001S and P1D");
        }
    }

    private long remaining() {
        return Math.max(0, budget - (System.nanoTime() - started));
    }

    public boolean expired() {
        return remaining() == 0;
    }

    public <T> T call(Callable<T> action) throws Exception {
        long left = remaining();
        if (left == 0) {
            throw new TimeoutException("Datadog polling deadline reached");
        }
        var future = executor.submit(action);
        try {
            return future.get(left, TimeUnit.NANOSECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw new IllegalStateException("Datadog polling request failed", e.getCause());
        } finally {
            future.cancel(true);
        }
    }

    public void pause(Duration interval) throws InterruptedException {
        TimeUnit.NANOSECONDS.sleep(Math.min(remaining(), interval.toNanos()));
    }

    @Override
    public void close() {
        // Do not wait beyond the deadline for an HTTP transport that ignores interruption.
        executor.shutdownNow();
    }
}
