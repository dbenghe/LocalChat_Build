package localchat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.swing.*;

/** 1.28.12: automatic and manual handoff to a new linked conversation when the context is too large or compaction fails. */
public final class Release11212Test {
    static int checks;

    static void check(boolean ok, String why) {
        checks++;
        if (!ok) throw new AssertionError(why);
    }

    /** Local endpoint: normal calls answer "Continued answer"; summary calls fail (HTTP 500) when failSummary is set. Counts everything. */
    static final class Fixture implements AutoCloseable {
        final HttpServer http;
        final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger summaries = new AtomicInteger(), normal = new AtomicInteger();
        volatile boolean failSummary;

        Fixture() throws IOException {
            http = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            http.createContext("/", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                bodies.add(body);
                boolean summary = body.contains("Summarize historical conversation");
                int status = 200;
                String reply;
                if (summary) {
                    summaries.incrementAndGet();
                    if (failSummary) {
                        status = 500;
                        reply = "{\"error\":\"fixture summary failure\"}";
                    } else reply = FeatureTest.answer("Verified summary of project 507123.", 100, 25);
                } else {
                    normal.incrementAndGet();
                    reply = FeatureTest.answer("Continued answer", 1000, 10);
                }
                byte[] data = reply.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, data.length);
                exchange.getResponseBody().write(data);
                exchange.close();
            });
            http.start();
        }

        String url() {
            return "http://127.0.0.1:" + http.getAddress().getPort() + "/v1";
        }

        public void close() {
            http.stop(0);
        }
    }

    static void settle(LocalChat app, BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < end) {
            boolean[] ready = {false};
            FeatureTest.edt(() -> ready[0] = condition.getAsBoolean());
            if (ready[0]) break;
            Thread.sleep(25);
        }
        FeatureTest.idle(app);
        Thread.sleep(50);
        FeatureTest.idle(app);
    }

    static LocalChat open(Path home, String url, int limit, boolean autoCompact) throws Exception {
        LocalChat[] app = {null};
        FeatureTest.edt(() -> {
            app[0] = AttachmentTest.window(home, url);
            WorkspacePanel workspace = FeatureTest.field(app[0], "workspace");
            workspace.limit.setValue(limit);
            if (workspace.autoCompact.isSelected() != autoCompact) workspace.autoCompact.doClick();
        });
        return app[0];
    }

    static String filler(String tag, int chars) {
        StringBuilder b = new StringBuilder();
        for (int n = 0; b.length() < chars; n++) b.append(tag).append(" line ").append(n).append(" dimensions and quantities checked\n");
        return b.toString();
    }

    /** Project history: tool read, large user/assistant pairs and a user confirmation. */
    static List<ChatClient.Message> material(int pairs, int chars) {
        List<ChatClient.Message> h = new ArrayList<>();
        h.add(ChatClient.Message.user("Proiect 507123: converteste desenele conform regulilor."));
        h.add(ChatClient.Message.assistant("Citesc fisierul.", List.of(new ChatClient.ToolCall("c1", "read_file", new LinkedHashMap<>(Map.of("path", "C:\\Work\\507123\\Input\\desen-A.pdf")))), ""));
        h.add(ChatClient.Message.toolResult("c1", "pagina 1 dimensiuni verificate", false));
        for (int n = 0; n < pairs; n++) {
            h.add(ChatClient.Message.user("Intrebare " + n + " " + filler("evidence" + n, chars)));
            h.add(ChatClient.Message.assistant("Raspuns " + n + " " + filler("answer" + n, chars), List.of(), ""));
        }
        h.add(ChatClient.Message.user("Da, confirm regulile de conversie A4 -> A3 pentru proiectul 507123."));
        h.add(ChatClient.Message.assistant("Am notat confirmarea regulilor de conversie.", List.of(), ""));
        return h;
    }

    static void seed(LocalChat app, int pairs, int chars) throws Exception {
        FeatureTest.edt(() -> {
            List<ChatClient.Message> history = FeatureTest.field(app, "history");
            history.clear();
            history.addAll(material(pairs, chars));
        });
    }

    static Path dir(LocalChat app) throws Exception {
        Path[] out = {null};
        FeatureTest.edt(() -> out[0] = FeatureTest.<Path>field(app, "conversationsDir"));
        return out[0];
    }

    static Path active(LocalChat app) throws Exception {
        Path[] out = {null};
        FeatureTest.edt(() -> out[0] = FeatureTest.<Path>field(app, "conversationFile"));
        return out[0];
    }

    static int files(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) return 0;
        try (var s = Files.list(dir)) {
            return (int) s.filter(p -> p.getFileName().toString().endsWith(".json")).count();
        }
    }

    static boolean visible(LocalChat app, String name) throws Exception {
        boolean[] v = {false};
        FeatureTest.edt(() -> v[0] = FeatureTest.<JButton>field(app, name).isVisible());
        return v[0];
    }

    static String text(LocalChat app, String name) throws Exception {
        String[] v = {""};
        FeatureTest.edt(() -> v[0] = FeatureTest.<JButton>field(app, name).getText());
        return v[0];
    }

    static void click(LocalChat app, String name) throws Exception {
        FeatureTest.edt(() -> FeatureTest.<JButton>field(app, name).doClick());
        FeatureTest.idle(app);
    }

    static Conversations.Saved only(Path dir, Path except) throws Exception {
        try (var s = Files.list(dir)) {
            List<Path> others = s.filter(p -> p.getFileName().toString().endsWith(".json") && !p.equals(except)).toList();
            check(others.size() == 1, "exactly one other conversation exists");
            return Conversations.load(others.get(0));
        }
    }

    static boolean hasHandoff(Object request) {
        Object messages = Json.get(request, "messages");
        return messages instanceof List<?> list && list.stream().anyMatch(m -> "user".equals(Json.str(m, "role")) && Objects.toString(Json.str(m, "content"), "").startsWith(Handoff.MARK));
    }

    static void builder() throws Exception {
        Conversations.State state = new Conversations.State();
        state.factoryProject = "507123";
        state.factoryName = "MBS";
        state.factoryFolder = "C:\\Work\\507123";
        state.projectStatus = "restarted";
        state.model = "fixture-model";
        state.memory.summary = "Verified project 507123; drawings A read; conversion rules confirmed.";
        List<ChatClient.Message> history = material(2, 200);
        try (Fixture server = new Fixture()) {
            Handoff.Input in = new Handoff.Input("Proiect 507123", "a.json", "Compactarea a esuat", "C:\\Work\\507123", "C:\\Scratch\\new", "C:\\Scratch\\old", state, history, List.of(), "Genereaza PDF-urile pentru 507123.", 6000);
            String a = Handoff.build(in), b = Handoff.build(in);
            check(a.equals(b), "handoff is deterministic: same input, same text");
            check(a.startsWith(Handoff.MARK) && Handoff.isHandoff(ChatClient.Message.user(a)), "handoff carries the marker");
            for (String part : new String[]{"## Context", "## Hard rules", "## Pending / in flight", "## Compaction memory", "## Next step", "507123", "C:\\Work\\507123", "C:\\Scratch\\new", "read_skill", "desen-A.pdf", "Genereaza PDF-urile pentru 507123.", "confirm regulile de conversie", "conversion rules confirmed"})
                check(a.contains(part), "handoff contains " + part);
            check(a.length() < 6000 * 4, "handoff stays inside its budget");
            check(Handoff.budget(1000) == 1500 && Handoff.budget(10_000_000) == 6000 && Handoff.budget(20000) == 5000, "handoff budget is clamped between 1500 and 6000 tokens");
            check(Handoff.reasonOf(ChatClient.Message.user(a)).equals("Compactarea a esuat"), "reason is readable back from the handoff");
            check(server.bodies.isEmpty(), "building a handoff makes no model call");
        }
        String again = Handoff.continuationTitle("Proiect 507123 (continuare)", "(continuare)");
        check(again.equals("Proiect 507123 (continuare 2)") && Handoff.continuationTitle(again, "(continuare)").equals("Proiect 507123 (continuare 3)"), "continuation titles count up instead of nesting");
        check(Handoff.continuationTitle("Proiect", "(continued)").equals("Proiect (continued)"), "title suffix is added once");
        IOException cause = ContextGuard.overflow("Istoricul depaseste limita");
        check(ContextGuard.isOverflow(cause) && ContextGuard.handoffWorthy(cause), "overflow is handoff-worthy");
        check(!ContextGuard.handoffWorthy(new ContextGuard.Declined("draft changed")), "a changed draft never triggers a handoff");
        check(!ContextGuard.handoffWorthy(new java.io.InterruptedIOException("stop")), "stop never triggers a handoff");
        check(!ContextGuard.handoffWorthy(new IOException("HTTP 429 rate limit")) && !ContextGuard.handoffWorthy(new IOException("HTTP 401 unauthorized")) && ContextGuard.handoffWorthy(new IOException("HTTP 500 server error")), "quota and authentication errors never trigger a handoff");
        check(ContextGuard.isOverflow(ContextGuard.compactionFailure(new IOException("HTTP 500"))) && !ContextGuard.isOverflow(ContextGuard.compactionFailure(new IOException("HTTP 429"))), "compaction failure becomes a handoff trigger only when worthy");
    }

    static void persistence(Path home) throws Exception {
        Path dir = Files.createDirectories(home.resolve("persist"));
        Conversations.State old = new Conversations.State();
        old.hotfolderProject = "C:\\Hot\\506946";
        old.factoryProject = "506946";
        old.model = "m";
        Conversations.State next = Conversations.State.continuation(old, "T (continuare)", "old.json", "T");
        check(next.handoffFresh && next.continuedFrom.equals("old.json") && next.hotfolderProject.equals(old.hotfolderProject) && next.factoryProject.equals("506946") && next.model.equals("m"), "continuation keeps project, hotfolder and model, flags handoffFresh");
        Path oldFile = dir.resolve("old.json"), newFile = dir.resolve("new.json");
        old.archived = true;
        old.continuedTo = "new.json";
        old.continuedToTitle = "T (continuare)";
        Conversations.save(oldFile, "C:\\Hot", List.of(ChatClient.Message.user("Proiect 506946")), old);
        Conversations.save(newFile, "C:\\Hot", List.of(ChatClient.Message.user(Handoff.MARK + " reason: x")), next);
        var o = Conversations.load(oldFile).state();
        var n = Conversations.load(newFile).state();
        check(o.archived && o.continuedTo.equals("new.json") && o.continuedToTitle.equals("T (continuare)"), "old chat keeps archive flag and forward link after reload");
        check(n.handoffFresh && n.continuedFrom.equals("old.json") && n.continuedFromTitle.equals("T") && !n.archived, "new chat keeps backward link and loop guard after reload");
        Hotfolder.Change change = new Hotfolder.Change("506946", Path.of("C:\\Hot\\506946"), false, List.of(), "sig");
        var chats = Conversations.list(dir);
        check(chats.stream().filter(c -> HotfolderChats.matches(c, change)).count() == 1, "only the continuation matches the hotfolder project");
        var found = HotfolderChats.find(chats, change);
        check(found != null && found.file().getFileName().toString().equals("new.json"), "hotfolder events go to the continuation, never un-archive the superseded chat");
        check(!HotfolderChats.matches(Conversations.load(oldFile), change), "superseded chat never matches again");
    }

    static void manual(Path home) throws Exception {
        try (Fixture server = new Fixture()) {
            LocalChat app = open(Files.createDirectories(home.resolve("manual")), server.url(), 20000, false);
            Path dir = dir(app);
            FeatureTest.edt(() -> FeatureTest.command(app, "/continue"));
            check(files(dir) == 0 && server.bodies.isEmpty(), "/continue without history creates nothing and calls nothing");
            seed(app, 1, 300);
            FeatureTest.edt(() -> {
                check(FeatureTest.<JButton>field(app, "continueButton").isEnabled(), "button enabled while idle");
                check(!FeatureTest.<JButton>field(app, "continueButton").getToolTipText().isBlank(), "button has a tooltip");
            });
            click(app, "continueButton");
            Path first = active(app);
            check(files(dir) == 2 && first != null, "manual button creates the new conversation");
            var old = only(dir, first);
            var now = Conversations.load(first);
            check(old.state().archived && old.state().continuedTo.equals(first.getFileName().toString()), "old chat is archived and links forward");
            check(now.state().continuedFrom.equals(old.file().getFileName().toString()) && !now.state().archived && now.state().handoffFresh, "new chat links back, is active and guarded");
            check(old.messages().size() == material(1, 300).size(), "old conversation is kept complete, never deleted");
            check(now.messages().size() == 1 && Handoff.isHandoff(now.messages().get(0)) && now.messages().get(0).content.contains("read_skill"), "new chat is seeded with the handoff and the hard rules");
            check(now.title().startsWith(old.title()) && now.title().length() > old.title().length(), "new title is the original plus a continuation suffix");
            check(visible(app, "linkFromButton") && !visible(app, "linkToButton") && text(app, "linkFromButton").contains(old.title().substring(0, Math.min(10, old.title().length()))), "new chat shows 'continued from' link");
            check(server.bodies.isEmpty(), "manual handoff makes no model call");
            click(app, "linkFromButton");
            check(active(app).equals(old.file()) && visible(app, "linkToButton") && !visible(app, "linkFromButton"), "link opens the archived old chat, which links forward");
            click(app, "linkToButton");
            check(active(app).equals(first) && visible(app, "linkFromButton"), "link opens the continuation again");
            check(server.bodies.isEmpty(), "following links never calls the API");
            FeatureTest.edt(() -> FeatureTest.command(app, "/continue"));
            Path second = active(app);
            check(!second.equals(first) && files(dir) == 3, "/continue works on a continuation too");
            var third = Conversations.load(second);
            check(third.title().matches(".* \\([^()]+ 2\\)$"), "second continuation is numbered");
            check(Conversations.load(first).state().archived && Conversations.load(first).state().continuedTo.equals(second.getFileName().toString()), "chain keeps every chat archived and linked");
            LocalChat restarted = open(home.resolve("manual"), server.url(), 20000, false);
            Path[] before = {null};
            FeatureTest.edt(() -> {
                FeatureTest.call(restarted, "openSession", new Class<?>[]{Conversations.Saved.class}, Conversations.load(first));
            });
            check(visible(restarted, "linkFromButton") && visible(restarted, "linkToButton"), "links survive a restart (both directions on the middle chat)");
            click(restarted, "linkFromButton");
            check(active(restarted).equals(old.file()), "link works after restart");
            check(server.bodies.isEmpty(), "restart and navigation make no API request");
            restarted.dispose();
            app.dispose();
        }
    }

    static void overflow(Path home) throws Exception {
        try (Fixture server = new Fixture()) {
            LocalChat app = open(Files.createDirectories(home.resolve("overflow")), server.url(), 20000, false);
            seed(app, 8, 12000);
            String draft = "Continua cu proiectul 507123 si genereaza PDF-urile.";
            Path dir = dir(app);
            NormalModeTest.ask(app, draft);
            settle(app, () -> server.normal.get() >= 1);
            Path now = active(app);
            check(files(dir) == 2, "overflow created exactly one new conversation");
            var old = only(dir, now);
            var next = Conversations.load(now);
            check(old.state().archived && old.state().continuedTo.equals(now.getFileName().toString()), "overflowing chat is archived and links forward");
            check(old.messages().size() == material(8, 12000).size(), "old archive stays complete");
            check(server.summaries.get() == 0 && server.normal.get() == 1 && server.bodies.size() == 1, "no compaction and no extra call: only the resent request");
            check(hasHandoff(Json.parse(server.bodies.get(0))), "resent request starts from the handoff");
            check(next.messages().stream().anyMatch(m -> m.role.equals("user") && m.content.equals(draft)) && next.messages().get(next.messages().size() - 1).content.equals("Continued answer"), "last user message was resent in the new chat and answered");
            check(!next.state().handoffFresh && next.state().continuedFrom.equals(old.file().getFileName().toString()), "a finished turn clears the loop guard and keeps the link");
            check(old.messages().stream().filter(m -> m.role.equals("user") && m.content.equals(draft)).count() == 0, "the unsent request was not added to the old chat");
            app.dispose();
        }
    }

    static void compaction(Path home) throws Exception {
        try (Fixture server = new Fixture()) {
            server.failSummary = true;
            LocalChat app = open(Files.createDirectories(home.resolve("compaction")), server.url(), 20000, true);
            seed(app, 8, 12000);
            String draft = "Genereaza raportul pentru proiectul 507123.";
            Path dir = dir(app);
            NormalModeTest.ask(app, draft);
            settle(app, () -> server.normal.get() >= 1);
            check(server.summaries.get() >= 1, "compaction was attempted first");
            Path now = active(app);
            check(files(dir) == 2, "compaction failure created one continuation");
            var old = only(dir, now);
            check(old.state().archived && old.state().continuedTo.equals(now.getFileName().toString()), "failed-compaction chat archived and linked");
            check(server.normal.get() == 1 && hasHandoff(Json.parse(server.bodies.get(server.bodies.size() - 1))), "the request continued in the new chat from the handoff");
            var next = Conversations.load(now);
            check(next.messages().stream().anyMatch(m -> m.role.equals("user") && m.content.equals(draft)), "the user's message is preserved in the new chat");
            check(old.messages().stream().noneMatch(m -> m.content.equals(draft)), "nothing was repeated in the old chat");
            app.dispose();
        }
    }

    static void inTurn(Path home) throws Exception {
        Path local = Files.createDirectories(home.resolve("in-turn")), report = local.resolve("large-report.txt");
        Files.writeString(report, "PDF evidence 507123 dimensions and checked quantities\n".repeat(900));
        try (ContextGuardTest.Server server = new ContextGuardTest.Server(report)) {
            server.fail = true;
            LocalChat app = open(local, server.url(), 16000, true);
            String question = "Create visible PDF reports for project 507123 using the checked drawings.";
            NormalModeTest.ask(app, question);
            settle(app, () -> server.normal.get() >= 2);
            Path dir = dir(app), now = active(app);
            check(server.summaries.get() >= 1 && server.normal.get() == 2, "tool loop hit a failing compaction, then one resent request (summaries=" + server.summaries.get() + ", normal=" + server.normal.get() + ", requests=" + server.requests.size() + ", files=" + files(dir) + ")");
            check(files(dir) == 2, "mid-turn compaction failure created one continuation");
            var old = only(dir, now);
            check(old.state().archived && old.messages().stream().anyMatch(m -> m.role.equals("tool") && m.content.length() > 12000), "old chat archived with the full tool evidence");
            var next = Conversations.load(now);
            check(next.state().continuedFrom.equals(old.file().getFileName().toString()) && next.messages().get(0).content.contains("read_file"), "handoff lists the files read");
            check(next.messages().stream().anyMatch(m -> m.role.equals("user") && m.content.equals(question)), "the in-flight request was resent in the new chat");
            check(Files.readString(report).startsWith("PDF evidence"), "source file untouched");
            app.dispose();
        }
    }

    static void loopGuard(Path home) throws Exception {
        try (Fixture server = new Fixture()) {
            LocalChat app = open(Files.createDirectories(home.resolve("loop")), server.url(), 2048, false);
            seed(app, 3, 6000);
            Path dir = dir(app);
            NormalModeTest.ask(app, "Continua proiectul 507123.");
            Thread.sleep(300);
            FeatureTest.idle(app);
            Path now = active(app);
            check(files(dir) == 2, "exactly one handoff; a chat created by handoff never hands off again");
            var next = Conversations.load(now);
            check(next.state().handoffFresh && next.state().continuedTo.isBlank(), "second overflow is stopped by the loop guard");
            check(server.bodies.isEmpty(), "an over-limit request is never sent");
            NormalModeTest.ask(app, "Inca o incercare.");
            check(files(dir) == 2 && server.bodies.isEmpty(), "retrying does not create more chats");
            app.dispose();
        }
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("localchat.home"))), "run-");
        try {
            builder();
            persistence(home);
            manual(home);
            overflow(home);
            compaction(home);
            inTurn(home);
            loopGuard(home);
            System.out.println("PASS: " + checks + " handoff checks; deterministic builder, no model call, overflow and compaction-failure triggers, archive and links both ways, resend, manual button, loop guard, restart, hotfolder");
        } finally {
            FeatureTest.edt(() -> {
                for (var w : java.awt.Window.getWindows()) w.dispose();
            });
        }
        System.exit(0);
    }
}