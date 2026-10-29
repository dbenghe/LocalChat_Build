package localchat;

import javax.swing.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.*;

/**
 * 1.28.14: the PDFCONTROL dialog works on its own (no conversation, no working folder): project picker, automatic PDFs, every action
 * of the dropdown end to end on synthetic projects (real Python engine over MCP, real local tools), clear errors, six languages.
 * No model, no API.
 */
public final class Release11214Test {
    static int checks;
    static final List<PdfControlDialog> ALL = new ArrayList<>();
    static final int NEW_KEYS = 35;

    interface Work { void run() throws Exception; }
    static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    static void edt(Work w) throws Exception {
        Throwable[] failure = {null};
        SwingUtilities.invokeAndWait(() -> { try { w.run(); } catch (Throwable e) { failure[0] = e; } });
        if (failure[0] != null) throw new Exception(failure[0]);
    }
    /** Evaluates on the event thread, polling until true or the timeout (seconds). */
    static boolean until(BooleanSupplier condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            boolean[] ok = {false};
            edt(() -> ok[0] = condition.getAsBoolean());
            if (ok[0]) return true;
            Thread.sleep(25);
        }
        return false;
    }
    static String sha(Path p) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))); }
    static Path real(Path p) {
        try { return p.toRealPath(); } catch (java.io.IOException e) { return p.getParent() == null ? p : real(p.getParent()).resolve(p.getFileName()); }
    }
    static boolean under(Path file, Path... parents) { for (Path parent : parents) if (real(file).startsWith(real(parent))) return true; return false; }
    /** Everything in the project except Controle and Output: relative path -> hash. */
    static Map<String, String> snapshot(Path project) throws Exception {
        Map<String, String> map = new TreeMap<>();
        try (Stream<Path> all = Files.walk(project)) {
            for (Path f : all.filter(Files::isRegularFile).collect(Collectors.toList())) {
                String rel = project.relativize(f).toString().replace('\\', '/');
                if (!rel.startsWith("Controle/") && !rel.startsWith("Output/")) map.put(rel, sha(f));
            }
        }
        return map;
    }
    static Tools tools(Path home, Path folder) {
        return new Tools(new Tools.Context(folder, home.resolve("skills"), home.resolve("rules.md"), home.resolve("settings.properties"), Map.of(), System.getProperty("audit.python"),
                new Mcp(), PathMap.none(), true, EnumSet.noneOf(Tools.Access.class), (a, b) -> Tools.Decision.YES), List.of());
    }
    static void python(Path home, String name, String script, Path argument) throws Exception {
        Files.writeString(home.resolve(name), script, StandardCharsets.UTF_8);
        Process process = new ProcessBuilder(System.getProperty("audit.python"), "-I", home.resolve(name).toString(), argument.toString()).redirectErrorStream(true).start();
        String made = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        check(process.waitFor() == 0, "fixtures " + name + ": " + made);
    }

    static final String EXTRA = """
            pdf('PDF/123456_WA800_V_rev2.pdf', v('WA800'))
            pdf('OLD/123456_WA900_V.pdf', v('WA900')); pdf('_tmp/123456_TMP1_V.pdf', v('TMP1'))
            pdf('Controle/Oud/123456_CTL1_V.pdf', v('CTL1')); pdf('Output/123456_OUT1_V.pdf', v('OUT1'))
            """;
    static final String OTHERS = """
            import sys, pymupdf
            from pathlib import Path
            root = Path(sys.argv[1])
            def pdf(rel, text):
                p = root / rel; p.parent.mkdir(parents=True, exist_ok=True)
                d = pymupdf.open(); pg = d.new_page(width=800, height=600); pg.insert_text((45, 70), text, fontsize=10); d.save(str(p)); d.close()
            def v(project, mark, note):
                return 'Projectnummer:\\n' + project + '\\nVormtekening\\n' + mark + '_V\\n' + note + '\\nWapeningstekening\\n'
            for side, note in (('OUD', 'oude versie'), ('NIEUW', 'nieuwe versie met wijziging')):
                pdf('654321/' + side + '/654321_B1_V.pdf', v('654321', 'B1', note)); pdf('654321/' + side + '/654321_B2_V.pdf', v('654321', 'B2', note))
            pdf('777777/OLD/777777_X1_V.pdf', v('777777', 'X1', 'only old'))
            """;
    static final String SERVER = """
            import sys, pymupdf
            from pathlib import Path
            root = Path(sys.argv[1])
            def pdf(rel, text):
                p = root / rel; p.parent.mkdir(parents=True, exist_ok=True)
                d = pymupdf.open(); pg = d.new_page(width=842, height=595); pg.insert_text((45, 70), text, fontsize=10); d.save(str(p)); d.close()
            pdf('123456_WA800_V_rev1.pdf', 'server'); pdf('123456_ZZ1_V.pdf', 'only server')
            """;

    static final class Run {
        PdfControlDialog dialog;
        final PdfControlTest.StubUi ui = new PdfControlTest.StubUi();
        final AtomicReference<Object> record = new AtomicReference<>();
        final AtomicInteger started = new AtomicInteger(), finished = new AtomicInteger();
        final List<Path> folders = new CopyOnWriteArrayList<>(), opened = new CopyOnWriteArrayList<>();
        volatile boolean readOnly;
    }
    static Run open(McpServer mcp, Path workDir, List<String> hints, Path home) throws Exception {
        Run run = new Run();
        edt(() -> {
            run.dialog = new PdfControlDialog(null, mcp, workDir, hints, home, folder -> tools(home, folder), () -> run.readOnly, s -> run.started.incrementAndGet(), run.record::set,
                    run.folders::add, run.finished::incrementAndGet);
            run.dialog.ui = run.ui;
            run.dialog.opener = run.opened::add;
        });
        check(until(() -> run.dialog.root() != null, 90), "the dialog read the projects root from the engine");
        ALL.add(run.dialog);
        return run;
    }
    static String projectText(PdfControlDialog d) { return Objects.toString(d.project.getEditor().getItem(), ""); }
    /** Types what a user would: action, project, optional manual selection, Porneste; returns when the dialog is idle again. */
    static void go(Run run, int op, String project, boolean manual) throws Exception {
        int errors = run.ui.errors.size(), confirms = run.ui.confirms.size(), finished = run.finished.get();
        edt(() -> {
            run.dialog.operation.setSelectedIndex(op);
            run.dialog.project.setSelectedItem(project);
            if (manual) run.dialog.choose.doClick(); else run.dialog.auto.doClick();
            run.dialog.start.doClick();
        });
        boolean idle = until(() -> !run.dialog.running && (run.ui.errors.size() > errors || run.finished.get() > finished || (confirms < run.ui.confirms.size() && !run.ui.answer)), 420);
        check(idle, "the action ended (operation " + op + "): status=" + run.dialog.status.getText());
    }
    static String text(Run run) throws Exception { String[] t = {null}; edt(() -> t[0] = run.dialog.details.getText()); return t[0]; }
    static List<Path> artifacts(Run run) throws Exception {
        List<Path> files = new ArrayList<>();
        edt(() -> { for (int i = 0; i < run.dialog.artifacts.size(); i++) files.add(run.dialog.artifacts.get(i)); });
        return files;
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("localchat.home"))), "r11214-");
        Path pack = Path.of(System.getProperty("audit.package"));
        Path engine = Files.createDirectories(home.resolve("pdfcontrol"));
        try (Stream<Path> files = Files.list(pack)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".py")).collect(Collectors.toList())) Files.copy(f, engine.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
        Path root = Files.createDirectories(home.resolve("PRODUCTIE")).toRealPath();
        Path project = root.resolve("123456");
        python(home, "fixture.py", Release11213Test.FIXTURE + EXTRA, project);
        python(home, "others.py", OTHERS, root);
        Files.createDirectories(root.resolve("Misc"));
        Files.createDirectories(root.resolve("12345"));
        Path server = Files.createDirectories(home.resolve("server-folder"));
        python(home, "server.py", SERVER, server);
        McpServer mcp = new McpServer("pdfcontrol", List.of(System.getProperty("audit.python"), "-I", pack.resolve("octop_mcp.py").toString()),
                Map.of("PDFCONTROL_PACKAGE", pack.toString(), "PDFCONTROL_PROJECTS_ROOT", root.toString()), null);
        try {
            mcp.start(60000);
            check(mcp.tools().stream().anyMatch(t -> t.name.equals("pdfcontrol_start_workflow")), "the engine is connected");
            helpers(mcp, root, project);
            standalone(mcp, home, root, project);
            actions(mcp, home, root, project, server);
            failures(mcp, home, root, project, server);
            translations();
            localChat(home, root, pack, server);
        } finally {
            edt(() -> { for (PdfControlDialog d : ALL) d.dispose(); });
            mcp.close();
        }
        System.out.println("Release11214Test OK, checks=" + checks);
        System.exit(0);
    }
    static Object engine(McpServer mcp, String tool, Map<String, Object> args) {
        try { return PdfControlDialog.invoke(mcp, tool, args); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    static List<Object> rows(McpServer mcp, String project) {
        return PdfAutoFill.rows(project, (tool, args) -> engine(mcp, tool, args));
    }
    static void expectRefusal(Work w, String must, String what) throws Exception {
        try { w.run(); } catch (java.io.IOException expected) { check(expected.getMessage() != null && expected.getMessage().toLowerCase(Locale.ROOT).contains(must.toLowerCase(Locale.ROOT)), what + ": message names the problem: " + expected.getMessage()); return; }
        throw new AssertionError(what + ": no refusal");
    }

    // ---------------------------------------------------------------- picker, defaults, plans (no run)
    static void helpers(McpServer mcp, Path root, Path project) throws Exception {
        List<String> projects = PdfControlDialog.projectFolders(root);
        check(projects.containsAll(List.of("123456", "654321", "777777")) && projects.size() == 3, "project picker lists exactly the six-digit folders: " + projects);
        check(PdfControlDialog.guessProject(project.resolve("PDF"), List.of("654321"), projects).equals("123456"), "the working folder's project wins over chat hints");
        check(PdfControlDialog.guessProject(root.resolve("Misc"), List.of("lucram la 654321 azi"), projects).equals("654321"), "a project number from the chat fills the picker");
        check(PdfControlDialog.guessProject(root.resolve("Misc"), List.of("proiect 999999"), projects).isEmpty(), "an unknown number is not preselected");
        check(PdfControlDialog.guessProject(root.resolve("Misc"), List.of(), projects).isEmpty(), "no conversation and no project in the folder: nothing preselected, the user picks");
        List<Object> rows = rows(mcp, "123456");
        List<String> drawings = PdfAutoFill.drawings(rows);
        check(drawings.size() == 10 && drawings.contains("PDF/123456_WA224_V.pdf") && drawings.contains("Desene finale/123456_WA133_W.pdf"), "automatic PDFs: the project's original drawings: " + drawings);
        check(drawings.stream().noneMatch(p -> p.contains("OLD") || p.contains("Controle") || p.contains("Output") || p.contains("_tmp")) && PdfAutoFill.compareDefaults(rows) == null, "automatic PDFs skip OLD, Controle, Output and helper folders: " + drawings);
        List<List<String>> sides = PdfAutoFill.compareDefaults(rows(mcp, "654321"));
        check(sides != null && sides.get(0).size() == 2 && sides.get(1).size() == 2 && sides.get(0).get(0).startsWith("OUD/") && sides.get(1).get(0).startsWith("NIEUW/"), "compare defaults OUD -> NIEUW: " + sides);
        check(PdfControlDialog.reportJobs(project).isEmpty(), "no control jobs yet");
        for (int op : new int[]{PdfControlDialog.CHECK, PdfControlDialog.EXTRACT, PdfControlDialog.WORKFLOW}) {
            PdfControlDialog.Plan plan = PdfControlDialog.plan(op, "123456", project, rows, null, null, null, null, null);
            check(plan.steps().size() == 1 && plan.steps().get(0).tool().equals(PdfControlDialog.OPERATIONS[op]) && !plan.steps().get(0).local() && plan.count() == 10 && plan.confirm().contains("123456"), "plan of operation " + op);
        }
        PdfControlDialog.Plan pairs = PdfControlDialog.plan(PdfControlDialog.PAIRS, "123456", project, rows, null, null, null, null, null);
        check(pairs.steps().size() == 2 && pairs.steps().get(0).tool().equals("pdfcontrol_fix_missing_pairs") && pairs.steps().get(0).local() && pairs.steps().get(1).tool().equals("pdfcontrol_start_pairs") && pairs.steps().get(1).refresh(), "pairs: project level fix_missing_pairs first, then the pair job on the refreshed files");
        PdfControlDialog.Plan compare = PdfControlDialog.plan(PdfControlDialog.COMPARE, "654321", root.resolve("654321"), rows(mcp, "654321"), null, null, null, null, null);
        check(compare.steps().get(0).args().get("old_files").equals(sides.get(0)) && compare.steps().get(0).args().get("new_files").equals(sides.get(1)), "compare uses OUD as old and NIEUW as new by default");
        PdfControlDialog.Plan chosen = PdfControlDialog.plan(PdfControlDialog.COMPARE, "123456", project, rows, null, List.of(List.of("a/o.pdf"), List.of("b/n.pdf")), null, null, null);
        check(chosen.steps().get(0).args().get("old_files").equals(List.of("a/o.pdf")) && chosen.steps().get(0).args().get("new_files").equals(List.of("b/n.pdf")), "explicit OUD/NIEUW selection replaces the defaults");
        PdfControlDialog.Plan explicit = PdfControlDialog.plan(PdfControlDialog.CHECK, "123456", project, rows, List.of("PDF/123456_WA300_V.pdf"), null, "soest", null, null);
        check(explicit.steps().get(0).args().get("files").equals(List.of("PDF/123456_WA300_V.pdf")) && "soest".equals(explicit.steps().get(0).args().get("factory")), "explicit selection and factory are passed on");
        List<Object> many = new ArrayList<>();
        for (int i = 0; i < 501; i++) many.add(Json.obj("path", "PDF/f" + i + ".pdf", "pdf_source", true));
        expectRefusal(() -> PdfControlDialog.plan(PdfControlDialog.CHECK, "123456", project, many, null, null, null, null, null), "501", "more than 500 PDFs");
        expectRefusal(() -> PdfControlDialog.plan(PdfControlDialog.CHECK, "777777", project, List.of(), null, null, null, null, null), "777777", "no original PDFs");
        expectRefusal(() -> PdfControlDialog.plan(PdfControlDialog.COMPARE, "123456", project, rows, null, null, null, null, null), "OUD", "no OUD/NIEUW");
        expectRefusal(() -> PdfControlDialog.plan(PdfControlDialog.REGENERATE, "123456", project, List.of(), null, null, null, null, null), "123456", "no job to regenerate");
        expectRefusal(() -> PdfControlDialog.plan(PdfControlDialog.SERVER, "123456", project, List.of(), null, null, null, " ", null), "server", "no server folder");
        expectRefusal(() -> PdfControlDialog.plan(PdfControlDialog.SERVER, "123456", project, List.of(), null, null, null, "Z:\\niciodata\\123456", null), "niciodata", "missing server folder");
    }

    // ---------------------------------------------------------------- the dialog with no conversation
    static void standalone(McpServer mcp, Path home, Path root, Path project) throws Exception {
        Run run = open(mcp, home, List.of(), home);
        List<String> items = new ArrayList<>();
        String[] selected = {null};
        edt(() -> { for (int i = 0; i < run.dialog.project.getItemCount(); i++) items.add(run.dialog.project.getItemAt(i)); selected[0] = projectText(run.dialog); });
        check(items.containsAll(List.of("123456", "654321", "777777")) && items.size() == 3 && selected[0].isEmpty(), "the picker lists the projects of the PRODUCTIE root; nothing preselected without chat: " + items + " [" + selected[0] + "]");
        check(run.dialog.operation.getItemCount() == 7, "all actions are in the dropdown");
        go(run, PdfControlDialog.CHECK, "", false);
        check(run.ui.errors.size() == 1 && run.ui.errors.get(0).contains("sase cifre") && run.started.get() == 0, "no project chosen: a clear message and nothing started: " + run.ui.errors);
        go(run, PdfControlDialog.CHECK, "111111", false);
        check(run.ui.errors.size() == 2 && run.ui.errors.get(1).contains("111111") && run.started.get() == 0, "a project that does not exist: a clear message: " + run.ui.errors);
        Run chat = open(mcp, home, List.of("Verifica proiectul 654321, te rog"), home);
        String[] guess = {null};
        edt(() -> guess[0] = projectText(chat.dialog));
        check(guess[0].equals("654321"), "with a conversation the project is filled in from it: " + guess[0]);
        Run folder = open(mcp, project.resolve("PDF"), List.of(), home);
        edt(() -> guess[0] = projectText(folder.dialog));
        check(guess[0].equals("123456"), "with a working folder the project is filled in from it");
        edt(() -> { folder.dialog.operation.setSelectedIndex(PdfControlDialog.CHECK); folder.dialog.project.setSelectedItem("123456"); });
        check(until(() -> folder.dialog.selection.getText().contains("10"), 60), "the automatic selection is shown: " + folder.dialog.selection.getText());
        edt(() -> { folder.dialog.operation.setSelectedIndex(PdfControlDialog.COMPARE); folder.dialog.project.setSelectedItem("654321"); });
        check(until(() -> folder.dialog.selection.getText().contains("OUD 2") && folder.dialog.selection.getText().contains("NIEUW 2"), 60), "compare shows OUD/NIEUW by default: " + folder.dialog.selection.getText());
    }
    // ---------------------------------------------------------------- every action, end to end
    static void results(Run run, Path... parents) throws Exception {
        List<Path> files = artifacts(run);
        check(!files.isEmpty(), "the run lists result files: " + text(run));
        for (Path f : files) check(Files.exists(f) && under(f, parents), "result stays in Controle/Output (or the analyses folder): " + f);
    }
    static String status(Run run) { return Json.str(run.record.get(), "status"); }
    static boolean finishedOk(Run run) { return List.of("done", "partial").contains(status(run)); }

    static void actions(McpServer mcp, Path home, Path root, Path project, Path server) throws Exception {
        Run run = open(mcp, home, List.of(), home);
        Path controle = project.resolve("Controle"), output = project.resolve("Output");
        Map<String, String> before = snapshot(project);
        // 1) Verifica desenele: PDFs chosen automatically
        go(run, PdfControlDialog.CHECK, "123456", false);
        check(run.ui.errors.isEmpty() && run.ui.confirms.size() == 1 && run.ui.confirms.get(0).contains("123456") && run.ui.confirms.get(0).contains("(10 PDF"), "check: one confirmation naming project and PDF count: " + run.ui.errors + run.ui.confirms);
        check(finishedOk(run) && run.started.get() == 1 && run.finished.get() == 1 && run.folders.size() == 1 && real(run.folders.get(0)).equals(real(project)), "check ran to the end: " + run.record.get());
        check(String.valueOf(Json.str(run.record.get(), "job_id")).matches("[0-9a-f]{32}"), "check: job id of the engine: " + run.record.get());
        results(run, controle, output);
        edt(() -> { check(run.dialog.open.isEnabled(), "open button enabled after a run"); run.dialog.open.doClick(); });
        check(run.opened.size() == 1 && Files.exists(run.opened.get(0)) && under(run.opened.get(0), controle, output), "'Deschide raportul' opens a result file: " + run.opened);
        check(snapshot(project).equals(before), "check: nothing written outside Controle/Output (hotfolder untouched)");
        check(PdfControlDialog.reportJobs(project).size() == 1, "the finished control job is listed for regeneration");
        // 2) Extrage planse si perechi
        go(run, PdfControlDialog.EXTRACT, "123456", false);
        check(run.ui.errors.isEmpty() && finishedOk(run), "extract ran: " + run.ui.errors + run.record.get());
        results(run, controle, output);
        check(snapshot(project).equals(before), "extract: hotfolder untouched");
        // 3) Flux complet
        go(run, PdfControlDialog.WORKFLOW, "123456", false);
        check(run.ui.errors.isEmpty() && finishedOk(run), "full workflow ran: " + run.ui.errors + run.record.get());
        results(run, controle, output);
        check(snapshot(project).equals(before), "workflow: hotfolder untouched");
        // 4) Regenereaza raportul PDF
        go(run, PdfControlDialog.REGENERATE, "123456", false);
        check(run.ui.errors.isEmpty() && finishedOk(run), "regenerate ran on an earlier job: " + run.ui.errors + run.record.get());
        results(run, controle, output);
        check(snapshot(project).equals(before), "regenerate: hotfolder untouched");
        // 5) Compara OUD/NIEUW with the default folders, on project 654321
        Path other = root.resolve("654321");
        Map<String, String> otherBefore = snapshot(other);
        go(run, PdfControlDialog.COMPARE, "654321", false);
        check(run.ui.errors.isEmpty() && run.ui.confirms.get(run.ui.confirms.size() - 1).contains("2 OUD / 2 NIEUW") && finishedOk(run), "compare ran with OUD -> NIEUW by default: " + run.ui.errors + run.record.get());
        results(run, other.resolve("Controle"), other.resolve("Output"));
        check(snapshot(other).equals(otherBefore), "compare: hotfolder untouched");
        // 6) a manual selection overrides the automatic one
        run.ui.picks = List.of("PDF/123456_WA300_V.pdf");
        go(run, PdfControlDialog.CHECK, "123456", true);
        check(run.ui.errors.isEmpty() && finishedOk(run) && run.ui.confirms.get(run.ui.confirms.size() - 1).contains("(1 PDF"), "explicit selection: one file checked: " + run.ui.errors + run.ui.confirms);
        run.ui.picks = null;
        // 7) a declined confirmation starts nothing
        int jobs = PdfControlDialog.reportJobs(project).size(), startedBefore = run.started.get();
        run.ui.answer = false;
        go(run, PdfControlDialog.CHECK, "123456", false);
        run.ui.answer = true;
        check(run.started.get() == startedBefore && PdfControlDialog.reportJobs(project).size() == jobs && run.ui.errors.isEmpty(), "declined confirmation: nothing runs");
        // 8) Completeaza perechi V/W at project level, then the pair job
        Map<String, String> pairsBefore = snapshot(project);
        go(run, PdfControlDialog.PAIRS, "123456", false);
        check(run.ui.errors.isEmpty() && run.ui.confirms.get(run.ui.confirms.size() - 1).contains("nimic nu se suprascrie"), "pairs: the confirmation says what is copied: " + run.ui.errors);
        check(finishedOk(run), "pairs ran to the end: " + run.record.get() + " " + text(run));
        Path made = project.resolve("PDF/123456_WA224_W.pdf");
        check(Files.isRegularFile(made) && sha(made).equals(sha(project.resolve("Desene finale/123456_WA133_W.pdf"))), "pairs: the missing W was copied next to the V drawing as a new file");
        Map<String, String> pairsAfter = snapshot(project);
        Map<String, String> added = new TreeMap<>(pairsAfter);
        added.keySet().removeAll(pairsBefore.keySet());
        check(added.keySet().equals(Set.of("PDF/123456_WA224_W.pdf")), "pairs: the only new file in the project is that copy: " + added.keySet());
        for (Map.Entry<String, String> e : pairsBefore.entrySet()) check(e.getValue().equals(pairsAfter.get(e.getKey())), "pairs: existing file untouched: " + e.getKey());
        String shown = text(run);
        check(shown.contains("COPIED: 123456_WA224_V.pdf") && shown.contains("NOT FOUND: 123456_WA400_V.pdf") && shown.contains("AMBIGUOUS: 123456_WA600_V.pdf") && shown.contains("NOT OVERWRITTEN: 123456_WA500_V.pdf"), "pairs: the dialog shows what was copied and what could not be: " + shown);
        results(run, controle, output, home.resolve("analyses"));
        go(run, PdfControlDialog.PAIRS, "123456", false);
        check(run.ui.errors.isEmpty() && finishedOk(run) && snapshot(project).equals(pairsAfter), "pairs again: nothing is copied twice or overwritten: " + run.ui.errors + text(run));
        // 9) server revision compare, read-only
        Map<String, String> serverBefore = snapshot(project);
        run.ui.server = server.toString();
        int asked = run.ui.confirms.size();
        run.readOnly = true;
        edt(() -> { run.dialog.operation.setSelectedIndex(PdfControlDialog.SERVER); run.dialog.chooseServer.doClick(); });
        check(until(() -> run.dialog.serverFolder.getText().equals(server.toString()), 10), "server folder chosen");
        go(run, PdfControlDialog.SERVER, "123456", false);
        String revisions = text(run);
        check(run.ui.errors.isEmpty() && run.ui.confirms.size() == asked && !artifacts(run).isEmpty() && revisions.contains("ONLY ON SERVER") && revisions.contains("123456_zz1_v"), "server compare works in read-only mode, without a confirmation, and reports the differences: " + run.ui.errors + revisions);
        check(revisions.contains("NEWER LOCALLY") && revisions.contains("123456_wa800_v: local rev 2") && revisions.contains("server rev 1"), "server compare reads the revision from the file name: " + revisions);
        results(run, home.resolve("analyses"));
        check(snapshot(project).equals(serverBefore), "server compare: nothing written next to the drawings");
        int starts = run.started.get(), errorsBefore = run.ui.errors.size();
        go(run, PdfControlDialog.CHECK, "123456", false);
        check(run.started.get() == starts && run.ui.errors.size() == errorsBefore + 1 && run.ui.errors.get(errorsBefore).contains("doar citire"), "read-only mode blocks writing actions with a message: " + run.ui.errors);
        run.readOnly = false;
    }

    // ---------------------------------------------------------------- errors are shown, never silent
    static void failures(McpServer mcp, Path home, Path root, Path project, Path server) throws Exception {
        Run run = open(mcp, home, List.of(), home);
        go(run, PdfControlDialog.CHECK, "777777", false);
        check(run.ui.errors.size() == 1 && run.ui.errors.get(0).contains("777777") && run.ui.errors.get(0).contains("OLD") && run.started.get() == 0, "project without original PDFs: " + run.ui.errors);
        go(run, PdfControlDialog.COMPARE, "123456", false);
        check(run.ui.errors.size() == 2 && run.ui.errors.get(1).contains("OUD") && run.started.get() == 0, "compare without OUD/NIEUW: " + run.ui.errors);
        go(run, PdfControlDialog.REGENERATE, "654321", false);
        check(run.ui.errors.size() == 3 && run.ui.errors.get(2).contains("654321") && run.ui.errors.get(2).contains("Verifica desenele") || run.ui.errors.size() == 3 && run.ui.errors.get(2).contains("654321"), "regenerate without an earlier job: " + run.ui.errors);
        edt(() -> run.dialog.serverFolder.setText(""));
        go(run, PdfControlDialog.SERVER, "123456", false);
        check(run.ui.errors.size() == 4 && run.started.get() == 0, "server compare without a server folder: " + run.ui.errors);
        edt(() -> run.dialog.serverFolder.setText("Z:\\niciodata\\123456"));
        go(run, PdfControlDialog.SERVER, "123456", false);
        check(run.ui.errors.size() == 5 && run.ui.errors.get(4).contains("niciodata") && run.started.get() == 0, "missing server folder: " + run.ui.errors);
        run.ui.picks = List.of("PDF/does_not_exist.pdf");
        go(run, PdfControlDialog.CHECK, "123456", true);
        run.ui.picks = null;
        check(run.ui.errors.size() == 6 && run.started.get() == 1 && run.finished.get() == 1 && "failed".equals(status(run)), "a job the engine refuses ends with a visible error, not silence: " + run.ui.errors + run.record.get());
        String[] line = {null};
        edt(() -> line[0] = run.dialog.status.getText());
        check(line[0].length() > 3 && !text(run).isBlank() && !run.dialog.running, "the failure is also in the status line and the details: " + line[0]);
        // engine not reachable (never started)
        McpServer dead = new McpServer("pdfcontrol", List.of(System.getProperty("audit.python"), "-I", "-c", "pass"), Map.of(), null);
        Run broken = new Run();
        edt(() -> {
            broken.dialog = new PdfControlDialog(null, dead, home, List.of(), home, f -> tools(home, f), () -> false, s -> broken.started.incrementAndGet(), broken.record::set, broken.folders::add, broken.finished::incrementAndGet);
            broken.dialog.ui = broken.ui;
            ALL.add(broken.dialog);
        });
        check(until(() -> broken.dialog.status.getText().contains("indisponibil"), 60), "an unreachable engine is reported in the dialog: " + broken.dialog.status.getText());
        go(broken, PdfControlDialog.CHECK, "123456", false);
        check(broken.ui.errors.size() == 1 && broken.started.get() == 0, "start with an unreachable engine: a message, nothing starts: " + broken.ui.errors);
    }
    // ---------------------------------------------------------------- the real app window, no conversation
    static LocalChat newWindow(Path appHome) throws Exception {
        System.setProperty("localchat.home", appHome.toString());
        LocalChat[] window = {null};
        edt(() -> {
            var ctor = LocalChat.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            window[0] = ctor.newInstance();
            window[0].pdfControlNotices = new ArrayList<>();
        });
        return window[0];
    }
    static void localChat(Path home, Path root, Path pack, Path server) throws Exception {
        Path app = Files.createDirectories(home.resolve("app")), bare = Files.createDirectories(home.resolve("bare"));
        String python = System.getProperty("audit.python");
        Files.writeString(app.resolve("unelte.json"), Json.write(Json.obj("pdfPython", python, "mcpServers", Json.obj("pdfcontrol", Json.obj("command", python, "args", List.of("-I", pack.resolve("octop_mcp.py").toString()),
                "env", Json.obj("PDFCONTROL_PACKAGE", pack.toString(), "PDFCONTROL_PROJECTS_ROOT", root.toString()))))), StandardCharsets.UTF_8);
        Path appEngine = Files.createDirectories(app.resolve("pdfcontrol"));
        try (Stream<Path> files = Files.list(pack)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".py")).collect(Collectors.toList())) Files.copy(f, appEngine.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
        String previous = System.getProperty("localchat.home");
        LocalChat chat = null, none = null;
        try {
            chat = newWindow(app);
            LocalChat window = chat;
            edt(() -> { var m = LocalChat.class.getDeclaredMethod("reloadTools"); m.setAccessible(true); m.invoke(window); });
            PdfControlDialog[] dialog = {null};
            boolean opened = until(() -> { dialog[0] = window.createPdfControl(); return dialog[0] != null; }, 120);
            check(opened, "the dialog opens in the app once the tools are up, with no conversation: summary=" + fieldValue(window, "mcpSummary") + " err=" + fieldValue(window, "toolsConfigError") + " notices=" + window.pdfControlNotices.stream().distinct().collect(Collectors.toList()));
            String starting = I18n.text("Tool-urile PDFCONTROL pornesc inca. Incearca din nou peste cateva secunde.");
            check(window.pdfControlNotices.stream().allMatch(n -> n.equals(starting)), "before the tools are up the user is told to retry: " + window.pdfControlNotices);
            ALL.add(dialog[0]);
            Object conversation = FeatureTest.field(window, "conversationFile");
            JTextField workDir = FeatureTest.field(window, "workDirField");
            String workDirBefore = workDir.getText();
            check(conversation == null, "no conversation is open");
            check(until(() -> dialog[0].root() != null, 60), "the app's dialog reads the projects root");
            PdfControlTest.StubUi ui = new PdfControlTest.StubUi();
            dialog[0].ui = ui;
            boolean[] sawBusy = {false};
            edt(() -> { dialog[0].operation.setSelectedIndex(PdfControlDialog.CHECK); dialog[0].project.setSelectedItem("123456"); dialog[0].start.doClick(); });
            boolean finished = until(() -> { sawBusy[0] |= (Boolean) fieldValue(window, "busy"); return !dialog[0].running && !ui.confirms.isEmpty() && dialog[0].open.isEnabled(); }, 240);
            check(finished, "check from the app dialog finished: " + ui.errors + dialog[0].status.getText());
            check(sawBusy[0] && !(Boolean) fieldValue(window, "busy") && ((JButton) fieldValue(window, "pdfControlButton")).isEnabled(), "the chat is blocked during the run and free afterwards: " + sawBusy[0]);
            check(ui.errors.isEmpty() && workDir.getText().equals(workDirBefore) && FeatureTest.field(window, "conversationFile") == null && ((List<?>) FeatureTest.field(window, "history")).isEmpty(), "no conversation, working folder or history was created by the dialog: " + ui.errors);
            ui.server = server.toString();
            edt(() -> { dialog[0].operation.setSelectedIndex(PdfControlDialog.SERVER); dialog[0].chooseServer.doClick(); dialog[0].project.setSelectedItem("123456"); dialog[0].start.doClick(); });
            check(until(() -> !dialog[0].running && dialog[0].details.getText().contains("ONLY ON SERVER"), 120), "the server compare works from the app dialog too: " + ui.errors + dialog[0].details.getText());
            // an app without any PDFCONTROL server: a message the user sees
            Files.writeString(bare.resolve("unelte.json"), "{\"mcpServers\":{}}", StandardCharsets.UTF_8);
            none = newWindow(bare);
            LocalChat empty = none;
            edt(() -> { var m = LocalChat.class.getDeclaredMethod("reloadTools"); m.setAccessible(true); m.invoke(empty); });
            String expected = I18n.text("PDFCONTROL complet nu este conectat. Reporneste serverele MCP.");
            check(until(() -> { empty.createPdfControl(); return empty.pdfControlNotices.stream().anyMatch(n -> n.startsWith(expected)); }, 60), "no PDFCONTROL server: the user gets a message: " + empty.pdfControlNotices);
        } finally {
            if (chat != null) ((Mcp) FeatureTest.field(chat, "mcp")).closeAll();
            if (none != null) ((Mcp) FeatureTest.field(none, "mcp")).closeAll();
            if (previous != null) System.setProperty("localchat.home", previous);
        }
    }
    static Object fieldValue(Object target, String name) { try { return FeatureTest.field(target, name); } catch (Exception e) { throw new IllegalStateException(e); } }

    // ---------------------------------------------------------------- six languages, Romanian without diacritics
    static void translations() throws Exception {
        String raw;
        try (var in = Release11214Test.class.getResourceAsStream("/localchat/i18n/ro.json")) { raw = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        List<String> lines = raw.lines().filter(l -> l.startsWith("  \"")).collect(Collectors.toList());
        List<String> keys = new ArrayList<>();
        for (String line : lines.subList(lines.size() - NEW_KEYS, lines.size())) {
            String body = line.strip();
            if (body.endsWith(",")) body = body.substring(0, body.length() - 1);
            keys.add(((Map<?, ?>) Json.parse("{" + body + "}")).keySet().iterator().next().toString());
        }
        check(keys.size() == NEW_KEYS && keys.contains("Porneste") && keys.contains("Stare MCP: {0}"), "the 1.28.14 keys are the last entries: " + keys);
        for (String key : keys) check(key.chars().allMatch(c -> c < 128), "Romanian text without diacritics: " + key);
        java.util.regex.Pattern holes = java.util.regex.Pattern.compile("\\{([0-9])\\}");
        String saved = I18n.language();
        try {
            for (String code : I18n.CODES) {
                I18n.setLanguage(code);
                for (String key : keys) {
                    var m = holes.matcher(key);
                    Set<String> wanted = new TreeSet<>();
                    while (m.find()) wanted.add(m.group(1));
                    String shown = I18n.text(key, "<0>", "<1>", "<2>", "<3>");
                    check(!shown.isBlank() && !shown.matches("(?s).*\\{[0-9]\\}.*"), code + " fills the placeholders: " + key + " -> " + shown);
                    for (String hole : wanted) check(shown.contains("<" + hole + ">"), code + " keeps placeholder " + hole + " in: " + key);
                    check(I18n.keys(code).contains(key), code + " has an entry for: " + key);
                    if (code.equals("ro")) check(shown.chars().allMatch(c -> c < 128), "ro shown without diacritics: " + shown);
                }
            }
        } finally {
            I18n.setLanguage(saved);
        }
    }
}