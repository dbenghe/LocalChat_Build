package localchat;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.*;

/**
 * 1.28.13: pdfcontrol_fix_missing_pairs (pair detection, mesh number from the drawing, copy under the pair name, no overwrite)
 * and the server policy (copies and new files without a card; overwrite, edit, move and delete still ask; scripts stay blocked).
 * Real Python tool with synthetic PDFs, no model, no API.
 */
public final class Release11213Test {
    static int checks;

    static void check(boolean ok, String why) {
        checks++;
        if (!ok) throw new AssertionError(why);
    }

    static final String FIXTURE = """
            import sys, pymupdf
            from pathlib import Path
            root = Path(sys.argv[1])
            def pdf(rel, text):
                p = root / rel; p.parent.mkdir(parents=True, exist_ok=True)
                d = pymupdf.open(); pg = d.new_page(width=800, height=600); pg.insert_text((45, 70), text, fontsize=10); d.save(str(p)); d.close()
            def v(mark, ref=None):
                return 'Projectnummer:\\n123456\\nVormtekening\\n' + mark + '_V\\n' + ((ref + '_W\\n') if ref else '') + 'Wapeningstekening\\n'
            def w(mark, extra=''):
                return 'Projectnummer:\\n123456\\nWapeningstekening\\n' + mark + '_W\\n' + extra
            pdf('PDF/123456_WA224_V.pdf', v('WA224', 'WA133'))
            pdf('Desene finale/123456_WA133_W.pdf', w('WA133'))
            pdf('PDF/123456_WA300_V.pdf', v('WA300')); pdf('PDF/123456_WA300_W.pdf', w('WA300'))
            pdf('PDF/123456_WA400_V.pdf', v('WA400', 'WA999'))
            pdf('PDF/123456_WA500_V.pdf', v('WA500', 'WA133')); pdf('PDF/123456_WA500_W.pdf', w('WA130'))
            pdf('PDF/123456_WA600_V.pdf', v('WA600', 'WA700'))
            pdf('Other/123456_WA700_W.pdf', w('WA700', 'Revisie A\\n')); pdf('Extra/123456_WA700_W.pdf', w('WA700', 'Revisie B\\n'))
            """;

