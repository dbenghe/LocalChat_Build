package localchat;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Runs a contiguous run of independent read-only tool calls of one model step in parallel.
 * Results are still consumed one by one in the model's original order, so the loop guard, the UI events
 * and the tool messages sent back to the model do not change. A run stops at the first call that is not
 * eligible (a mutation, a job start, a skill read), so a read never overtakes an earlier mutation.
 */
final class ToolBatch {
    static final int MAX_PARALLEL = 4;
    private static final ThreadPoolExecutor POOL = pool();

    private static ThreadPoolExecutor pool() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(MAX_PARALLEL, MAX_PARALLEL, 20, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), new ThreadFactory() {
                    private int count;
                    public synchronized Thread newThread(Runnable task) {
                        Thread thread = new Thread(task, "tool-batch-" + (++count));
                        thread.setDaemon(true);
                        return thread;
                    }
                });
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private final Map<ChatClient.ToolCall, Future<Tools.Result>> futures = new IdentityHashMap<>();
    private int prefetched;
    private static final java.util.concurrent.atomic.AtomicLong TOTAL = new java.util.concurrent.atomic.AtomicLong();

    /** Calls started ahead of their turn since start, over all batches (tests and diagnostics). */
    static long total() {
        return TOTAL.get();
    }

    /** Number of calls that were started ahead of their turn (diagnostics and tests). */
    int prefetched() {
        return prefetched;
    }

    boolean has(ChatClient.ToolCall call) {
        return futures.containsKey(call);
    }

    /**
     * Starts the eligible run that begins at {@code index} unless that call was already started.
     * A run of one call gains nothing and is left to the normal sequential path; a call repeated inside the
     * run (same name and arguments) ends the run, so the loop guard sees it exactly as before.
     */
    void prefetch(Predicate<ChatClient.ToolCall> eligible, Function<ChatClient.ToolCall, Tools.Result> execute,
                  List<ChatClient.ToolCall> calls, int index) {
        if (index < 0 || index >= calls.size() || futures.containsKey(calls.get(index))) return;
        List<ChatClient.ToolCall> run = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = index; i < calls.size() && run.size() < MAX_PARALLEL * 2; i++) {
            ChatClient.ToolCall call = calls.get(i);
            if (futures.containsKey(call) || !eligible.test(call)) break;
            if (!seen.add(call.name + "|" + Json.write(new java.util.TreeMap<>(call.args)))) break;
            run.add(call);
        }
        if (run.size() < 2) return;
        for (ChatClient.ToolCall call : run) {
            futures.put(call, POOL.submit(() -> execute.apply(call)));
            prefetched++;
            TOTAL.incrementAndGet();
        }
    }

    /** The result of a started call, waiting for it if needed; otherwise the call runs on the calling thread. */
    Tools.Result execute(Function<ChatClient.ToolCall, Tools.Result> execute, ChatClient.ToolCall call) {
        Future<Tools.Result> future = futures.remove(call);
        if (future == null) return execute.apply(call);
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return Tools.Result.error("Oprit de utilizator.");
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            return Tools.Result.error(String.valueOf(cause));
        } catch (CancellationException cancelled) {
            return Tools.Result.error("Oprit de utilizator.");
        }
    }

    /** Drops calls that were started but never consumed (Stop, guard break, recovery). */
    void discard() {
        for (Future<Tools.Result> future : futures.values()) future.cancel(true);
        futures.clear();
    }
}
