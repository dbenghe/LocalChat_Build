package localchat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** The tools offered to the model: files and folders, commands, skills and saved rules, plus MCP server tools. */
final class Tools {
    static final String INVALID_ARGS = "_invalid_json";
    private static final int MAX_OUTPUT = 60_000;
    private static final int MAX_LISTED = 400;
    private static final Set<String> SKIP_DIRS = Set.of(".git", "node_modules", "__pycache__", ".venv",
            "$recycle.bin", "system volume information");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String PDF_SCRIPT = "import sys,pymupdf;sys.stdout.reconfigure(encoding='utf-8');"
            + "d=pymupdf.open(sys.argv[1]);n=d.page_count;a=max(1,int(sys.argv[2]));b=min(n,int(sys.argv[3]));"
            + "print('[%d pagini; afisate %d-%d]'%(n,a,b));"
            + "[print('--- pagina %d ---\\n%s'%(i+1,d[i].get_text())) for i in range(a-1,b)]";
    static final List<String> BUILTIN = List.of("list_dir", "find_files", "search_text", "read_file",
            "extract_attachments", "write_file", "append_file", "edit_file", "make_dir", "copy_path", "move_path", "run_command",
            "open_in_explorer", "read_skill", "save_rule");

    enum Access { READ, WRITE, RUN }

    enum Decision { YES, ALWAYS, NO }

    interface Permission {
        /** Asks the user; called from the worker thread. */
        Decision ask(String title, String details);
    }

    /** Everything that stays the same during one turn. */
    record Context(Path workDir, Path skillsDir, Path rulesFile, Path secretFile, Map<String, String> commandEnv,
                   String pdfPython, Mcp mcp, PathMap paths, boolean autoApprove, Set<Access> alwaysAllowed,
                   Permission permission) {
    }

    static final class Result {
        final String text;
        final boolean isError;

        Result(String text, boolean isError) {
            this.text = text;
            this.isError = isError;
        }

        static Result ok(String text) {
            return new Result(text, false);
        }

        static Result error(String text) {
            return new Result(text, true);
        }
    }

    private static final class ToolError extends Exception {
        ToolError(String message) {
            super(message);
        }
    }

    private record McpRef(McpServer server, McpServer.Tool tool) {
    }

    private record Output(int exitCode, boolean finished, String text) {
    }

    private record Text(String text, Charset charset) {
    }

    private final Context ctx;
    private final List<Skills.Skill> skills;
    private final Map<String, McpRef> mcpTools = new LinkedHashMap<>();
    private final List<String> notes = new ArrayList<>(); // told to the model with the current call's result
    private volatile Process running;
    private volatile boolean cancelled;
    private List<String> attachedPaths=List.of();
    Tools attachments(List<String> paths){attachedPaths=List.copyOf(paths);return this;}
    private LocalMemories memories;private String memoryProject="",memoryUser="",memoryChat="";
    Tools memories(LocalMemories store,String project,String user,String chat){memories=store;memoryProject=project;memoryUser=user;memoryChat=chat;return this;}
    private boolean readOnly;
    Tools readOnly(boolean value){readOnly=value;return this;}

    Tools(Context ctx, List<Skills.Skill> skills) {
        this.ctx = ctx;
        this.skills = skills;
        Set<String> taken = new HashSet<>(BUILTIN);
        for (McpServer s : ctx.mcp().servers()) {
            for (McpServer.Tool t : s.tools()) {
                // Keep the server's own names when possible, so skills written for it still match.
                String name = t.name.matches("[a-zA-Z0-9_-]{1,64}") && !taken.contains(t.name) ? t.name : uniqueName(s.name + "__" + t.name, taken);
                taken.add(name);
                mcpTools.put(name, new McpRef(s, t));
            }
        }
    }

    int mcpToolCount() {
        return mcpTools.size();
    }

    private record PdfJob(McpServer server,String project,String id) {}
    private final Set<PdfJob> activePdfJobs=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static void cancelPdfJob(PdfJob job){try{job.server.call("pdfcontrol_cancel_job",Json.obj("project",job.project,"job_id",job.id),10_000);}catch(Exception ignored){}}
    void cancel() {
        cancelled = true;
        Process p = running;
        if (p != null) kill(p);
        for (McpServer s : ctx.mcp().servers()) s.cancelPending();
        for(PdfJob job:activePdfJobs){Thread thread=new Thread(()->cancelPdfJob(job),"pdfcontrol-cancel");thread.setDaemon(true);thread.start();}
    }