    static String sha(Path p) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }

    static Tools tools(Path root, boolean automatic, Tools.Permission permission) {
        return new Tools(new Tools.Context(root, root.resolve("skills"), root.resolve("rules.md"), root.resolve("settings.properties"), Map.of(), System.getProperty("audit.python"),
                new Mcp(), PathMap.none(), automatic, EnumSet.noneOf(Tools.Access.class), permission), List.of());
    }

    static Tools.Result call(Tools t, Object... kv) {
        Map<String, Object> args = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) args.put((String) kv[i], kv[i + 1]);
        return t.execute(new ChatClient.ToolCall("p" + checks, "pdfcontrol_fix_missing_pairs", args));
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("localchat.home"))), "r11213-");
        pairs(home);
        serverPolicy(home);
        prompts(home);
        routing(home);
        System.out.println("Release11213Test OK, checks=" + checks);
    }

    static void pairs(Path home) throws Exception {
        Path engine = Files.createDirectories(home.resolve("pdfcontrol"));
        try (Stream<Path> files = Files.list(Path.of(System.getProperty("audit.package")))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".py")).collect(Collectors.toList())) Files.copy(f, engine.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
        check(Files.isRegularFile(engine.resolve("fix_missing_pairs.py")), "sync_engine delivers fix_missing_pairs.py into the package");
        Path project = home.resolve("PRODUCTIE/123456");
        Files.writeString(home.resolve("fixture.py"), FIXTURE, StandardCharsets.UTF_8);
        Process process = new ProcessBuilder(System.getProperty("audit.python"), "-I", home.resolve("fixture.py").toString(), project.toString()).redirectErrorStream(true).start();
        String made = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        check(process.waitFor() == 0, "fixtures: " + made);
        Path pdfDir = project.resolve("PDF"), finalDir = project.resolve("Desene finale");
        Map<Path, String> before = new TreeMap<>();
        try (Stream<Path> all = Files.walk(project)) {
            for (Path f : all.filter(Files::isRegularFile).collect(Collectors.toList())) before.put(f, sha(f));
        }
        Tools t = tools(home, true, (a, b) -> Tools.Decision.YES);
        check(t.specs().stream().anyMatch(s -> s.name.equals("pdfcontrol_fix_missing_pairs")), "tool is offered");
        check(tools(home, true, (a, b) -> Tools.Decision.YES).analysisOnly().specs().stream().noneMatch(s -> s.name.equals("pdfcontrol_fix_missing_pairs")), "analysis agent does not get the copying tool");
        // read-only mode forces a dry run; apply=false is a dry run that lists the pair and creates nothing
        Tools ro = tools(home, true, (a, b) -> Tools.Decision.YES).readOnly(true);
        Tools.Result roRun = call(ro, "project", "123456", "project_root", project.toString());
        check((roRun.isError || roRun.text.contains("dry run")) && !Files.exists(pdfDir.resolve("123456_WA224_W.pdf")), "in read-only mode a call copies nothing: " + roRun.text);
        Tools.Result dry = call(t, "project", "123456", "project_root", project.toString(), "apply", false);
        check(!dry.isError && dry.text.contains("dry run") && dry.text.contains("WOULD COPY: 123456_WA224_V.pdf [mesh WA133]"), "apply=false lists only: " + dry.text);
        check(!Files.exists(pdfDir.resolve("123456_WA224_W.pdf")), "dry run creates nothing");
        // a refused card creates nothing
        List<String> asks = new ArrayList<>();
        Tools.Result denied = call(tools(home, false, (a, b) -> {asks.add(a);return Tools.Decision.NO;}), "project", "123456", "project_root", project.toString());
        check(denied.isError && asks.size() == 1 && !Files.exists(pdfDir.resolve("123456_WA224_W.pdf")), "refused card copies nothing");
        // the real run
        Tools.Result r = call(t, "project", "123456", "project_root", project.toString());
        check(!r.isError, "pair run: " + r.text);
        check(r.text.contains("COPIED: 123456_WA224_V.pdf [mesh WA133]") && r.text.contains("Desene finale"), "copied from the final drawings folder, mesh number read from the drawing: " + r.text);
        Path made224 = pdfDir.resolve("123456_WA224_W.pdf");
        check(Files.isRegularFile(made224) && sha(made224).equals(sha(finalDir.resolve("123456_WA133_W.pdf"))), "pair copied under the missing pair name <project>_<mark>_W.pdf, byte-identical");
        check(Files.isRegularFile(finalDir.resolve("123456_WA133_W.pdf")) && before.get(finalDir.resolve("123456_WA133_W.pdf")).equals(sha(finalDir.resolve("123456_WA133_W.pdf"))), "the source is not moved or changed");
        check(r.text.contains("NOT FOUND: 123456_WA400_V.pdf [mesh WA999]") && !Files.exists(pdfDir.resolve("123456_WA400_W.pdf")), "unknown mesh is reported, nothing invented");
        check(r.text.contains("AMBIGUOUS: 123456_WA600_V.pdf") && !Files.exists(pdfDir.resolve("123456_WA600_W.pdf")), "two different sources: no guess");
        check(r.text.contains("NOT OVERWRITTEN: 123456_WA500_V.pdf") && sha(pdfDir.resolve("123456_WA500_W.pdf")).equals(before.get(pdfDir.resolve("123456_WA500_W.pdf"))), "an existing different W is never overwritten");
        check(r.text.contains("Pair present: 1 | copied: 1"), "summary counts: " + r.text);
        for (Map.Entry<Path, String> e : before.entrySet()) check(sha(e.getKey()).equals(e.getValue()), "original unchanged: " + e.getKey());
        long pdfCount = Files.list(pdfDir).count();
        Tools.Result again = call(t, "project", "123456", "project_root", project.toString());
        check(!again.isError && again.text.contains("copied: 0") && Files.list(pdfDir).count() == pdfCount, "second run is idempotent: " + again.text);
        String reportLine = r.text.lines().filter(l -> l.contains("Report: ")).findFirst().orElse("");
        Path report = Path.of(reportLine.substring(reportLine.indexOf("Report: ") + 8).trim());
        Object json = Json.parse(Files.readString(report));
        check(Json.str(json, "copied", 0, "mesh").equals("WA133") && Json.str(json, "copied", 0, "pair").equals("123456_WA224_W.pdf"), "report names mesh and pair");
        // arguments are validated
        check(call(t, "project", "12", "project_root", project.toString()).isError, "project must be six digits");
        check(call(t, "project", "123456", "project_root", project.toString(), "extra", "x").isError, "unknown argument refused");
        check(call(t, "project", "123456", "project_root", project.toString(), "folders", List.of(home.resolve("elsewhere").toString())).isError, "folder outside the project refused");
        // run_command pointing at the tool
        check(Tools.pairHunt("powershell -Command \"Get-ChildItem J:\\x -Recurse -Filter *_W.pdf | Copy-Item -Destination .\"") && Tools.pairHunt("copy \"a\\123456_WA133_W.pdf\" \"b\\123456_WA224_W.pdf\"") && !Tools.pairHunt("dir C:\\x") && !Tools.pairHunt("echo hello"), "pair hunting commands recognised");
        Tools.Result hint = tools(home, true, (a, b) -> Tools.Decision.YES).execute(new ChatClient.ToolCall("h", "run_command", Map.of("command", "echo copy 123456_WA133_W.pdf", "cwd", home.toString())));
        check(hint.text.contains("pdfcontrol_fix_missing_pairs"), "run_command result points to the pair tool: " + hint.text);
        check(t.specs().stream().filter(s -> s.name.equals("run_command")).anyMatch(s -> s.description.contains("pdfcontrol_fix_missing_pairs")), "run_command description points to the pair tool");
    }

    /** The user decided: copies and plain new files to the server need no card. Overwrite, edit, move and delete still do. */
    static void serverPolicy(Path home) throws Exception {
        Path scratch = home.resolve("scratch"), server = Files.createDirectories(home.resolve("server/Projecten/7")), local = Files.createDirectories(home.resolve("local"));
        char drive = server.getRoot().toString().charAt(0);
        FileGuard g = FileGuard.of(scratch, List.of(), () -> Set.of(Character.toUpperCase(drive)));
        Release11211Test.asked.clear();
        Tools yes = Release11211Test.tools(local, g, true, Tools.Decision.YES), no = Release11211Test.tools(local, g, true, Tools.Decision.NO);
        Path src = Release11211Test.put(local.resolve("a.pdf"), "pdf-1");
        Tools.Result copy = Release11211Test.run(yes, "copy_path", "source", src.toString(), "destination", server.resolve("a.pdf").toString());
        check(!copy.isError && Files.readString(server.resolve("a.pdf")).equals("pdf-1") && Release11211Test.asked.isEmpty(), "copy of a new file to the server: no card: " + copy.text);
        Tools.Result fresh = Release11211Test.run(no, "write_file", "path", server.resolve("list.csv").toString(), "content", "a;b\n");
        check(!fresh.isError && Files.exists(server.resolve("list.csv")) && Release11211Test.asked.isEmpty(), "new production file on the server: no card, even when the user would refuse cards");
        Path batch = local.resolve("batch");
        Release11211Test.put(batch.resolve("x.pdf"), "x");
        Release11211Test.put(batch.resolve("sub/y.xlsx"), "y");
        check(!Release11211Test.run(no, "copy_path", "source", batch.toString(), "destination", server.resolve("batch").toString()).isError && Files.exists(server.resolve("batch/sub/y.xlsx")) && Release11211Test.asked.isEmpty(), "folder copy of new files: no card");
        // still asking: replace, edit, append, move, delete
        Release11211Test.put(local.resolve("b.pdf"), "pdf-2");
        check(Release11211Test.run(no, "copy_path", "source", local.resolve("b.pdf").toString(), "destination", server.resolve("a.pdf").toString()).isError && Files.readString(server.resolve("a.pdf")).equals("pdf-1"), "copy onto an existing server file does not overwrite without overwrite=true");
        Release11211Test.asked.clear();
        check(Release11211Test.run(no, "copy_path", "source", local.resolve("b.pdf").toString(), "destination", server.resolve("a.pdf").toString(), "overwrite", true).isError && Release11211Test.serverCards() == 1 && Files.readString(server.resolve("a.pdf")).equals("pdf-1"), "overwrite=true onto an existing server file asks; refused: unchanged");
        Release11211Test.asked.clear();
        check(!Release11211Test.run(yes, "copy_path", "source", local.resolve("b.pdf").toString(), "destination", server.resolve("a.pdf").toString(), "overwrite", true).isError && Release11211Test.serverCards() == 1 && Files.readString(server.resolve("a.pdf")).equals("pdf-2"), "confirmed overwrite goes through, with the card");
        Release11211Test.asked.clear();
        check(Release11211Test.run(no, "edit_file", "path", server.resolve("list.csv").toString(), "old_text", "a;b", "new_text", "c;d").isError && Release11211Test.serverCards() == 1 && Files.readString(server.resolve("list.csv")).startsWith("a;b"), "edit of an existing server file asks");
        Release11211Test.asked.clear();
        check(Release11211Test.run(no, "append_file", "path", server.resolve("list.csv").toString(), "content", "z\n").isError && Release11211Test.serverCards() == 1, "append to an existing server file asks");
        Release11211Test.asked.clear();
        check(Release11211Test.run(no, "write_file", "path", server.resolve("list.csv").toString(), "content", "zzz").isError && Release11211Test.serverCards() == 1 && Files.readString(server.resolve("list.csv")).startsWith("a;b"), "write_file over an existing server CSV asks (this is what MiniMax did in place)");
        Release11211Test.asked.clear();
        check(Release11211Test.run(no, "move_path", "source", server.resolve("a.pdf").toString(), "destination", server.resolve("moved.pdf").toString()).isError && Files.exists(server.resolve("a.pdf")) && Release11211Test.serverCards() == 1, "moving a server file asks");
        Path cwd = Files.createDirectories(scratch);
        for (String command : List.of("del \"" + server.resolve("a.pdf") + "\"", "powershell -Command \"Remove-Item '" + server.resolve("list.csv") + "'\"", "powershell -Command \"Set-Content '" + server.resolve("list.csv") + "' 'x'\"",
                "python -c \"open(r'" + server.resolve("list.csv") + "','w').write('x')\"", "ren \"" + server.resolve("a.pdf") + "\" z.pdf")) {
            Release11211Test.asked.clear();
            check(Release11211Test.run(no, "run_command", "command", command, "cwd", cwd.toString()).isError && Release11211Test.serverCards() == 1 && Files.exists(server.resolve("a.pdf")) && Files.readString(server.resolve("list.csv")).startsWith("a;b"), "delete/modify via shell asks: " + command);
        }
        // shell copy of a new file: no card
        Release11211Test.asked.clear();
        check(!Release11211Test.run(yes, "run_command", "command", "copy \"" + src + "\" \"" + server.resolve("shell.pdf") + "\"", "cwd", cwd.toString()).isError && Files.exists(server.resolve("shell.pdf")) && Release11211Test.asked.isEmpty(), "shell copy of a new file: no card");
        // hard blocks are unchanged: scripts, .md, _tmp*, OLD, extensions, in the project and on the server
        Path project = home.resolve("PRODUCTIE/123456");
        Release11211Test.asked.clear();
        for (String bad : List.of("run.py", "notes.md", "x.ps1", "_tmp.csv", "tmp2.pdf", "a.txt", "OLD/a.pdf")) {
            check(Release11211Test.run(yes, "write_file", "path", server.resolve(bad).toString(), "content", "x").isError && !Files.exists(server.resolve(bad)), "server still refuses " + bad);
        }
        Release11211Test.put(local.resolve("one.py"), "print(1)");
        check(Release11211Test.run(yes, "copy_path", "source", local.resolve("one.py").toString(), "destination", server.resolve("one.py").toString()).isError && !Files.exists(server.resolve("one.py")), "copying a script to the server is still refused");
        for (String bad : List.of("fix.py", "run.ps1", "notes.md", "_x.pdf", "tmp1.csv")) {
            check(Release11211Test.run(yes, "write_file", "path", project.resolve(bad).toString(), "content", "x").isError && !Files.exists(project.resolve(bad)), "project folder still refuses " + bad);
        }
        check(Release11211Test.asked.isEmpty(), "hard blocks show no card");
    }

    static void prompts(Path home) throws Exception {
        check(ToolRelevance.unlocked(List.of(), "Lipseste plasa pereche la planul 506774, cauta-o").contains(ToolRelevance.FILES), "Romanian pair request unlocks the file tools");
        check(ToolRelevance.unlocked(List.of(), "check the missing pair mesh").contains(ToolRelevance.FILES), "English pair request unlocks the file tools");
        check(ToolRelevance.names(ToolRelevance.FILES).contains("pdfcontrol_fix_missing_pairs"), "tool belongs to the file group");
        check(SkillNotes.class != null && Files.readString(Path.of("src/localchat/SkillNotes.java")).contains("pdfcontrol_fix_missing_pairs"), "skill notes name the tool");
        LocalChat[] app = {null};
        FeatureTest.edt(() -> {
            try {
                app[0] = AttachmentTest.window(home, "http://127.0.0.1:9/v1");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        String[] text = {null};
        FeatureTest.edt(() -> {
            try {
                text[0] = RuleCoverageTest.prompt(app[0], home);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        check(text[0].contains("pdfcontrol_fix_missing_pairs FIRST") && text[0].contains("no PowerShell/Python"), "system prompt: use the pair tool first, no scripts");
        check(text[0].contains("without a question") && text[0].contains("needs the user's confirmation") && !text[0].contains("only after the user confirms the exact file list"), "system prompt: server copies without a question, existing files still confirmed");
        String saved = I18n.language();
        try {
            for (String lang : List.of("nl", "ro", "es", "pl", "en", "tr")) {
                I18n.setLanguage(lang);
                String card = I18n.text("Completeaza plasele pereche lipsa"), details = I18n.text("doar lista, fara copiere"), hint = I18n.text("Plase pereche lipsa: foloseste pdfcontrol_fix_missing_pairs (citeste numarul de pe plan, cauta in desenele finale si copiaza cu numele perechii, fara suprascriere) in loc de scripturi PowerShell/Python.");
                check(!card.isBlank() && !details.isBlank() && hint.contains("pdfcontrol_fix_missing_pairs") && (lang.equals("ro") || !card.equals("Completeaza plasele pereche lipsa")), "UI text translated, " + lang);
                check(!I18n.text("Modificare pe server: confirmare necesara").isBlank() && (lang.equals("ro") || !I18n.text("Modificare pe server: confirmare necesara").equals("Modificare pe server: confirmare necesara")), "server title translated, " + lang);
            }
        } finally {
            I18n.setLanguage(saved);
        }
    }

    static final String REVISIONS = """
            import sys, pymupdf
            from pathlib import Path
            root = Path(sys.argv[1])
            def pdf(rel, text, field=None):
                p = root / rel; p.parent.mkdir(parents=True, exist_ok=True)
                d = pymupdf.open(); pg = d.new_page(width=842, height=595); pg.insert_text((45, 70), text, fontsize=10)
                if field: pg.insert_text((640, 545), 'Revisie', fontsize=9); pg.insert_text((700, 545), field, fontsize=9)
                d.save(str(p)); d.close()
            pdf('local/123456_A1_V_rev2.pdf', 'a'); pdf('server/123456_A1_V_rev1.pdf', 'b')
            pdf('local/123456_A2_V.pdf', 'c', 'A'); pdf('server/123456_A2_V.pdf', 'd', 'C')
            pdf('local/123456_A4_V.pdf', 'only local'); pdf('server/123456_A5_V.pdf', 'only server')
            pdf('local/OLD/123456_A1_V_rev0.pdf', 'excluded')
            """;

    static Tools.Result revisions(Tools t, Object... kv) {
        Map<String, Object> args = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) args.put((String) kv[i], kv[i + 1]);
        return t.execute(new ChatClient.ToolCall("r" + checks, "pdfcontrol_compare_server_revisions", args));
    }

    static void routing(Path home) throws Exception {
        // 1) prompt routing for control, compare and server revisions, without typed project/files
        LocalChat[] app = {null};
        FeatureTest.edt(() -> {
            try {
                app[0] = AttachmentTest.window(home, "http://127.0.0.1:9/v1");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        String[] text = {null};
        FeatureTest.edt(() -> {
            try {
                text[0] = RuleCoverageTest.prompt(app[0], home);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        String prompt = text[0];
        check(prompt.contains("pdfcontrol_start_check, then pdfcontrol_job_summary and pdfcontrol_job_findings") && prompt.contains("Never OCR, PowerShell or Python"), "prompt: drawing control goes to pdfcontrol_start_check + job_summary/job_findings, never OCR/PowerShell/Python");
        check(prompt.contains("pdfcontrol_start_compare (OUD/NIEUW)") && prompt.contains("project and files may be omitted"), "prompt: old versus new goes to pdfcontrol_start_compare; project and files may be omitted");
        check(prompt.contains("pdfcontrol_compare_server_revisions (read-only)") && prompt.contains("only as a new file"), "prompt: server versus download revisions use the read-only tool, copy only as a new file");
        check(!ToolRelevance.unlocked(List.of(), "verifica ce este pe server J: fata de folderul descarcat, daca este mai noua revizia").isEmpty(), "Romanian server revision request unlocks the tools");
        check(ToolRelevance.unlocked(List.of(), "which revision is newer, the server or my download?").contains(ToolRelevance.FILES), "English revision request unlocks the tools");
        check(ToolRelevance.names(ToolRelevance.FILES).contains("pdfcontrol_compare_server_revisions"), "revision tool belongs to the group that relevance unlocks");
        Tools t = tools(home, true, (a, b) -> Tools.Decision.YES);
        check(t.specs().stream().anyMatch(s -> s.name.equals("pdfcontrol_compare_server_revisions")) && tools(home, true, (a, b) -> Tools.Decision.YES).readOnly(true).specs().stream().anyMatch(s -> s.name.equals("pdfcontrol_compare_server_revisions")), "revision tool offered, also in read-only mode");
        check(tools(home, true, (a, b) -> Tools.Decision.YES).analysisOnly().specs().stream().anyMatch(s -> s.name.equals("pdfcontrol_compare_server_revisions")), "analysis agent may read-compare revisions");
        // 2) run_command hints
        check(Tools.checkHunt("python -c \"import fitz; print(fitz.open('a.pdf')[0].get_text())\"") && Tools.checkHunt("tesseract plan.pdf out") && Tools.checkHunt("pdftotext C:\\x\\plan.pdf -") && !Tools.checkHunt("dir *.pdf") && !Tools.checkHunt("python tool.py data.csv"), "OCR/text-extraction hunt detected, ordinary commands not");
        check(Tools.serverRevisionHunt("powershell -Command \"Compare-Object (gci J:\\PRODUCTIE\\123456) (gci C:\\Users\\x\\Downloads\\PRODUCTIE\\123456)\"") && Tools.serverRevisionHunt("robocopy J:\\a C:\\Users\\x\\Downloads\\PRODUCTIE\\1 /L") && !Tools.serverRevisionHunt("dir J:\\PRODUCTIE") && !Tools.serverRevisionHunt("dir C:\\Users\\x\\Downloads"), "server revision hunt detected, plain listings not");
        // 3) defaults for the start tools
        check("123456".equals(PdfAutoFill.project(Path.of("C:/Users/x/Downloads/PRODUCTIE/123456"))) && PdfAutoFill.project(Path.of("C:/Users/x/Downloads")) == null && PdfAutoFill.project(null) == null, "project comes from the working folder");
        List<Object> rows = new ArrayList<>();
        for (String path : List.of("PDF/123456_A_V.pdf", "Desene finale/123456_A_V.pdf", "PDF/123456_B_V.pdf", "OLD/123456_C_V.pdf", "Controle/123456_D_V.pdf", "Output/x.pdf", "PDF/x.xlsx", "OUD/123456_E_V.pdf", "NIEUW/123456_E_V.pdf", "NIEUW/123456_F_V.pdf"))
            rows.add(Json.obj("path", path, "pdf_source", path.endsWith(".pdf") && !path.startsWith("Controle")));
        List<String> drawings = PdfAutoFill.drawings(rows);
        check(drawings.contains("Desene finale/123456_A_V.pdf") && !drawings.contains("PDF/123456_A_V.pdf") && drawings.contains("PDF/123456_B_V.pdf") && drawings.stream().noneMatch(d -> d.startsWith("OLD/") || d.startsWith("Controle/") || d.startsWith("Output/") || d.endsWith(".xlsx")), "default files: one path per name, final folder first, no OLD/Controle/Output");
        List<List<String>> sides = PdfAutoFill.compareDefaults(rows);
        check(sides != null && sides.get(0).equals(List.of("OUD/123456_E_V.pdf")) && sides.get(1).equals(List.of("NIEUW/123456_E_V.pdf", "NIEUW/123456_F_V.pdf")), "compare defaults: OUD and NIEUW folders");
        Path workDir = Path.of("C:/x/PRODUCTIE/123456");
        java.util.function.BiFunction<String, Map<String, Object>, Object> fake = (tool, a) -> Json.obj("files", rows, "next_offset", null);
        Map<String, Object> filled = PdfAutoFill.fill("pdfcontrol_start_check", new LinkedHashMap<>(), workDir, fake);
        check("123456".equals(filled.get("project")) && filled.get("files") instanceof List<?> l && !l.isEmpty(), "start_check without arguments is filled from the working folder");
        Map<String, Object> given = new LinkedHashMap<>(Map.of("project", "654321", "files", List.of("a.pdf")));
        check(PdfAutoFill.fill("pdfcontrol_start_check", given, workDir, fake).equals(given), "explicit project and files are never replaced");
        Map<String, Object> compare = PdfAutoFill.fill("pdfcontrol_start_compare", new LinkedHashMap<>(), workDir, fake);
        check(compare.get("old_files") instanceof List<?> o && o.size() == 1 && compare.get("new_files") instanceof List<?> n && n.size() == 2, "start_compare without arguments uses OUD/NIEUW");
        check(PdfAutoFill.fill("pdfcontrol_job_summary", new LinkedHashMap<>(), workDir, fake).isEmpty(), "other tools are left alone");
        for (Runnable bad : List.<Runnable>of(() -> {
            try {PdfAutoFill.fill("pdfcontrol_start_check", new LinkedHashMap<>(), Path.of("C:/x/none"), fake);} catch (IllegalArgumentException e) {throw e;} catch (Exception e) {throw new RuntimeException(e);}
        }, () -> {
            try {PdfAutoFill.fill("pdfcontrol_start_compare", new LinkedHashMap<>(), workDir, (tool, a) -> Json.obj("files", List.of(Json.obj("path", "PDF/a.pdf", "pdf_source", true))));} catch (IllegalArgumentException e) {throw e;} catch (Exception e) {throw new RuntimeException(e);}
        })) {
            boolean refused = false;
            try {bad.run();} catch (IllegalArgumentException expected) {refused = true;}
            check(refused, "no project folder / no OUD+NIEUW: clear refusal instead of a guess");
        }
        Map<String, Object> schema = Json.obj("type", "object", "properties", Json.obj(), "required", List.of("project", "files", "extra"));
        check(PdfAutoFill.optional(schema).get("required").equals(List.of("extra")) && PdfAutoFill.defaults("pdfcontrol_start_compare") && PdfAutoFill.defaults("pdfcontrol_start_pairs") && !PdfAutoFill.defaults("pdfcontrol_job_status"), "schema no longer requires project/files for the start tools");
        String source = Files.readString(Path.of("src/localchat/Tools.java"));
        check(source.contains("PdfAutoFill.fill(ref.tool().name, args") && source.contains("PdfAutoFill.optional(schema)"), "the MCP call path fills defaults and the schema is relaxed");
        // 4) the revision tool with real PDFs, read-only
        Path engine = Files.createDirectories(home.resolve("pdfcontrol"));
        try (Stream<Path> files = Files.list(Path.of(System.getProperty("audit.package")))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".py")).collect(Collectors.toList())) Files.copy(f, engine.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
        check(Files.isRegularFile(engine.resolve("compare_server_revisions.py")), "sync_engine delivers compare_server_revisions.py into the package");
        Path base = home.resolve("rev-fixture");
        Files.createDirectories(base);
        Files.writeString(home.resolve("revisions.py"), REVISIONS, StandardCharsets.UTF_8);
        Process process = new ProcessBuilder(System.getProperty("audit.python"), "-I", home.resolve("revisions.py").toString(), base.toString()).redirectErrorStream(true).start();
        String made = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        check(process.waitFor() == 0, "revision fixtures: " + made);
        Map<Path, String> before = new TreeMap<>();
        try (Stream<Path> all = Files.walk(base)) {
            for (Path f : all.filter(Files::isRegularFile).collect(Collectors.toList())) before.put(f, sha(f));
        }
        List<String> asked = new ArrayList<>();
        Tools cards = tools(home, true, (a, b) -> {asked.add(a); return Tools.Decision.YES;});
        Tools.Result result = revisions(cards, "project", "123456", "local_folder", base.resolve("local").toString(), "server_folder", base.resolve("server").toString());
        check(!result.isError && result.text.contains("NEWER LOCALLY") && result.text.contains("123456_a1_v: local rev 2") && result.text.contains("server rev 1"), "newer locally by file-name revision: " + result.text);
        check(result.text.contains("123456_a2_v: local rev A") && result.text.contains("OLDER LOCALLY") && result.text.contains("server rev C"), "older locally by title-block revision field: " + result.text);
        check(result.text.contains("ONLY LOCAL") && result.text.contains("123456_a4_v") && result.text.contains("ONLY ON SERVER") && result.text.contains("123456_a5_v") && !result.text.contains("rev0"), "only local / only server, OLD excluded: " + result.text);
        Map<Path, String> after = new TreeMap<>();
        try (Stream<Path> all = Files.walk(base)) {
            for (Path f : all.filter(Files::isRegularFile).collect(Collectors.toList())) after.put(f, sha(f));
        }
        check(before.equals(after), "the revision comparison wrote nothing next to the drawings");
        Tools.Result badProject = revisions(cards, "project", "12", "local_folder", base.resolve("local").toString(), "server_folder", base.resolve("server").toString());
        Tools.Result extra = revisions(cards, "project", "123456", "server_folder", base.resolve("server").toString(), "apply", true);
        check(badProject.isError && extra.isError, "invalid project / unknown argument refused");
        // 5) translations of the new texts
        String saved = I18n.language();
        try {
            for (String lang : List.of("nl", "ro", "es", "pl", "en", "tr")) {
                I18n.setLanguage(lang);
                for (String key : List.of("Compara revizii server cu local", "Proiect {0}: doar citire; planurile de pe server se compara cu cele din folderul local dupa numarul planului si revizie. Nimic nu se scrie langa planuri.", "Comparatia a depasit limita de timp. Nimic nu a fost modificat.",
                        "Control plansa: foloseste pdfcontrol_start_check, apoi pdfcontrol_job_summary si pdfcontrol_job_findings (proiectul si PDF-urile se completeaza automat) in loc de OCR sau scripturi PowerShell/Python.",
                        "Revizii server: foloseste pdfcontrol_compare_server_revisions (doar citire, dupa numarul planului si revizie) in loc de scripturi PowerShell/Python.",
                        "PDFCONTROL: proiectul si lista de PDF-uri au fost completate automat (folderul de lucru, toate planurile originale; OUD/NIEUW la comparare).")) {
                    String shown = I18n.text(key, "123456");
                    check(!shown.isBlank() && (lang.equals("ro") ? shown.chars().allMatch(c -> c < 128) : !shown.equals(key.replace("{0}", "123456"))), "new UI text translated, " + lang + ": " + key.substring(0, 20));
                }
                check(I18n.text("Revizii server: foloseste pdfcontrol_compare_server_revisions (doar citire, dupa numarul planului si revizie) in loc de scripturi PowerShell/Python.").contains("pdfcontrol_compare_server_revisions"), "hint keeps the tool name, " + lang);
            }
        } finally {
            I18n.setLanguage(saved);
        }
    }
}
