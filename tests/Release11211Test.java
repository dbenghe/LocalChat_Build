package localchat;

import java.nio.file.*;
import java.util.*;
import javax.swing.*;

/** 1.28.11: scratch folder, hard guard for project folders and servers, server confirmation cards, rules after compaction, stray cleanup. */
public final class Release11211Test {
    static int checks;

    static void check(boolean ok, String why) {
        checks++;
        if (!ok) throw new AssertionError(why);
    }

    static final List<String> asked = new ArrayList<>();

    static Tools tools(Path work, FileGuard guard, boolean autoApprove, Tools.Decision answer) {
        return new Tools(new Tools.Context(work, work.resolve("skills"), work.resolve("rules.md"), work.resolve("settings.properties"), Map.of(), null, new Mcp(), PathMap.none(),
                autoApprove, EnumSet.noneOf(Tools.Access.class), (title, details) -> {
                    asked.add(title + "\n" + details);
                    return answer;
                }), List.of()).guard(guard);
    }

    static Tools.Result run(Tools t, String name, Object... kv) {
        Map<String, Object> args = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) args.put((String) kv[i], kv[i + 1]);
        return t.execute(new ChatClient.ToolCall("c" + checks, name, args));
    }

    static long serverCards() {
        return asked.stream().filter(a -> a.startsWith(FileGuard.COPY_TITLE)).count();
    }

    static Path put(Path p, String text) throws Exception {
        Files.createDirectories(p.getParent());
        return Files.writeString(p, text);
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("localchat.home"))), "r11211-");
        paths(home);
        guardedTools(home);
        serverTools(home);
        shellParsing(home);
        strays(home);
        compaction(home);
        prompt(home);
        hotfolder(home);
        System.out.println("Release11211Test OK, checks=" + checks);
    }

    /** Zones, helper names, allowlist, UNC and mapped drives. */
    static void paths(Path home) throws Exception {
        Path scratch = home.resolve("scratch"), project = home.resolve("PRODUCTIE/123456");
        FileGuard g = FileGuard.of(scratch, List.of(), () -> Set.of('J'));
        for (String bad : List.of("_tmp_soest_probe.py", "probe.py", "run.ps1", "x.bat", "x.cmd", "x.vbs", "x.js", "notes.md", "_x.pdf", "tmp1.csv", "temp.xlsx", "tmp"))
            check(g.refuse(project.resolve(bad), false) != null, "project refuses " + bad);
        for (String ok : List.of("PROJECT_INFO.md", "Pentru_ChatGPT_123456.txt", "Output/a.py", "Controle/notes.md", "Controle/PDFCONTROL/_x.py", "Output/sub/tmp1.csv", "data.csv", "temperature.csv", "Import.xlsx", "Tempel.pdf", "a.pdf"))
            check(g.refuse(project.resolve(ok), false) == null, "project allows " + ok);
        check(g.refuse(project.resolve("_work"), true) != null, "project refuses a _ folder");
        check(g.refuse(project.resolve("Output/_work"), true) == null, "Output may hold any folder");
        check(g.refuse(project.resolve("sub/probe.py"), false) != null, "subfolders are guarded too");
        check(g.project(project.resolve("a.pdf")) && !g.project(home.resolve("elsewhere/a.py")), "project zone detection");
        check(g.refuse(home.resolve("elsewhere/probe.py"), false) == null, "outside any project nothing is refused");
        check(g.refuse(scratch.resolve("probe.py"), false) == null && g.inScratch(scratch.resolve("a/b.py")), "scratch accepts scripts");
        // servers: UNC and mapped drive J:
        for (String root : List.of("\\\\srv\\share\\Projecten\\1", "J:\\Projecten\\1")) {
            Path base = Path.of(root);
            check(g.server(base.resolve("a.csv")), "server zone " + root);
            for (String ok : List.of("a.pdf", "a.xlsx", "a.xls", "a.csv", "a.dwg", "a.dxf", "a.ifc", "a.docx", "a.zip", "Complete Lijst/x.CSV"))
                check(g.refuse(base.resolve(ok), false) == null, "server allows " + ok + " on " + root);
            for (String bad : List.of("a.py", "a.ps1", "notes.md", "a.log", "_x.pdf", "tmp1.csv", "OLD/a.pdf", "old/a.pdf", "a.txt", "a.exe", "a"))
                check(g.refuse(base.resolve(bad), false) != null, "server refuses " + bad + " on " + root);
            check(g.refuse(base.resolve("_work"), true) != null, "server refuses _ folder");
        }
        check(!g.server(Path.of("K:\\x\\a.csv")) && !g.server(home.resolve("a.csv")), "unmapped drive and local path are not servers");
        check(FileGuard.mappedDrives("K, l").containsAll(Set.of('K', 'L')), "configured network drives");
        FileGuard off = FileGuard.disabled();
        check(!off.active() && off.refuse(project.resolve("probe.py"), false) == null && off.networkWrites("echo x > \\\\srv\\s\\a.csv", home).isEmpty(), "disabled guard changes nothing");
        Path s1 = FileGuard.scratchFor("conv 1/../x"), s2 = FileGuard.scratchFor("conv 1/../x"), s3 = FileGuard.scratchFor("other");
        check(s1.equals(s2) && !s1.equals(s3) && s1.startsWith(Path.of(System.getenv("TEMP")).resolve("LocalChat")) && s1.normalize().equals(s1), "scratch per conversation under %TEMP%\\LocalChat, safe name: " + s1);
        check(!s1.startsWith(Path.of("C:\\LocalChat_Build")), "scratch is not in the project");
    }

    /** write/edit/make_dir/copy in a project folder; allow-all and remembered answers never bypass. */
    static void guardedTools(Path home) throws Exception {
        Path scratch = home.resolve("scratch2"), project = Files.createDirectories(home.resolve("PRODUCTIE/654321"));
        FileGuard g = FileGuard.of(scratch, List.of(project), Set::of);
        for (boolean auto : new boolean[]{true, false}) {
            for (Tools.Decision answer : Tools.Decision.values()) {
                asked.clear();
                Tools t = tools(project, g, auto, answer);
                Tools.Result r = run(t, "write_file", "path", project.resolve("_tmp_probe.py").toString(), "content", "print(1)");
                check(r.isError && !Files.exists(project.resolve("_tmp_probe.py")), "write refused auto=" + auto + " answer=" + answer);
                check(asked.isEmpty(), "a refused helper write asks nothing");
                check(r.text.contains(scratch.toString()), "refusal points to the scratch folder");
                check(run(t, "write_file", "path", project.resolve("notes.md").toString(), "content", "x").isError, "md refused");
                check(run(t, "make_dir", "path", project.resolve("tmp_x").toString()).isError, "tmp folder refused");
                check(run(t, "append_file", "path", project.resolve("a.ps1").toString(), "content", "x").isError, "append ps1 refused");
                put(project.resolve("src/a.pdf"), "x");
                check(run(t, "copy_path", "source", project.resolve("src/a.pdf").toString(), "destination", project.resolve("_copy.pdf").toString()).isError, "copy to _ name refused");
                check(run(t, "move_path", "source", project.resolve("src/a.pdf").toString(), "destination", project.resolve("b.py").toString()).isError && Files.exists(project.resolve("src/a.pdf")), "move to .py refused");
            }
        }
        asked.clear();
        Tools t = tools(project, g, true, Tools.Decision.NO);
        check(!run(t, "write_file", "path", project.resolve("Output/r.py").toString(), "content", "x").isError, "Output accepts any file in allow-all");
        check(!run(t, "write_file", "path", project.resolve("PROJECT_INFO.md").toString(), "content", "x").isError, "PROJECT_INFO.md allowed");
        check(!run(t, "write_file", "path", project.resolve("Pentru_ChatGPT_654321.txt").toString(), "content", "x").isError, "Pentru_ChatGPT allowed");
        check(!run(t, "write_file", "path", project.resolve("Controle/n.md").toString(), "content", "x").isError, "Controle accepts any file");
        check(!run(t, "write_file", "path", project.resolve("result.csv").toString(), "content", "x").isError, "normal deliverable allowed");
        check(asked.isEmpty(), "allow-all asks nothing locally");
        // scratch: no card even without allow-all; blocked in read-only mode
        asked.clear();
        Tools strict = tools(project, g, false, Tools.Decision.NO);
        Path script = scratch.resolve("probe.py");
        check(!run(strict, "write_file", "path", script.toString(), "content", "print('hi')").isError && Files.exists(script) && asked.isEmpty(), "scratch write needs no card");
        check(!run(strict, "read_file", "path", script.toString()).isError && asked.isEmpty(), "scratch read is free");
        check(run(tools(project, g, true, Tools.Decision.YES).readOnly(true), "write_file", "path", scratch.resolve("x.py").toString(), "content", "x").isError, "read-only mode blocks even scratch");
        // cwd = project for a script in scratch
        Tools open = tools(project, g, true, Tools.Decision.YES);
        Tools.Result r = run(open, "run_command", "command", "cd");
        check(!r.isError && r.text.contains(project.getFileName().toString()), "run_command defaults to the project as cwd");
        Tools.Result env = run(open, "run_command", "command", "echo %LOCALCHAT_SCRATCH%");
        check(env.text.contains(scratch.toString()), "LOCALCHAT_SCRATCH points to scratch: " + env.text);
        // run_command that writes a helper into the project is refused before it runs
        asked.clear();
        Tools.Result w = run(open, "run_command", "command", "echo print(1) > _probe.py");
        check(w.isError && !Files.exists(project.resolve("_probe.py")), "redirect to a helper file is refused");
        check(run(open, "run_command", "command", "powershell -Command \"Set-Content -Path _x.ps1 -Value 1\"").isError, "Set-Content helper refused");
        check(run(open, "run_command", "command", "powershell -Command \"'x' | Out-File tmp_a.txt\"").isError, "Out-File tmp refused");
        check(run(open, "run_command", "command", "copy src\\a.pdf probe.py").isError, "copy to helper refused");
        check(run(open, "run_command", "command", "robocopy src . probe.py").isError || !Files.exists(project.resolve("probe.py")), "robocopy of a helper into the project does not land");
        check(!run(open, "run_command", "command", "echo ok > Output\\a.txt").isError && Files.exists(project.resolve("Output/a.txt")), "redirect into Output allowed");
        check(!run(open, "run_command", "command", "echo 1 > nul").isError, "nul redirect fine");
        // multiline python error points to scratch
        Tools.Result ml = run(open, "run_command", "command", "python -c \"import os\nprint(1)\"");
        check(ml.isError && ml.text.contains(scratch.toString()) && ml.text.contains(".py"), "multiline python error names the scratch folder: " + ml.text);
        // stray note after a command that leaves a helper (undetectable beforehand)
        Tools.Result stray = run(open, "run_command", "command", "cmd /c ren Output\\a.txt a.txt >nul & copy /y nul \"" + project + "\\left.txt\" >nul");
        check(!stray.isError, "ordinary command still runs");
    }

    /** Server (network) writes: new files and copies go without a card (1.28.13); overwrite, edit, append, move, delete and scripts always ask, never remembered. */
    static void serverTools(Path home) throws Exception {
        Path scratch = home.resolve("scratch3"), server = Files.createDirectories(home.resolve("server/Projecten/1")), local = Files.createDirectories(home.resolve("local"));
        char drive = server.getRoot().toString().charAt(0);
        FileGuard g = FileGuard.of(scratch, List.of(), () -> Set.of(Character.toUpperCase(drive)));
        check(g.server(server.resolve("a.csv")) && !g.server(scratch.resolve("a.csv")), "test server zone");
        for (Tools.Decision remembered : new Tools.Decision[]{Tools.Decision.YES, Tools.Decision.ALWAYS}) {
            asked.clear();
            Tools t = tools(local, g, true, remembered);
            Path f = server.resolve("data.csv");
            Files.deleteIfExists(f);
            Tools.Result r = run(t, "write_file", "path", f.toString(), "content", "a;b\n1;2\n");
            check(!r.isError && Files.exists(f) && serverCards() == 0, "1.28.13: new server file needs no card in allow-all: " + remembered);
            check(!run(t, "write_file", "path", f.toString(), "content", "a;b\n3;4\n").isError && serverCards() == 1, "overwrite asks, answer " + remembered + " is not remembered");
            check(asked.get(0).contains(f.toString()) && asked.get(0).contains("csv x1"), "card lists the file and extension");
            check(!run(t, "edit_file", "path", f.toString(), "old_text", "3;4", "new_text", "5;6").isError && serverCards() == 2 && Files.readString(f).contains("5;6"), "edit asks");
            check(!run(t, "append_file", "path", f.toString(), "content", "7;8\n").isError && serverCards() == 3, "append asks");
            check(asked.stream().noneMatch(a -> a.startsWith("Suprascriere") || a.startsWith("Modificare") && !a.startsWith(FileGuard.COPY_TITLE)), "no generic card for server writes");
        }
        asked.clear();
        Tools no = tools(local, g, true, Tools.Decision.NO);
        Path f = server.resolve("data.csv");
        String before = Files.readString(f);
        check(run(no, "write_file", "path", f.toString(), "content", "zzz").isError && Files.readString(f).equals(before), "refused card leaves the file unchanged");
        check(run(no, "edit_file", "path", f.toString(), "old_text", "5;6", "new_text", "9;9").isError && Files.readString(f).equals(before), "refused edit unchanged");
        Tools yes = tools(local, g, true, Tools.Decision.YES);
        asked.clear();
        for (String bad : List.of("run.py", "notes.md", "x.ps1", "a.log", "_x.csv", "tmp1.csv", "OLD/a.csv", "a.txt")) {
            check(run(yes, "write_file", "path", server.resolve(bad).toString(), "content", "x").isError && !Files.exists(server.resolve(bad)), "server refuses " + bad);
        }
        check(asked.isEmpty(), "refusals show no card");
        check(run(yes, "make_dir", "path", server.resolve("_scratch").toString()).isError, "server refuses _ folder");
        // whole-folder copy with .py/.md among production files
        Path src = local.resolve("batch");
        put(src.resolve("a.csv"), "1");
        put(src.resolve("b.pdf"), "2");
        put(src.resolve("c.py"), "print(1)");
        put(src.resolve("README.md"), "x");
        put(src.resolve("sub/d.xlsx"), "3");
        put(src.resolve("sub/_tmp.csv"), "4");
        asked.clear();
        Tools.Result copy = run(yes, "copy_path", "source", src.toString(), "destination", server.resolve("batch").toString());
        check(!copy.isError && serverCards() == 0 && asked.isEmpty(), "folder copy of new production files: no card, " + copy.text);
        check(Files.exists(server.resolve("batch/a.csv")) && Files.exists(server.resolve("batch/sub/d.xlsx")), "production files copied");
        check(!Files.exists(server.resolve("batch/c.py")) && !Files.exists(server.resolve("batch/README.md")) && !Files.exists(server.resolve("batch/sub/_tmp.csv")), "helper files stay out of the server");
        check(copy.text.contains("c.py") || copy.text.contains("README.md"), "result tells what was skipped: " + copy.text);
        // a folder holding only helper files is refused
        Path only = local.resolve("helpers");
        put(only.resolve("a.py"), "x");
        put(only.resolve("b.md"), "x");
        asked.clear();
        check(run(yes, "copy_path", "source", only.toString(), "destination", server.resolve("helpers").toString()).isError && asked.isEmpty() && !Files.exists(server.resolve("helpers")), "folder of only helpers refused");
        // moving a folder with omitted items is an error, nothing moves
        asked.clear();
        check(run(yes, "move_path", "source", src.toString(), "destination", server.resolve("moved").toString()).isError && Files.exists(src.resolve("c.py")), "move with helper files is refused");
        // single file copy with a .py extension
        put(local.resolve("one.py"), "x");
        check(run(yes, "copy_path", "source", local.resolve("one.py").toString(), "destination", server.resolve("one.py").toString()).isError, "py copy refused");
        check(run(yes, "copy_path", "source", local.resolve("one.py").toString(), "destination", server.resolve("one.csv").toString()).isError == false, "renaming does not matter: only the target name counts, and the card lists it");
        // ---- shell: anything that may write on the server asks, always ----
        Path cwd = Files.createDirectories(scratch);
        String sp = server.toString(), data = sp + "\\data.csv";
        String[] writes = {
                "powershell -Command \"[IO.File]::WriteAllText('" + data + "', 'x')\"",
                "powershell -Command \"$t=[IO.File]::ReadAllText('" + data + "'); $t -replace 'a','b' | Set-Content -Path '" + data + "'\"",
                "powershell -Command \"Add-Content '" + data + "' 'x'\"",
                "powershell -Command \"'x' | Out-File '" + data + "'\"",
                "powershell -Command \"Move-Item '" + data + "' '" + sp + "\\moved.csv'\"",
                "powershell -Command \"Remove-Item '" + data + "'\"",
                "powershell -Command \"[System.IO.File]::WriteAllLines(\\\"" + data + "\\\", @('x'))\"",
                "del \"" + data + "\"", "erase \"" + data + "\"", "move \"" + data + "\" \"" + sp + "\\m2.csv\"",
                "copy \"" + local.resolve("batch/a.csv") + "\" \"" + data + "\"", "echo x > \"" + data + "\"", "echo x >> \"" + data + "\"",
                "type nul > \"" + sp.replace('\\', '/') + "/slash.csv\"", "ren \"" + data + "\" renamed.csv",
                "python -c \"open(r'" + data + "','w').write('x')\"", "python -c \"import shutil; shutil.copy('a', r'" + data + "')\"",
                "python -c \"open('" + data.replace("\\", "\\\\") + "','w')\""};
        String keep = Files.readString(f);
        for (String command : writes) {
            asked.clear();
            Tools t = tools(local, g, true, Tools.Decision.NO);
            Tools.Result r = run(t, "run_command", "command", command, "cwd", cwd.toString());
            check(r.isError && serverCards() == 1 && Files.readString(f).equals(keep) && Files.exists(f), "card + refusal, nothing ran (changes/deletes existing data or a script): " + command);
        }
        // 1.28.13: plain copies of new files to the server run WITHOUT a card (no existing file is touched)
        asked.clear();
        Path fresh = put(local.resolve("fresh/new1.csv"), "n1");
        check(!run(yes, "run_command", "command", "copy \"" + fresh + "\" \"" + sp + "\\copy.csv\"", "cwd", cwd.toString()).isError && Files.exists(server.resolve("copy.csv")) && serverCards() == 0, "shell copy of a new file: no card");
        check(!run(yes, "run_command", "command", "powershell -Command \"Copy-Item '" + fresh + "' '" + sp + "\\copy2.csv'\"", "cwd", cwd.toString()).isError && Files.exists(server.resolve("copy2.csv")) && serverCards() == 0, "Copy-Item of a new file: no card");
        run(yes, "run_command", "command", "xcopy \"" + fresh + "\" \"" + sp + "\\x2\\\" /y", "cwd", cwd.toString());
        run(yes, "run_command", "command", "robocopy \"" + local.resolve("fresh") + "\" \"" + sp + "\\r2\"", "cwd", cwd.toString());
        check(Files.exists(server.resolve("x2/new1.csv")) && Files.exists(server.resolve("r2/new1.csv")) && serverCards() == 0, "xcopy/robocopy to new targets: no card");
        // ... but a copy that would replace an existing server file asks, and nothing is replaced when refused
        asked.clear();
        check(run(no, "run_command", "command", "copy /y \"" + fresh + "\" \"" + sp + "\\copy.csv\"", "cwd", cwd.toString()).isError && serverCards() == 1 && Files.readString(server.resolve("copy.csv")).equals("n1"), "shell copy over an existing server file asks");
        // 'Da mereu' (ALWAYS) answered for a real write: the next write asks again
        asked.clear();
        Tools always = tools(local, g, true, Tools.Decision.ALWAYS);
        String echo = "echo y > \"" + server.resolve("ok.csv") + "\"";
        check(!run(always, "run_command", "command", echo, "cwd", cwd.toString()).isError && Files.exists(server.resolve("ok.csv")) && serverCards() == 1, "answer Permite lets the command run");
        check(!run(always, "run_command", "command", echo, "cwd", cwd.toString()).isError && serverCards() == 2, "Da mereu does not bypass the second time");
        // reads never ask
        asked.clear();
        for (String read : List.of("type \"" + data + "\"", "dir \"" + sp + "\"", "powershell -Command \"Get-Content '" + data + "' | Select-Object -First 2\"", "python -c \"print(open(r'" + data + "').read())\"", "findstr x \"" + data + "\" 2>&1"))
            run(yes, "run_command", "command", read, "cwd", cwd.toString());
        check(serverCards() == 0, "read-only server commands ask nothing: " + asked);
        // scripts: the text of a .py/.ps1 run by run_command is scanned
        Path py = put(scratch.resolve("fix.py"), "import csv\nwith open(r'" + data + "', 'w') as f:\n    f.write('x')\n");
        asked.clear();
        check(run(no, "run_command", "command", "python \"" + py + "\"", "cwd", cwd.toString()).isError && serverCards() == 1 && asked.get(0).contains(data), "python script text with a server path and open(w) asks, listing the target");
        Path ps = put(scratch.resolve("fix.ps1"), "$p = '" + data + "'\n$t = [IO.File]::ReadAllText($p)\n[IO.File]::WriteAllText($p, $t.Replace('a','b'))\n");
        asked.clear();
        check(run(no, "run_command", "command", "powershell -ExecutionPolicy Bypass -File \"" + ps + "\"", "cwd", cwd.toString()).isError && serverCards() == 1, "ps1 script text is scanned");
        Path harmless = put(scratch.resolve("read.py"), "print(open(r'" + data + "').read())\n");
        asked.clear();
        run(yes, "run_command", "command", "python \"" + harmless + "\"", "cwd", cwd.toString());
        check(serverCards() == 0, "a read-only script asks nothing");
        // cwd on the server with a relative write
        Path inServer = Files.createDirectories(server.resolve("cwd"));
        asked.clear();
        check(run(no, "run_command", "command", "echo x > rel.csv", "cwd", inServer.toString()).isError && serverCards() == 1 && !Files.exists(inServer.resolve("rel.csv")), "relative write with the server as cwd asks");
        // folder listing in the card
        asked.clear();
        run(yes, "run_command", "command", "powershell -Command \"Copy-Item '" + server.resolve("batch") + "' '" + server.resolve("Complete Lijst") + "' -Recurse\"", "cwd", cwd.toString());
        check(asked.isEmpty() && Files.exists(server.resolve("Complete Lijst/a.csv")), "a folder copy to a new target asks nothing: " + asked);
        run(no, "run_command", "command", "powershell -Command \"Copy-Item '" + server.resolve("batch") + "' '" + server.resolve("Complete Lijst") + "' -Recurse -Force\"", "cwd", cwd.toString());
        check(asked.size() == 1 && asked.get(0).contains("a.csv") && asked.get(0).contains("d.xlsx"), "a folder copy that would replace existing files lists them in the card: " + asked);
        // read-only mode: no card, blocked
        Tools ro = tools(local, g, true, Tools.Decision.YES).readOnly(true);
        asked.clear();
        check(run(ro, "run_command", "command", echo, "cwd", cwd.toString()).isError && !asked.stream().anyMatch(a -> a.startsWith(FileGuard.COPY_TITLE)), "read-only mode blocks without a card");
        check(FileGuard.confirmOnly(FileGuard.COPY_TITLE) && FileGuard.confirmOnly(FileGuard.CLEAN_TITLE) && !FileGuard.confirmOnly("Rulare comanda"), "confirm-only titles");
    }

    static void shellParsing(Path home) throws Exception {
        List<FileGuard.Op> ops = FileGuard.parse("echo x > _a.py && copy a.csv J:\\srv\\b.csv & powershell -Command \"Set-Content -Path c.ps1 -Value 1; Copy-Item -Path a -Destination d\"");
        check(ops.stream().anyMatch(o -> o.kind() == FileGuard.Kind.WRITE && "_a.py".equals(o.dest())), "parse redirect");
        check(ops.stream().anyMatch(o -> o.kind() == FileGuard.Kind.COPY && "J:\\srv\\b.csv".equals(o.dest())), "parse copy");
        check(ops.stream().anyMatch(o -> o.kind() == FileGuard.Kind.WRITE && "c.ps1".equals(o.dest())), "parse nested Set-Content");
        check(ops.stream().anyMatch(o -> o.kind() == FileGuard.Kind.COPY && "d".equals(o.dest())), "parse Copy-Item -Destination");
        check(FileGuard.parse("dir C:\\x 2>&1 | findstr a > nul").isEmpty(), "reads and nul redirects produce no operation");
        check(FileGuard.parse("robocopy a b /MIR").stream().anyMatch(FileGuard.Op::mirror), "robocopy /MIR flagged");
        FileGuard g = FileGuard.of(home.resolve("s"), List.of(), () -> Set.of('J'));
        check(!g.networkWrites("Remove-Item J:\\Projecten\\x\\Import -Recurse", home).isEmpty(), "J: Remove-Item found");
        check(g.networkWrites("Get-Content J:\\Projecten\\x\\a.csv | Select -First 3", home).isEmpty(), "J: read is not a write");
        check(!g.networkWrites("python -c \"open('J:\\\\Projecten\\\\a.csv','w')\"", home).isEmpty(), "escaped backslashes in python text");
        check(!g.networkWrites("echo x > \\\\srv\\share\\a.csv", home).isEmpty() && !g.networkWrites("echo x > //srv/share/a.csv", home).isEmpty(), "UNC with both slash styles");
        check(g.networkWrites("echo x > K:\\a.csv", home).isEmpty() && g.networkWrites("echo x > a.csv", home).isEmpty(), "local writes are not server writes");
        check(g.networkWrites("echo http://example.com/a > a.txt", home).isEmpty(), "URLs are not server paths");
    }

    static void strays(Path home) throws Exception {
        Path scratch = home.resolve("scratch4"), project = Files.createDirectories(home.resolve("PRODUCTIE/777777"));
        FileGuard g = FileGuard.of(scratch, List.of(project), Set::of);
        put(project.resolve("_tmp_soest_probe.py"), "x");
        put(project.resolve("tmp_a.txt"), "x");
        put(project.resolve("notes.md"), "x");
        put(project.resolve("sub/run.ps1"), "x");
        put(project.resolve("PROJECT_INFO.md"), "keep");
        put(project.resolve("Pentru_ChatGPT_777777.txt"), "keep");
        put(project.resolve("Output/helper.py"), "keep");
        put(project.resolve("Controle/_x.md"), "keep");
        put(project.resolve("real.pdf"), "keep");
        put(project.resolve("Import.xlsx"), "keep");
        check(g.strays(project, 3).size() == 4, "strays found: " + g.strays(project, 3));
        asked.clear();
        Tools listing = tools(project, g, true, Tools.Decision.NO);
        Tools.Result list = run(listing, "list_stray_files", "path", project.toString());
        check(!list.isError && list.text.contains("_tmp_soest_probe.py") && !list.text.contains("PROJECT_INFO.md") && !list.text.contains("helper.py") && asked.isEmpty(), "list names strays only, asks nothing: " + list.text);
        check(run(listing, "cleanup_stray_files", "path", project.toString()).isError && Files.exists(project.resolve("_tmp_soest_probe.py")), "refused card deletes nothing");
        check(asked.size() == 1 && asked.get(0).startsWith(FileGuard.CLEAN_TITLE) && asked.get(0).contains("_tmp_soest_probe.py"), "cleanup asks with the list");
        Tools yes = tools(project, g, true, Tools.Decision.ALWAYS);
        asked.clear();
        Tools.Result gone = run(yes, "cleanup_stray_files", "path", project.toString());
        check(!gone.isError && asked.size() == 1, "cleanup asks even in allow-all: " + gone.text);
        for (String p : List.of("_tmp_soest_probe.py", "tmp_a.txt", "notes.md", "sub/run.ps1")) check(!Files.exists(project.resolve(p)), "deleted " + p);
        for (String p : List.of("PROJECT_INFO.md", "Pentru_ChatGPT_777777.txt", "Output/helper.py", "Controle/_x.md", "real.pdf", "Import.xlsx")) check(Files.exists(project.resolve(p)), "never deleted " + p);
        asked.clear();
        run(yes, "cleanup_stray_files", "path", project.toString());
        check(asked.isEmpty(), "nothing to clean asks nothing");
        check(run(yes, "cleanup_stray_files", "path", home.toString()).isError, "not a project folder");
        // pattern limits the deletion; allowlisted files never match
        put(project.resolve("_a.py"), "x");
        put(project.resolve("_b.py"), "x");
        run(yes, "cleanup_stray_files", "path", project.toString(), "pattern", "_a.py");
        check(!Files.exists(project.resolve("_a.py")) && Files.exists(project.resolve("_b.py")), "pattern restricts the deletion");
        run(yes, "cleanup_stray_files", "path", project.toString(), "pattern", "PROJECT_INFO.md");
        check(Files.exists(project.resolve("PROJECT_INFO.md")), "pattern cannot reach an allowlisted file");
    }

    /** Rules and skill constraints survive a compaction; read_skill works again afterwards. */
    static void compaction(Path home) throws Exception {
        check(ContextGuard.KEEP_RULES.contains("scratch") && ContextGuard.KEEP_RULES.contains("skills") && ContextGuard.KEEP_RULES.contains("verbatim"), "keep-rules text");
        try (ContextGuardTest.Server server = new ContextGuardTest.Server(null)) {
            ContextGuardTest.Ui ui = new ContextGuardTest.Ui();
            ContextGuard guard = new ContextGuard(8192, 1024, true, 70, 1.25);
            check(guard.compactions == 0, "no compaction yet");
            guard.prepare(Provider.CUSTOM, server.url(), "", "fixture", "system", ContextGuardTest.material(), List.of(), 1, ui);
            check(guard.compactions == 1, "compaction counted");
            String summaryRequest = Json.write(server.requests.stream().filter(r -> Objects.toString(Json.str(r, "messages", 0, "content"), "").contains("Summarize historical conversation")).findFirst().orElseThrow());
            check(summaryRequest.contains("Preserve verbatim") && summaryRequest.contains("scratch folder") && summaryRequest.contains("skills already read"), "the summarizer is told to keep the rules and skill constraints");
        }
        ToolLoopGuard loop = new ToolLoopGuard();
        ChatClient.ToolCall skill = new ChatClient.ToolCall("s1", "read_skill", Map.of("name", "pdfcontrol-octop"));
        check(loop.before(skill) == null, "first read_skill runs");
        loop.completed(skill, Tools.Result.ok("full skill text"));
        Tools.Result again = loop.before(new ChatClient.ToolCall("s2", "read_skill", Map.of("name", "pdfcontrol-octop")));
        check(again != null && !again.isError, "second read_skill is answered from memory before a compaction");
        loop.afterCompaction();
        check(loop.before(new ChatClient.ToolCall("s3", "read_skill", Map.of("name", "pdfcontrol-octop"))) == null, "after a compaction read_skill returns the full text again");
        ChatClient.ToolCall list = new ChatClient.ToolCall("l1", "list_stray_files", Map.of("path", "."));
        check(loop.before(list) == null, "list_stray_files is an ordinary read");
    }

    /** The system prompt carries the hard rules in every UI language; the compaction reminder is added after a compaction. */
    static void prompt(Path home) throws Exception {
        LocalChat[] app = {null};
        FeatureTest.edt(() -> {
            try {
                app[0] = AttachmentTest.window(home, "http://127.0.0.1:9/v1");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        String saved = I18n.language();
        try {
            for (String lang : List.of("nl", "ro", "es", "pl", "en", "tr")) {
                String[] text = {null};
                FeatureTest.edt(() -> {
                    try {
                        I18n.setLanguage(lang);
                        text[0] = RuleCoverageTest.prompt(app[0], home);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                check(text[0].contains("Hard rules") && text[0].contains("scratch folder") && text[0].contains("Output or Controle") && text[0].contains("without a question"), "hard rules in the prompt, language " + lang);
                check(text[0].contains(FeatureTest.<Object>call(app[0], "scratchDir", new Class<?>[]{}).toString()), "prompt names the scratch folder, " + lang);
                check(text[0].contains("after a compaction or a cut history call read_skill again"), "read_skill wording, " + lang);
                for (String key : List.of("Modificare pe server: confirmare necesara", "Curatare fisiere ajutatoare: confirmare necesara", "Comanda atinge o cale de server (analiza aproximativa, deci confirmarea listeaza tot ce atinge calea):\n{0}"))
                    check(I18n.contains(key), "catalog has " + key + " for " + lang);
            }
        } finally {
            I18n.setLanguage(saved);
        }
        for (String code : List.of("nl", "es", "pl", "en", "tr"))
            check(I18n.keys(code).size() == I18n.keys("ro").size(), "catalog " + code + " has as many keys as ro");
        for (String s : I18n.keys("ro")) if (s.startsWith("Scriere pe server") || s.startsWith("Modificare pe server") || s.startsWith("Completeaza plasele") || s.startsWith("Plase pereche") || s.startsWith("Curatare") || s.startsWith("Fisier ajutator") || s.startsWith("Pe server nu se scrie") || s.startsWith("Comanda atinge")) check(s.chars().allMatch(c -> c < 0x100), "new ro text without diacritics: " + s);
    }

    static void hotfolder(Path home) throws Exception {
        Path root = Files.createDirectories(home.resolve("hot/PRODUCTIE"));
        Hotfolder watcher = new Hotfolder(home.resolve("hot/state.json"), 30);
        put(root.resolve("507045/first.msg"), "old");
        check(watcher.scan(root, 0).isEmpty(), "inventory");
        put(root.resolve("507045/Output/result.xlsx"), "generated");
        put(root.resolve("507045/Output/_probe.py"), "x");
        put(root.resolve("507045/_tmp_probe.py"), "x");
        check(watcher.scan(root, 10).isEmpty() && watcher.scan(root, 50).isEmpty() && watcher.scan(root, 90).isEmpty(), "Output, helper files and probes raise no notification");
        put(root.resolve("507045/Controle/Atasamente/new.eml"), "source");
        watcher.scan(root, 100);
        check(watcher.scan(root, 140).size() == 1, "source attachments inside Controle are still monitored");
        check(Hotfolder.EXCLUDED.contains("output") && !Hotfolder.EXCLUDED.contains("controle"), "Output excluded, Controle not");
    }
}