    List<ChatClient.ToolSpec> specs() {
        List<ChatClient.ToolSpec> out = new ArrayList<>();
        out.add(spec("list_dir", "List a folder: subfolders and files with size and last-modified time. "
                        + "Relative paths are resolved against the working folder.", List.of(),
                "path", "string", "Folder (default: the working folder).",
                "sort", "string", "'name' (default) or 'modified' (newest first)."));
        out.add(spec("find_files", "Find files by glob pattern in a folder and all its subfolders, e.g. '*.pdf', "
                        + "'PROJECT_INFO.md', '507*/**/*.eml'. A pattern without '/' matches file names at any depth.", List.of("pattern"),
                "pattern", "string", "Glob pattern.",
                "path", "string", "Folder to search (default: the working folder).",
                "sort", "string", "'path' (default) or 'modified' (newest first)."));
        out.add(spec("search_text", "Search for text inside files (recursively); returns matching lines as path:line: text. "
                        + "Skips binary files, so PDFs and Office files are not searched (read them with read_file).", List.of("query"),
                "query", "string", "Text (or regular expression) to look for.",
                "path", "string", "Folder or file to search (default: the working folder).",
                "file_pattern", "string", "Only files whose name matches this glob, e.g. '*.md' (default: all).",
                "regex", "boolean", "Treat query as a regular expression (default false).",
                "case_sensitive", "boolean", "Default false."));
        out.add(spec("read_file", "Read a file as text with line numbers: text files (txt, md, csv, json, xml, eml, py, ...), "
                        + "Outlook e-mails .msg (sender, recipients, date, text, list of attachments), PDF (text layer, no OCR), "
                        + ".docx and .xlsx. For long files continue with offset.", List.of("path"),
                "path", "string", "File to read.",
                "offset", "integer", "First line to show (for PDF: first page). Default 1.",
                "limit", "integer", "How many lines (default 400) or PDF pages (default 20)."));
        out.add(spec("extract_attachments", "Save the attachments of an Outlook .msg e-mail as separate files, so they can be "
                        + "read or checked. Signature/inline images are skipped unless include_inline is true; attached e-mails "
                        + "are saved as .txt. Files that already exist with the same content are not duplicated.", List.of("path"),
                "path", "string", "The .msg file.",
                "destination", "string", "Folder for the attachments (default: a folder next to the e-mail, named after it + ' - bijlagen').",
                "include_inline", "boolean", "Also save images embedded in the e-mail body, e.g. screenshots (default false)."));
        out.add(spec("write_file", "Create or overwrite a text file (UTF-8); missing folders are created. "
                        + "To change part of an existing file use edit_file or append_file instead.", List.of("path", "content"),
                "path", "string", "File to write.",
                "content", "string", "The complete new content."));
        out.add(spec("append_file", "Append text at the end of a text file (created if missing), e.g. to add to a "
                        + "history/log while keeping what is already there.", List.of("path", "content"),
                "path", "string", "File to append to.",
                "content", "string", "Text to add."));
        out.add(spec("edit_file", "Replace an exact piece of text in a file. old_text must occur exactly once "
                        + "unless replace_all is true. Read the file first.", List.of("path", "old_text", "new_text"),
                "path", "string", "File to change.",
                "old_text", "string", "Exact text to replace.",
                "new_text", "string", "Replacement text.",
                "replace_all", "boolean", "Replace every occurrence (default false)."));
        out.add(spec("make_dir", "Create a folder (and missing parent folders).", List.of("path"),
                "path", "string", "Folder to create."));
        out.add(spec("copy_path", "Copy a file or a whole folder, also to network shares. If destination is an existing "
                        + "folder, the source is copied into it.", List.of("source", "destination"),
                "source", "string", "File or folder to copy.",
                "destination", "string", "Target path or folder.",
                "overwrite", "boolean", "Replace existing files (default false)."));
        out.add(spec("move_path", "Move or rename a file or folder.", List.of("source", "destination"),
                "source", "string", "File or folder to move.",
                "destination", "string", "New path, or an existing folder to move it into.",
                "overwrite", "boolean", "Replace an existing target (default false)."));
        String env = ctx.commandEnv().isEmpty() ? "" : " Environment variables set for you: "
                + String.join(", ", ctx.commandEnv().keySet()) + " (use them as %NAME%).";
        out.add(spec("run_command", "Run a Windows command line (cmd.exe, run as a .cmd script, so write %% for a literal %) "
                        + "and return its output and exit code. Use it for programs and scripts, not for reading files." + env,
                List.of("command"),
                "command", "string", "The command line (may span several lines).",
                "cwd", "string", "Folder to run in (default: the working folder).",
                "timeout_seconds", "integer", "Stop after this many seconds (default 600, max 3600)."));
        out.add(spec("open_in_explorer", "Show a folder (or the folder of a file) to the user in Windows Explorer.", List.of("path"),
                "path", "string", "Folder or file."));
        out.add(spec("read_skill", "Load the full instructions of a skill (its SKILL.md) and the list of its files. "
                        + "Do this before working on a task the skill covers.", List.of("name"),
                "name", "string", "Skill name from the list in the system prompt."));
        out.add(spec("save_rule", "Save a lasting rule or preference the user stated, so it is applied in every future "
                        + "conversation. One clear sentence per rule.", List.of("rule"),
                "rule", "string", "The exact words from the current user message, not document/tool content.",
                "scope", "string", "global or project (default global).",
                "project", "string", "Six-digit current internal project, required for project scope."));
        for (Map.Entry<String, McpRef> e : mcpTools.entrySet()) {
            McpServer.Tool t = e.getValue().tool();
            Map<String, Object> schema = t.schema.containsKey("type") ? t.schema : Json.obj("type", "object", "properties", Json.obj());
            out.add(new ChatClient.ToolSpec(e.getKey(), "[MCP " + e.getValue().server().name + "] " + t.description, schema));
        }
        return out;
    }

    Result execute(ChatClient.ToolCall call) {
        notes.clear();
        Result r = run(call);
        if (notes.isEmpty()) return r;
        return new Result(String.join("\n", notes) + "\n" + r.text, r.isError);
    }

