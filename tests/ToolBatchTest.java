package localchat;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Parallel read-only tool calls: real concurrency, original result order, no read past a mutation, one permission card at a time. */
public final class ToolBatchTest {
    static int checks;
    static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    static ChatClient.ToolCall call(String id, String name, String key) { return new ChatClient.ToolCall(id, name, Json.obj("k", key)); }
    static Tools.Result ok(String text) { return Tools.Result.ok(text); }

    public static void main(String[] args) throws Exception {
        unit();
        integration();
        System.out.println("PASS: " + checks + " parallel tool call checks");
        System.exit(0);
    }

    static void unit() throws Exception {
        // Three reads wait for each other: this only finishes when they really run at the same time.
        CountDownLatch together = new CountDownLatch(3);
        AtomicInteger running = new AtomicInteger(), peak = new AtomicInteger();
        var reads = List.of(call("a", "read_x", "1"), call("b", "read_x", "2"), call("c", "read_x", "3"));
        java.util.function.Function<ChatClient.ToolCall, Tools.Result> wait = c -> {
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            together.countDown();
            try { if (!together.await(5, TimeUnit.SECONDS)) return Tools.Result.error("serialized " + c.id); } catch (InterruptedException e) { return Tools.Result.error("interrupted"); }
            running.decrementAndGet();
            return ok("result-" + c.id);
        };
        ToolBatch batch = new ToolBatch();
        batch.prefetch(c -> c.name.startsWith("read_"), wait, reads, 0);
        check(batch.prefetched() == 3 && reads.stream().allMatch(batch::has), "all three reads started together");
        StringBuilder order = new StringBuilder();
        for (var c : reads) { var r = batch.execute(wait, c); check(!r.isError, "parallel result for " + c.id); order.append(r.text).append(';'); }
        check(order.toString().equals("result-a;result-b;result-c;"), "results are consumed in the model's order");
        check(peak.get() == 3, "three calls were in flight at once");
        batch.discard();

        // A slow first call does not reorder the results.
        var slowFirst = List.of(call("s1", "read_x", "1"), call("s2", "read_x", "2"));
        ToolBatch ordered = new ToolBatch();
        java.util.function.Function<ChatClient.ToolCall, Tools.Result> uneven = c -> { try { Thread.sleep(c.id.equals("s1") ? 200 : 0); } catch (InterruptedException e) { } return ok(c.id); };
        ordered.prefetch(c -> true, uneven, slowFirst, 0);
        check(ordered.execute(uneven, slowFirst.get(0)).text.equals("s1") && ordered.execute(uneven, slowFirst.get(1)).text.equals("s2"), "slow first call still answers first");

        // A mutation ends the run; reads after it start only once it has executed.
        var mixed = List.of(call("r1", "read_x", "1"), call("r2", "read_x", "2"), call("w1", "write_x", "3"), call("r3", "read_x", "4"), call("r4", "read_x", "5"));
        List<String> started = Collections.synchronizedList(new ArrayList<>());
        java.util.function.Function<ChatClient.ToolCall, Tools.Result> record = c -> { started.add(c.id); return ok(c.id); };
        ToolBatch guarded = new ToolBatch();
        java.util.function.Predicate<ChatClient.ToolCall> reading = c -> c.name.startsWith("read_");
        guarded.prefetch(reading, record, mixed, 0);
        check(guarded.prefetched() == 2 && !guarded.has(mixed.get(2)) && !guarded.has(mixed.get(3)), "the run stops before the mutation");
        guarded.prefetch(reading, record, mixed, 1);
        check(guarded.prefetched() == 2, "an already started call is not started twice");
        guarded.execute(record, mixed.get(0)); guarded.execute(record, mixed.get(1));
        Thread.sleep(50); check(!started.contains("r3") && !started.contains("r4"), "reads behind a mutation have not started yet");
        guarded.prefetch(reading, record, mixed, 2); check(guarded.prefetched() == 2, "a mutation is never prefetched");
        guarded.execute(record, mixed.get(2)); check(started.contains("w1"), "the mutation runs on its own turn");
        guarded.prefetch(reading, record, mixed, 3); check(guarded.prefetched() == 4, "reads after the mutation start after it");
        guarded.discard();

        // One call alone gains nothing; an identical repeated call ends the run so the loop guard sees it as before.
        ToolBatch single = new ToolBatch(); single.prefetch(reading, record, List.of(call("o", "read_x", "1"), call("w", "write_x", "1")), 0);
        check(single.prefetched() == 0, "a single read stays on the normal path");
        ToolBatch twins = new ToolBatch(); var same = List.of(call("t1", "read_x", "same"), call("t2", "read_x", "other"), call("t3", "read_x", "same"));
        twins.prefetch(reading, record, same, 0); check(twins.prefetched() == 2 && !twins.has(same.get(2)), "a repeated identical call is left for the loop guard");
        twins.discard();

        // Stop: unconsumed calls are cancelled, an exception behaves like the sequential call.
        CountDownLatch release = new CountDownLatch(1); CountDownLatch entered = new CountDownLatch(2); AtomicInteger interrupted = new AtomicInteger();
        java.util.function.Function<ChatClient.ToolCall, Tools.Result> blocking = c -> { entered.countDown(); try { release.await(); } catch (InterruptedException e) { interrupted.incrementAndGet(); } return ok("late"); };
        ToolBatch stopped = new ToolBatch(); var pair = List.of(call("k1", "read_x", "1"), call("k2", "read_x", "2"));
        stopped.prefetch(reading, blocking, pair, 0); check(entered.await(5, TimeUnit.SECONDS), "blocked reads started");
        stopped.discard(); for (int n = 0; n < 100 && interrupted.get() < 2; n++) Thread.sleep(20);
        check(interrupted.get() == 2 && !stopped.has(pair.get(0)), "discard interrupts calls nobody will consume");
        ToolBatch failing = new ToolBatch(); var boom = List.of(call("f1", "read_x", "1"), call("f2", "read_x", "2"));
        java.util.function.Function<ChatClient.ToolCall, Tools.Result> thrower = c -> { throw new IllegalStateException("tool bug " + c.id); };
        failing.prefetch(reading, thrower, boom, 0);
        try { failing.execute(thrower, boom.get(0)); check(false, "exception expected"); } catch (IllegalStateException expected) { check(expected.getMessage().equals("tool bug f1"), "exceptions surface at the call's turn"); }
        failing.discard();
    }

