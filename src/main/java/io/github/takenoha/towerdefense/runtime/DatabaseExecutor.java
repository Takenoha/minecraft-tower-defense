package io.github.takenoha.towerdefense.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Serializes database work away from the Paper main thread. */
public final class DatabaseExecutor implements AutoCloseable {
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    private final ExecutorService executor;

    public DatabaseExecutor(String threadName) {
        Objects.requireNonNull(threadName, "threadName");
        executor = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name(threadName, 0).factory());
    }

    public <T> CompletableFuture<T> submit(Callable<T> work) {
        Objects.requireNonNull(work, "work");
        CompletableFuture<T> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                future.complete(work.call());
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        });
        return future;
    }

    public CompletableFuture<Void> execute(Runnable work) {
        Objects.requireNonNull(work, "work");
        return submit(() -> {
            work.run();
            return null;
        });
    }

    @Override
    public void close() {
        executor.shutdown();
        boolean interrupted = false;
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            interrupted = true;
            executor.shutdownNow();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