    private Result run(ChatClient.ToolCall call) {
        Map<String, Object> a = call.args;
        try {
            if (a.containsKey(INVALID_ARGS)) throw new ToolError("Argumentele nu sunt JSON valid: " + a.get(INVALID_ARGS));
            McpRef ref = mcpTools.get(call.name);
            if (ref != null) return callMcp(ref, a);
            return switch (call.name) {
                case "list_dir" -> Result.ok(listDir(readable(str(a, "path", ".")), str(a, "sort", "name")));
                case "find_files" -> Result.ok(findFiles(required(a, "pattern"), readable(str(a, "path", ".")), str(a, "sort", "path")));
                case "search_text" -> Result.ok(searchText(required(a, "query"), readable(str(a, "path", ".")),
                        str(a, "file_pattern", "*"), bool(a, "regex"), bool(a, "case_sensitive")));
                case "read_file" -> Result.ok(readRequestedFile(readable(required(a, "path")), integer(a, "offset", 1), a.get("limit") == null ? null : integer(a, "limit", 400)));
                case "extract_attachments" -> Result.ok(extractAttachments(readable(required(a, "path")), str(a, "destination", null), bool(a, "include_inline")));
                case "write_file" -> Result.ok(writeFile(writable(required(a, "path")), requiredText(a, "content"), false));
                case "append_file" -> Result.ok(writeFile(writable(required(a, "path")), requiredText(a, "content"), true));
                case "edit_file" -> Result.ok(editFile(writable(required(a, "path")), requiredText(a, "old_text"), requiredText(a, "new_text"), bool(a, "replace_all")));
                case "make_dir" -> Result.ok(makeDir(writable(required(a, "path"))));
                case "copy_path" -> Result.ok(copyOrMove(path(required(a, "source")), writable(required(a, "destination")), bool(a, "overwrite"), false));
                case "move_path" -> Result.ok(copyOrMove(path(required(a, "source")), writable(required(a, "destination")), bool(a, "overwrite"), true));
                case "run_command" -> runCommand(required(a, "command"), path(str(a, "cwd", ".")), Math.min(3600, Math.max(1, integer(a, "timeout_seconds", 600))));
                case "open_in_explorer" -> Result.ok(openInExplorer(path(required(a, "path"))));
                case "read_skill" -> Result.ok(Skills.read(skills, required(a, "name"), ctx.paths()::text));
                case "save_rule" -> Result.ok(saveRule(required(a, "rule"),str(a,"scope","global"),str(a,"project",memoryProject)));
                default -> Result.error("Tool necunoscut: " + call.name);
            };
        } catch (ToolError e) {
            return Result.error(e.getMessage());
        } catch (NoSuchFileException e) {
            return Result.error("Nu exista: " + e.getFile());
        } catch (AccessDeniedException e) {
            return Result.error("Acces refuzat: " + e.getFile());
        } catch (FileAlreadyExistsException e) {
            return Result.error("Exista deja: " + e.getFile() + " (foloseste overwrite=true daca vrei sa-l inlocuiesti).");
        } catch (IOException e) {
            return Result.error(e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (RuntimeException e) {
            return Result.error("Eroare interna: " + e);
        }
    }

    // ---- paths and permissions ----

    private Path path(String raw) throws ToolError {
        String given = expand(raw == null || raw.isBlank() ? "." : raw.trim());
        String s = ctx.paths().path(given);
        if (!s.equals(given) && !s.equalsIgnoreCase(given.replace('/', '\\'))) {
            notes.add("Nota: calea „" + given + "” e de pe alt calculator; pe acesta am folosit „" + s + "”. Foloseste de acum calea de aici.");
        }
        try {
            Path p = Paths.get(s);
            if (!p.isAbsolute()) p = ctx.workDir().resolve(p);
            return p.toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new ToolError("Cale invalida: " + raw);
        }
    }

    /** Reading inside the working folder (or skills) is free; anywhere else asks first. */
    private Path readable(String raw) throws ToolError {
        Path p = guarded(raw);
        if (!p.startsWith(ctx.workDir()) && !p.startsWith(ctx.skillsDir()) && !p.equals(ctx.rulesFile()) && !AttachmentSupport.grantsRead(p,attachedPaths)) {
            allow(Access.READ, "Citire in afara folderului de lucru", p.toString());
        }
        return p;
    }

    private Path writable(String raw) throws ToolError {
        return guarded(raw); // each write tool asks with its own details
    }

    private Path guarded(String raw) throws ToolError {
        Path p = path(raw);
        if (p.equals(ctx.secretFile()) || (Files.exists(p) && Files.exists(ctx.secretFile()) && sameFile(p,ctx.secretFile()))) throw new ToolError("Fisierul de setari al aplicatiei (cu cheile API) nu e accesibil prin tool-uri.");
        return p;
    }

    private static boolean sameFile(Path a,Path b){try{return Files.isSameFile(a,b);}catch(IOException ignored){return false;}}

    private void allow(Access access, String title, String details) throws ToolError {
        if (cancelled) throw new ToolError("Oprit de utilizator.");
        if(readOnly && access!=Access.READ)throw new ToolError(I18n.text("Mod doar citire: modificarile si comenzile sunt blocate."));
        if (ctx.autoApprove() || ctx.alwaysAllowed().contains(access)) return;
        Decision d = ctx.permission().ask(title, details);
        if (d == Decision.ALWAYS) ctx.alwaysAllowed().add(access);
        if (d == Decision.NO) {
            throw new ToolError("Utilizatorul a refuzat: " + title.toLowerCase(Locale.ROOT)
                    + ". Nu incerca acelasi lucru pe alta cale; intreaba utilizatorul ce doreste.");
        }
    }

    static String expand(String s) {
        if (s.equals("~") || s.startsWith("~\\") || s.startsWith("~/")) s = System.getProperty("user.home") + s.substring(1);
        Matcher m = Pattern.compile("%([A-Za-z0-9_()]+)%").matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String v = System.getenv(m.group(1));
            m.appendReplacement(b, Matcher.quoteReplacement(v != null ? v : m.group()));
        }
        m.appendTail(b);
        return b.toString();
    }

    // ---- folders and search ----

    private String listDir(Path dir, String sort) throws IOException, ToolError {
        if (!Files.isDirectory(dir)) throw new ToolError(Files.exists(dir) ? "Nu e folder: " + dir + " (foloseste read_file)." : "Nu exista: " + dir);
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) entries.add(p);
        }
        Map<Path, BasicFileAttributes> attrs = new LinkedHashMap<>();
        for (Path p : entries) attrs.put(p, attributes(p));
        Comparator<Path> byName = Comparator.comparing((Path p) -> attrs.get(p) == null || !attrs.get(p).isDirectory())
                .thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT));
        entries.sort("modified".equals(sort) ? Comparator.comparing((Path p) -> modified(attrs.get(p))).reversed() : byName);
        StringBuilder b = new StringBuilder("Folder: " + dir + "\n");
        if (entries.isEmpty()) b.append("(gol)\n");
        for (int i = 0; i < entries.size() && i < MAX_LISTED; i++) {
            Path p = entries.get(i);
            BasicFileAttributes at = attrs.get(p);
            boolean isDir = at != null && at.isDirectory();
            b.append(String.format("%s  %-5s %9s  %s%s%n", at == null ? "????-??-?? ??:??" : time(at), isDir ? "DIR" : "",
                    isDir || at == null ? "" : size(at.size()), p.getFileName(), isDir ? "\\" : ""));
        }
        if (entries.size() > MAX_LISTED) b.append("… inca ").append(entries.size() - MAX_LISTED).append(" intrari neafisate.\n");
        return b.toString();
    }

    private String findFiles(String pattern, Path root, String sort) throws IOException, ToolError {
        if (!Files.isDirectory(root)) throw new ToolError("Nu e folder: " + root);
        String glob = pattern.trim().replace('\\', '/');
        boolean byName = !glob.contains("/");
        PathMatcher matcher;
        PathMatcher alsoTop;
        try {
            matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
            alsoTop = glob.startsWith("**/") ? FileSystems.getDefault().getPathMatcher("glob:" + glob.substring(3)) : null;
        } catch (IllegalArgumentException e) {
            throw new ToolError("Model glob invalid: " + pattern);
        }
        List<Path> found = new ArrayList<>();
        Map<Path, BasicFileAttributes> attrs = new LinkedHashMap<>();
        walk(root, (file, at) -> {
            Path rel = root.relativize(file);
            boolean hit = byName ? matcher.matches(file.getFileName()) : matcher.matches(rel) || (alsoTop != null && alsoTop.matches(rel));
            if (hit) {
                found.add(file);
                attrs.put(file, at);
            }
            return found.size() < 5000;
        });
        if ("modified".equals(sort)) found.sort(Comparator.comparing((Path p) -> modified(attrs.get(p))).reversed());
        else found.sort(Comparator.comparing(p -> p.toString().toLowerCase(Locale.ROOT)));
        StringBuilder b = new StringBuilder(found.size() + " fisiere gasite pentru '" + pattern + "' in " + root + "\n");
        for (int i = 0; i < found.size() && i < MAX_LISTED; i++) {
            Path p = found.get(i);
            b.append(String.format("%s  %9s  %s%n", time(attrs.get(p)), size(attrs.get(p).size()), root.relativize(p)));
        }
        if (found.size() > MAX_LISTED) b.append("… inca ").append(found.size() - MAX_LISTED).append("; restrange cautarea.\n");
        return b.toString();
    }

    private String searchText(String query, Path root, String filePattern, boolean regex, boolean caseSensitive) throws IOException, ToolError {
        Pattern p;
        try {
            int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            p = Pattern.compile(regex ? query : Pattern.quote(query), flags);
        } catch (PatternSyntaxException e) {
            throw new ToolError("Expresie regulata invalida: " + e.getDescription());
        }
        PathMatcher names = FileSystems.getDefault().getPathMatcher("glob:" + (filePattern.isBlank() ? "*" : filePattern.trim()));
        List<String> hits = new ArrayList<>();
        int[] files = {0};
        Path base = Files.isDirectory(root) ? root : root.getParent();
        Visitor visitor = (file, at) -> {
            if (!names.matches(file.getFileName()) || at.size() > 10_000_000) return true;
            Text t = decode(Files.readAllBytes(file));
            if (t == null) return true;
            files[0]++;
            String[] lines = t.text().split("\r\n|\n|\r", -1);
            for (int i = 0; i < lines.length; i++) {
                if (p.matcher(lines[i]).find()) {
                    hits.add(base.relativize(file) + ":" + (i + 1) + ": " + clip(lines[i].trim(), 300));
                    if (hits.size() >= 300) return false;
                }
            }
            return true;
        };
        if (Files.isRegularFile(root)) visitor.visit(root, Files.readAttributes(root, BasicFileAttributes.class));
        else if (Files.isDirectory(root)) walk(root, visitor);
        else throw new ToolError("Nu exista: " + root);
        StringBuilder b = new StringBuilder(hits.size() + " randuri gasite in " + files[0] + " fisiere text (" + root + ")\n");
        for (String h : hits) b.append(h).append('\n');
        if (hits.size() >= 300) b.append("… oprit la 300 de rezultate; restrange cautarea.\n");
        return cap(b.toString());
    }

    private interface Visitor {
        /** Returns false to stop the walk. */
        boolean visit(Path file, BasicFileAttributes attrs) throws IOException;
    }

    private void walk(Path root, Visitor visitor) throws IOException {
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 40, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (cancelled) return FileVisitResult.TERMINATE;
                boolean skip = !dir.equals(root) && SKIP_DIRS.contains(dir.getFileName().toString().toLowerCase(Locale.ROOT));
                return skip ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile() || file.equals(ctx.secretFile()) || sameFile(file,ctx.secretFile())) return FileVisitResult.CONTINUE;
                try {
                    return visitor.visit(file, attrs) ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
                } catch (IOException e) {
                    return FileVisitResult.CONTINUE; // locked or unreadable: skip it
                }
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE; // no access: skip it
            }
        });
    }

    // ---- reading ----

    /** Recover only the known missing engine component, using exact completed-job evidence inside the workspace. */
    private Path confirmedPdfResult(Path path){
        try{
            Path checked=path.getParent(),run=checked==null?null:checked.getParent();
            if(!path.getFileName().toString().equalsIgnoreCase("resultaten.json")||checked==null||!checked.getFileName().toString().equalsIgnoreCase("checked")||run==null||!run.getFileName().toString().matches("[a-fA-F0-9]{32}")||run.getParent()==null||!run.getParent().getFileName().toString().equalsIgnoreCase("Octop-PDFCONTROL"))return null;
            Path actual=run.resolve("engine/checked/resultaten.json"),job=run.resolve("job.json"),scope=ctx.workDir().toRealPath();
            if(!Files.isRegularFile(actual)||!Files.isRegularFile(job)||!actual.toRealPath().startsWith(scope)||!job.toRealPath().startsWith(scope)||Files.size(job)>1048576)return null;
            guarded(job.toString());guarded(actual.toString());
            Object record=Json.parse(Files.readString(job,StandardCharsets.UTF_8));String status=Json.str(record,"status"),result=Json.str(record,"results");
            if(!List.of("done","partial").contains(status)||!run.getFileName().toString().equals(Json.str(record,"job_id"))||result==null||!Path.of(result).toAbsolutePath().normalize().equals(actual.toAbsolutePath().normalize()))return null;
            return actual;
        }catch(IOException|RuntimeException|ToolError ignored){return null;}
    }

    private String readRequestedFile(Path requested,int offset,Integer limit)throws IOException,ToolError{
        Path actual=Files.exists(requested)?null:confirmedPdfResult(requested);
        if(actual==null)return readFile(requested,offset,limit);
        // Apply the normal access and secret-file checks to the confirmed target, then report the real read.
        String content=readFile(readable(actual.toString()),offset,limit);
        return I18n.text("PDFCONTROL: cale recuperata din job.json (engine/checked/resultaten.json).\nCale ceruta: {0}\nCale folosita: {1}",requested,actual)+"\n"+content;
    }

    private String readFile(Path p, int offset, Integer limit) throws IOException, ToolError {
        if (Files.isDirectory(p)) throw new ToolError("E un folder: " + p + " — foloseste list_dir.");
        if (!Files.exists(p)) throw new ToolError(I18n.text("Nu exista: {0}",p));
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) return readPdf(p, offset, limit == null ? 20 : limit);
        String text;
        if (name.endsWith(".msg")) {
            text = OutlookMsg.text(p);
        } else if (name.endsWith(".docx")) {
            text = OfficeText.docx(p);
        } else if (name.endsWith(".xlsx") || name.endsWith(".xlsm")) {
            text = OfficeText.xlsx(p);
        } else {
            long size = Files.size(p);
            if (size > 50_000_000) throw new ToolError("Fisier prea mare pentru citire (" + size(size) + ").");
            Text t = decode(Files.readAllBytes(p));
            if (t == null) {
                String hint = name.endsWith(".xls") || name.endsWith(".doc")
                        ? " Formatul Office vechi nu e suportat; cere salvarea ca .xlsx/.docx sau CSV."
                        : "";
                throw new ToolError("Fisier binar (" + size(size) + "), nu poate fi citit ca text." + hint);
            }
            text = t.text();
        }
        String[] lines = text.split("\r\n|\n|\r", -1);
        int from = Math.max(1, offset);
        int count = Math.max(1, limit == null ? 400 : limit);
        int to = Math.min(lines.length, from + count - 1);
        StringBuilder b = new StringBuilder("Fisier: " + p + " (" + lines.length + " randuri)\n");
        for (int i = from; i <= to; i++) {
            b.append(String.format("%5d| %s%n", i, clip(lines[i - 1], 2000)));
            if (b.length() > MAX_OUTPUT) {
                b.append("… limita de text atinsa; continua cu offset=").append(i + 1).append('\n');
                return b.toString();
            }
        }
        if (to < lines.length) b.append("… mai sunt ").append(lines.length - to).append(" randuri; continua cu offset=").append(to + 1).append('\n');
        return b.toString();
    }

    private String readPdf(Path p, int offset, int limit) throws IOException, ToolError {
        String python = ctx.pdfPython();
        if (python == null || python.isBlank() || !Files.isRegularFile(Paths.get(python))) {
            throw new ToolError("Pentru PDF trebuie configurat un Python cu PyMuPDF (cheia \"pdfPython\" din unelte.json).");
        }
        int first = Math.max(1, offset);
        int last = first + Math.max(1, limit) - 1;
        Output out = exec(List.of(python, "-I", "-c", PDF_SCRIPT, p.toString(), String.valueOf(first), String.valueOf(last)),
                ctx.workDir(), Map.of(), 180);
        if (!out.finished() || out.exitCode() != 0) throw new ToolError("Nu pot citi PDF-ul: " + clip(out.text(), 1500));
        return "PDF: " + p + "\n" + cap(out.text());
    }

    private String extractAttachments(Path msg, String destination, boolean includeInline) throws IOException, ToolError {
        String file = msg.getFileName().toString();
        if (!file.toLowerCase(Locale.ROOT).endsWith(".msg")) throw new ToolError("extract_attachments merge doar pentru emailuri Outlook .msg.");
        if (!Files.isRegularFile(msg)) throw new ToolError("Nu exista: " + msg);
        OutlookMsg.Message m = OutlookMsg.read(msg);
        Path dir = destination == null || destination.isBlank()
                ? msg.resolveSibling(OutlookMsg.safeName(file.substring(0, file.length() - 4)) + " - bijlagen")
                : writable(destination);
        StringBuilder list = new StringBuilder();
        int count = 0;
        for (OutlookMsg.Attachment att : m.attachments()) {
            if (att.inline() && !includeInline) continue;
            count++;
            list.append("\n- ").append(att.embedded() != null ? "[email] " + att.name() + ".txt" : att.name() + " (" + size(att.size()) + ")");
        }
        if (count == 0) return "Emailul nu are atasamente de salvat" + (m.attachments().isEmpty() ? "." : " (doar imagini inline/semnatura).");
        allow(Access.WRITE, "Salvare atasamente email", "In folderul: " + dir + list);
        List<String> report = new ArrayList<>();
        OutlookMsg.extract(m, dir, includeInline, report);
        return "Atasamentele din " + file + " → " + dir + "\n" + String.join("\n", report);
    }

    // ---- writing ----

    private String writeFile(Path p, String content, boolean append) throws IOException, ToolError {
        if (Files.isDirectory(p)) throw new ToolError("E un folder: " + p);
        boolean exists = Files.exists(p);
        allow(Access.WRITE, append ? "Adaugare la fisier" : exists ? "Suprascriere fisier" : "Fisier nou",
                p + "\n(" + content.length() + " caractere)\n\n" + clip(content, 1500));
        Files.createDirectories(p.getParent());
        if (append) {
            Text old = exists ? decode(Files.readAllBytes(p)) : null;
            if (exists && old == null) throw new ToolError("Fisierul existent e binar; nu adaug text la el.");
            Charset cs = old == null ? StandardCharsets.UTF_8 : old.charset();
            String sep = old != null && !old.text().isEmpty() && !old.text().endsWith("\n") ? System.lineSeparator() : "";
            Files.writeString(p, (old == null ? "" : old.text()) + sep + content, cs);
            return "Adaugat " + content.length() + " caractere la " + p;
        }
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return (exists ? "Suprascris: " : "Creat: ") + p + " (" + content.length() + " caractere)";
    }

    private String editFile(Path p, String oldText, String newText, boolean all) throws IOException, ToolError {
        if (!Files.isRegularFile(p)) throw new ToolError("Nu exista fisierul: " + p);
        Text t = decode(Files.readAllBytes(p));
        if (t == null) throw new ToolError("Fisier binar; nu poate fi editat ca text.");
        String text = t.text();
        if (oldText.isEmpty()) throw new ToolError("old_text e gol.");
        if (!text.contains(oldText) && text.contains("\r\n") && oldText.contains("\n") && !oldText.contains("\r\n")) {
            oldText = oldText.replace("\n", "\r\n"); // the model usually writes \n line endings
            newText = newText.replace("\n", "\r\n");
        }
        int count = 0;
        for (int i = text.indexOf(oldText); i >= 0; i = text.indexOf(oldText, i + oldText.length())) count++;
        if (count == 0) throw new ToolError("Textul de inlocuit nu a fost gasit in " + p + ". Citeste din nou fisierul si copiaza textul exact.");
        if (count > 1 && !all) throw new ToolError("Textul apare de " + count + " ori; include mai mult context sau foloseste replace_all=true.");
        allow(Access.WRITE, "Modificare fisier", p + "\n\n− " + clip(oldText, 700) + "\n+ " + clip(newText, 700));
        String updated = all ? text.replace(oldText, newText) : text.replaceFirst(Pattern.quote(oldText), Matcher.quoteReplacement(newText));
        Files.writeString(p, updated, t.charset());
        return "Modificat " + p + " (" + (all ? count : 1) + " inlocuiri)";
    }

    private String makeDir(Path p) throws IOException, ToolError {
        if (Files.isDirectory(p)) return "Exista deja: " + p;
        allow(Access.WRITE, "Creare folder", p.toString());
        Files.createDirectories(p);
        return "Creat folderul " + p;
    }

    private String copyOrMove(Path source, Path destination, boolean overwrite, boolean move) throws IOException, ToolError {
        if (!Files.exists(source)) throw new ToolError("Nu exista sursa: " + source);
        if (source.equals(ctx.secretFile())) throw new ToolError("Fisierul de setari nu poate fi copiat sau mutat.");
        Path target = Files.isDirectory(destination) ? destination.resolve(source.getFileName()) : destination;
        if (target.startsWith(source)) throw new ToolError("Destinatia e in interiorul sursei.");
        if (Files.exists(target) && !overwrite) throw new ToolError("Exista deja: " + target + " (foloseste overwrite=true daca vrei sa-l inlocuiesti).");
        allow(Access.WRITE, move ? "Mutare" : "Copiere", source + "\n→ " + target + (overwrite ? "\n(inlocuieste ce exista)" : ""));
        StandardCopyOption[] options = overwrite
                ? new StandardCopyOption[]{StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES}
                : new StandardCopyOption[]{StandardCopyOption.COPY_ATTRIBUTES};
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        if (move) {
            Files.move(source, target, overwrite ? new StandardCopyOption[]{StandardCopyOption.REPLACE_EXISTING} : new StandardCopyOption[0]);
            return "Mutat: " + source + " → " + target;
        }
        if (Files.isRegularFile(source)) {
            Files.copy(source, target, options);
            return "Copiat: " + source + " → " + target + " (" + size(Files.size(target)) + ")";
        }
        long[] totals = {0, 0};
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return cancelled ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file).toString()), options);
                totals[0]++;
                totals[1] += attrs.size();
                return FileVisitResult.CONTINUE;
            }
        });
        return "Copiat folderul " + source + " → " + target + " (" + totals[0] + " fisiere, " + size(totals[1]) + ")";
    }

    // ---- commands ----

    private Result runCommand(String command, Path cwd, int timeoutSeconds) throws IOException, ToolError {
        if (!Files.isDirectory(cwd)) throw new ToolError("Folderul pentru comanda nu exista: " + cwd);
        allow(Access.RUN, "Rulare comanda", command + "\n\nIn folderul: " + cwd);
        Path script = Files.createTempFile("localchat-", ".cmd");
        try {
            String body = command.replace("\r\n", "\n").replace("\n", "\r\n");
            Files.writeString(script, "@echo off\r\nchcp 65001 >nul\r\n" + body + "\r\n", StandardCharsets.UTF_8);
            Output out = exec(List.of("cmd.exe", "/d", "/c", script.toString()), cwd, ctx.commandEnv(), timeoutSeconds);
            if (cancelled) throw new ToolError("Oprit de utilizator.");
            String text = out.text().isBlank() ? "(fara output)" : cap(out.text());
            if (!out.finished()) return Result.error("Comanda a depasit " + timeoutSeconds + " s si a fost oprita.\n" + text);
            return new Result("Cod de iesire: " + out.exitCode() + "\n" + text, out.exitCode() != 0);
        } finally {
            Files.deleteIfExists(script);
        }
    }

    private Output exec(List<String> command, Path cwd, Map<String, String> env, int timeoutSeconds) throws IOException, ToolError {
        ProcessBuilder pb = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true);
        pb.environment().putAll(env);
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        pb.environment().put("PYTHONUTF8", "1");
        Process proc = pb.start();
        running = proc;
        try {
            proc.getOutputStream().close(); // programs that wait for input get end-of-file instead of hanging
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> copyCapped(proc.getInputStream(), buffer), "command-output");
            reader.setDaemon(true);
            reader.start();
            boolean finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) kill(proc);
            reader.join(5000);
            String text;
            synchronized (buffer) {
                text = buffer.toString(StandardCharsets.UTF_8);
            }
            return new Output(finished ? proc.exitValue() : -1, finished, text);
        } catch (InterruptedException e) {
            kill(proc);
            Thread.currentThread().interrupt();
            throw new ToolError("Intrerupt.");
        } finally {
            running = null;
        }
    }

    private static void copyCapped(InputStream in, ByteArrayOutputStream out) {
        byte[] buf = new byte[8192];
        try (InputStream is = in) {
            int n;
            while ((n = is.read(buf)) > 0) {
                synchronized (out) {
                    if (out.size() < 4_000_000) out.write(buf, 0, n);
                }
            }
        } catch (IOException ignored) {
            // process killed
        }
    }

    private static void kill(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }

    private String openInExplorer(Path p) throws IOException, ToolError {
        if (!Files.exists(p)) throw new ToolError("Nu exista: " + p);
        Path folder = Files.isDirectory(p) ? p : p.getParent();
        new ProcessBuilder("explorer.exe", folder.toString()).start();
        return "Am deschis in Explorer: " + folder;
    }

    // ---- rules and MCP ----

    private String saveRule(String rule,String scope,String project) throws IOException, ToolError {
        if(readOnly)throw new ToolError(I18n.text("Mod doar citire: modificarile si comenzile sunt blocate."));
        if(memories!=null)return memories.saveRule(rule,scope,project,memoryProject,memoryUser,memoryChat,true);
        Path f = ctx.rulesFile();
        String existing = Files.exists(f) ? Files.readString(f, StandardCharsets.UTF_8) : "# Reguli salvate\n\n";
        String sep = existing.isEmpty() || existing.endsWith("\n") ? "" : "\n";
        Files.writeString(f, existing + sep + "- " + rule.trim().replace("\n", " ") + "  _(" + LocalDate.now() + ")_\n", StandardCharsets.UTF_8);
        return "Regula a fost salvata in " + f;
    }

    private Result callMcp(McpRef ref, Map<String, Object> args) throws IOException, ToolError {
        if (!ref.tool().readOnly) {
            allow(Access.RUN, "Tool " + ref.server().name + ": " + ref.tool().name, clip(Json.write(args), 1500));
        }
        Result r = ref.server().call(ref.tool().name, args, 15 * 60_000L);
        if(!r.isError&&(ref.tool().name.startsWith("pdfcontrol_start_")||ref.tool().name.equals("pdfcontrol_regenerate_report"))){
            Object record=Json.parse(r.text);String id=Json.str(record,"job_id"),project=String.valueOf(args.get("project"));
            if(id!=null&&Set.of("pending","running").contains(java.util.Objects.toString(Json.str(record,"status"),""))){
                PdfJob job=new PdfJob(ref.server(),project,id);activePdfJobs.add(job);long until=System.nanoTime()+java.util.concurrent.TimeUnit.HOURS.toNanos(1);
                try{
                    while(Set.of("pending","running").contains(java.util.Objects.toString(Json.str(record,"status"),""))){
                        if(cancelled){cancelPdfJob(job);return Result.error(I18n.text("PDFCONTROL: anulare solicitata pentru lucrarea {0}.",id));}
                        if(System.nanoTime()>until)return Result.error(I18n.text("PDFCONTROL: lucrarea {0} continua; verifica starea inainte de a reporni.",id));
                        Result update=ref.server().call("pdfcontrol_job_status",Json.obj("project",project,"job_id",id,"wait_seconds",5),30_000);
                        if(update.isError)return update;record=Json.parse(update.text);
                    }
                    boolean failed=Set.of("failed","cancelled").contains(java.util.Objects.toString(Json.str(record,"status"),""));
                    return new Result(cap(Json.write(record)),failed);
                }finally{activePdfJobs.remove(job);if(cancelled)cancelPdfJob(job);}
            }
        }
        return new Result(cap(r.text), r.isError);
    }

    // ---- helpers ----

    private static ChatClient.ToolSpec spec(String name, String description, List<String> required, Object... props) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = 0; i + 2 < props.length; i += 3) {
            properties.put((String) props[i], Json.obj("type", props[i + 1], "description", props[i + 2]));
        }
        return new ChatClient.ToolSpec(name, description,
                Json.obj("type", "object", "properties", properties, "required", new ArrayList<Object>(required)));
    }

    private static String uniqueName(String base, Set<String> taken) {
        String name = base.replaceAll("[^a-zA-Z0-9_-]", "_");
        if (name.length() > 60) name = name.substring(0, 60);
        String candidate = name;
        for (int i = 2; taken.contains(candidate); i++) candidate = name + "_" + i;
        return candidate;
    }

    private static String str(Map<String, Object> a, String key, String def) {
        Object v = a.get(key);
        return v == null ? def : v.toString();
    }

    private static String required(Map<String, Object> a, String key) throws ToolError {
        String v = str(a, key, "").trim();
        if (v.isEmpty()) throw new ToolError("Lipseste argumentul '" + key + "'.");
        return v;
    }

    /** Like required(), but keeps whitespace: file content and replacement text are taken literally. */
    private static String requiredText(Map<String, Object> a, String key) throws ToolError {
        Object v = a.get(key);
        if (v == null) throw new ToolError("Lipseste argumentul '" + key + "'.");
        return v.toString();
    }

    private static boolean bool(Map<String, Object> a, String key) {
        Object v = a.get(key);
        return Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v));
    }

    private static int integer(Map<String, Object> a, String key, int def) throws ToolError {
        Object v = a.get(key);
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new ToolError("Argumentul '" + key + "' trebuie sa fie un numar.");
        }
    }

    /** Text from bytes: UTF-8/UTF-16 when valid, else Windows-1252; null for binary data. */
    private static Text decode(byte[] b) {
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            return new Text(new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE), StandardCharsets.UTF_16LE);
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            return new Text(new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE), StandardCharsets.UTF_16BE);
        }
        for (int i = 0; i < Math.min(b.length, 8000); i++) if (b[i] == 0) return null;
        try {
            String s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
            return new Text(s.startsWith("﻿") ? s.substring(1) : s, StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            Charset cp = Charset.forName("windows-1252");
            return new Text(new String(b, cp), cp);
        }
    }

    private static BasicFileAttributes attributes(Path p) {
        try {
            return Files.readAttributes(p, BasicFileAttributes.class);
        } catch (IOException e) {
            return null;
        }
    }

    private static long modified(BasicFileAttributes at) {
        return at == null ? 0 : at.lastModifiedTime().toMillis();
    }

    private static String time(BasicFileAttributes at) {
        return LocalDateTime.ofInstant(at.lastModifiedTime().toInstant(), ZoneId.systemDefault()).format(TIME);
    }

    static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
        return String.format(Locale.ROOT, "%.2f GB", bytes / 1073741824.0);
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** Keeps the start and the end of long output; the model rarely needs the middle. */
    private static String cap(String s) {
        if (s.length() <= MAX_OUTPUT) return s;
        int head = MAX_OUTPUT * 2 / 3;
        int tail = MAX_OUTPUT - head;
        return s.substring(0, head) + "\n\n… [" + (s.length() - MAX_OUTPUT) + " caractere omise] …\n\n" + s.substring(s.length() - tail);
    }
}