    static Agent.Ui ui(List<String> events) {
        return new Agent.Ui() {
            public void text(String s) { }
            public void step() { }
            public void toolStarted(ChatClient.ToolCall c) { events.add("start:" + c.id); }
            public void toolFinished(ChatClient.ToolCall c, Tools.Result r) { events.add("done:" + c.id); }
        };
    }

    static void integration() throws Exception {
        Path root = Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("localchat.home"))), "batch-");
        Path outside = Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("localchat.home"))), "outside-");
        for (String n : List.of("a", "b", "c")) Files.writeString(root.resolve(n + ".txt"), "content of " + n);
        Files.writeString(outside.resolve("x.txt"), "outside x");
        Files.writeString(outside.resolve("y.txt"), "outside y");
        Files.writeString(outside.resolve("z.txt"), "outside z");
        AtomicInteger asks = new AtomicInteger(), asking = new AtomicInteger(), overlap = new AtomicInteger();
        Tools.Context context = new Tools.Context(root, root.resolve("skills"), root.resolve("rules.md"), root.resolve("settings.properties"), Map.of(), null, new Mcp(), PathMap.none(), false, EnumSet.noneOf(Tools.Access.class), (title, details) -> {
            asks.incrementAndGet();
            if (asking.incrementAndGet() > 1) overlap.incrementAndGet();
            try { Thread.sleep(80); } catch (InterruptedException e) { }
            asking.decrementAndGet();
            return Tools.Decision.ALWAYS;
        });
        Tools tools = new Tools(context, List.of());
        check(tools.parallelSafe("read_file") && tools.parallelSafe("list_dir") && tools.parallelSafe("find_files") && tools.parallelSafe("search_text"), "builtin readers are parallel-safe");
        check(!tools.parallelSafe("write_file") && !tools.parallelSafe("edit_file") && !tools.parallelSafe("run_command") && !tools.parallelSafe("read_skill") && !tools.parallelSafe("run_agents"), "mutations, commands, skills and agents are not");

        // Real tools through the agent: result messages keep the model's order; a read after a write sees the write.
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        long startedBefore = ToolBatch.total();
        try (var server = new FeatureTest.Server(
                InteractionRecoveryTest.reply("", InteractionRecoveryTest.call("c1", "read_file", Json.obj("path", "a.txt")), InteractionRecoveryTest.call("c2", "read_file", Json.obj("path", "b.txt")), InteractionRecoveryTest.call("c3", "list_dir", Json.obj("path", ".")),
                        InteractionRecoveryTest.call("c4", "write_file", Json.obj("path", "n.txt", "content", "fresh")), InteractionRecoveryTest.call("c5", "read_file", Json.obj("path", "n.txt")), InteractionRecoveryTest.call("c6", "read_file", Json.obj("path", "c.txt"))),
                FeatureTest.json(FeatureTest.answer("All read.", 10, 3)))) {
            Agent agent = new Agent(tools);
            agent.run(Provider.CUSTOM, server.url(), "", "fixture", "fixture", "system", List.of(ChatClient.Message.user("Read everything")), false, ui(events));
            var results = agent.collect("", "fixture").stream().filter(m -> m.role.equals("tool")).toList();
            check(results.stream().map(m -> m.toolCallId).toList().equals(List.of("c1", "c2", "c3", "c4", "c5", "c6")), "tool messages keep the model's order");
            check(results.get(0).content.contains("content of a") && results.get(1).content.contains("content of b") && results.get(2).content.contains("a.txt"), "parallel reads return their own data");
            check(results.get(4).content.contains("fresh"), "a read after a write in the same step sees the write");
            check(results.get(5).content.contains("content of c"), "reads after the mutation still run");
            check(events.equals(List.of("start:c1", "done:c1", "start:c2", "done:c2", "start:c3", "done:c3", "start:c4", "done:c4", "start:c5", "done:c5", "start:c6", "done:c6")), "UI events stay sequential and ordered");
            check(server.requests.size() == 2, "one tool step and one answer");
            check(ToolBatch.total() - startedBefore == 5, "the agent ran the reads of both runs in parallel (" + (ToolBatch.total() - startedBefore) + ")");
        }

        int before = asks.get();
        // Three reads outside the work folder need permission: one card at a time, and ALWAYS from the first covers the others.
        try (var server = new FeatureTest.Server(
                InteractionRecoveryTest.reply("", InteractionRecoveryTest.call("p1", "read_file", Json.obj("path", outside.resolve("x.txt").toString())), InteractionRecoveryTest.call("p2", "read_file", Json.obj("path", outside.resolve("y.txt").toString())), InteractionRecoveryTest.call("p3", "read_file", Json.obj("path", outside.resolve("z.txt").toString()))),
                FeatureTest.json(FeatureTest.answer("Outside read.", 10, 3)))) {
            Agent agent = new Agent(tools);
            agent.run(Provider.CUSTOM, server.url(), "", "fixture", "fixture", "system", List.of(ChatClient.Message.user("Read outside")), false, ui(new ArrayList<>()));
            var results = agent.collect("", "fixture").stream().filter(m -> m.role.equals("tool")).toList();
            check(results.size() == 3 && results.stream().noneMatch(m -> m.isError), "permitted parallel reads succeed");
            check(overlap.get() == 0, "approval cards never overlap");
            check(asks.get() - before == 1, "ALWAYS from the first question covers the parallel reads (asked " + (asks.get() - before) + ")");
            check(results.get(0).content.contains("outside x") && results.get(2).content.contains("outside z"), "outside reads keep their order");
        }
    }
}
