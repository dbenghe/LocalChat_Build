package localchat;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.*;

/**
 * 1.28.11: hard guard against helper files in project folders and on servers.
 * Scripts, probes and temp files belong in the per-conversation scratch folder; deliverables go to Output or Controle;
 * the server receives new production files (1.28.13: without a question); changing or deleting what already exists there asks every time.
 * Neither "Permite tot" nor a remembered "Da mereu" bypasses that. Pure path/name logic, so it is tested without a UI.
 */
final class FileGuard {
    static final String COPY_TITLE = "Modificare pe server: confirmare necesara";
    static final String CLEAN_TITLE = "Curatare fisiere ajutatoare: confirmare necesara";
    static final List<String> SERVER_EXTENSIONS = List.of("pdf", "xlsx", "xls", "csv", "dwg", "dxf", "ifc", "docx", "zip");
    private static final Set<String> HELPER_EXTENSIONS = Set.of("py", "pyw", "ps1", "psm1", "bat", "cmd", "vbs", "wsf", "js", "md", "sh");
    // tmp*/temp* only as a word: "tmp_x", "temp1", "Temp.csv" match, "template.xlsx" and "Tempelstraat" do not.
    private static final Pattern TEMP_NAME = Pattern.compile("(?i)(tmp|temp)([^a-z].*)?");
    private static final Pattern CHATGPT = Pattern.compile("(?i)Pentru_ChatGPT_[^\\\\/]+\\.txt");
    private static final Set<String> VALUE_PARAMS = Set.of("path", "literalpath", "destination", "filepath", "name", "itemtype", "type", "value",
            "encoding", "filter", "include", "exclude", "width", "inputobject", "delimiter", "stream", "depth", "container");
    private static final Set<String> COPY_VERBS = Set.of("copy", "xcopy", "robocopy", "copy-item", "cp", "cpi", "move", "move-item", "mv", "mi");
    static final int LIMIT = 5000;

    enum Zone { SCRATCH, PROJECT, NETWORK, OTHER }

    enum Kind { WRITE, DIR, COPY, MOVE }

    /** A write or copy found in a shell command; paths are still raw text. */
    record Op(Kind kind, String source, String dest, List<String> patterns, boolean mirror) {
    }

    /** One file a copy or move would touch, with its path relative to the copied folder (or just its name). */
    record Item(Path file, Path relative, boolean fromFolder) {
    }

    private static volatile long drivesAt;
    private static volatile Set<Character> drivesCache = Set.of();

    private final Path scratch;
    private final List<Path> roots;
    private final Supplier<Set<Character>> drives;
    private final boolean enabled;

    private FileGuard(Path scratch, List<Path> roots, Supplier<Set<Character>> drives, boolean enabled) {
        this.scratch = scratch == null ? null : norm(scratch);
        this.roots = roots;
        this.drives = drives;
        this.enabled = enabled;
    }

    static FileGuard disabled() {
        return new FileGuard(null, List.of(), Set::of, false);
    }

    static FileGuard of(Path scratch, Collection<Path> roots, Supplier<Set<Character>> drives) {
        List<Path> list = new ArrayList<>();
        for (Path r : roots) if (r != null) list.add(norm(r));
        return new FileGuard(scratch, List.copyOf(list), drives == null ? Set::of : drives, true);
    }

    /** Scratch folder of one conversation: %TEMP%\LocalChat\<id>. Created only when something is written there. */
    /** Cards that are confirmations only: no "always allow" choice, shown every time. */
    static boolean confirmOnly(String title) {
        return COPY_TITLE.equals(title) || CLEAN_TITLE.equals(title);
    }

    static Path scratchFor(String conversationId) {
        String safe = (conversationId == null ? "" : conversationId.replaceAll("\\.json$", "")).replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isBlank() || safe.chars().allMatch(c -> c == '.')) safe = "session";
        if (safe.length() > 80) safe = safe.substring(0, 80);
        String temp = System.getenv("TEMP");
        if (temp == null || temp.isBlank()) temp = System.getProperty("java.io.tmpdir");
        return Path.of(temp, "LocalChat", safe);
    }

    Path scratch() {
        return scratch;
    }

    boolean active() {
        return enabled;
    }

    String scratchText() {
        return scratch == null ? "%TEMP%\\LocalChat\\<conversatie>" : scratch.toString();
    }

    static Path norm(Path p) {
        return p.toAbsolutePath().normalize();
    }

    // ---- zones ----

    boolean inScratch(Path p) {
        return scratch != null && norm(p).startsWith(scratch);
    }

    Zone zone(Path p) {
        Path n = norm(p);
        if (scratch != null && n.startsWith(scratch)) return Zone.SCRATCH;
        if (!enabled) return Zone.OTHER;
        if (network(n)) return Zone.NETWORK;
        if (base(n) != null) return Zone.PROJECT;
        return Zone.OTHER;
    }

    boolean server(Path p) {
        return enabled && zone(p) == Zone.NETWORK;
    }

    boolean project(Path p) {
        return enabled && zone(p) == Zone.PROJECT;
    }

    private boolean network(Path n) {
        if (n.toString().startsWith("\\\\")) return true;
        Path root = n.getRoot();
        if (root == null) return false;
        String r = root.toString();
        return r.length() >= 2 && r.charAt(1) == ':' && drives.get().contains(Character.toUpperCase(r.charAt(0)));
    }

    /** The project folder that contains n: the configured folders, or ...\PRODUCTIE\<nr>. Longest match wins. */
    private Path base(Path n) {
        Path best = null;
        for (Path r : roots) if (n.startsWith(r) && (best == null || r.getNameCount() > best.getNameCount())) best = r;
        for (int i = 0; i < n.getNameCount() - 1; i++) {
            if (n.getName(i).toString().equalsIgnoreCase("PRODUCTIE")) {
                int end = Math.min(i + 2, n.getNameCount());
                Path candidate = n.getRoot().resolve(n.subpath(0, end));
                if (best == null || candidate.getNameCount() > best.getNameCount()) best = candidate;
                break;
            }
        }
        return best;
    }

    // ---- names ----

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static boolean helperDir(String name) {
        return name.startsWith("_") || TEMP_NAME.matcher(name).matches();
    }

    static boolean helperFile(String name) {
        return helperDir(name) || HELPER_EXTENSIONS.contains(extension(name));
    }

    private static boolean oldName(String name) {
        return name.toLowerCase(Locale.ROOT).matches("old([-_. ].*)?");
    }

    /** PROJECT_INFO.md, Pentru_ChatGPT_<nr>.txt and anything under Output\ or Controle\ of a project. */
    boolean allowed(Path p, boolean directory) {
        Path n = norm(p);
        Path base = base(n);
        if (base == null || n.getFileName() == null) return false;
        String name = n.getFileName().toString();
        if (name.equalsIgnoreCase("PROJECT_INFO.md") || CHATGPT.matcher(name).matches()) return true;
        Path rel = base.relativize(n);
        int count = rel.toString().isEmpty() ? 0 : rel.getNameCount();
        int dirs = directory ? count : count - 1;
        for (int i = 0; i < dirs; i++) {
            String d = rel.getName(i).toString();
            if (d.equalsIgnoreCase("Output") || d.equalsIgnoreCase("Controle")) return true;
        }
        return false;
    }

    // ---- hard guard ----

    /** Refusal text (for the model) when this target may not be created in a project folder or on a server; null when fine. */
    String refuse(Path target, boolean directory) {
        if (!enabled) return null;
        Path n = norm(target);
        Zone zone = zone(n);
        if (zone == Zone.PROJECT) {
            if (allowed(n, directory)) return null;
            Path base = base(n);
            Path rel = base.relativize(n);
            int count = rel.toString().isEmpty() ? 0 : rel.getNameCount();
            for (int i = 0; i < count; i++) {
                String name = rel.getName(i).toString();
                boolean last = i == count - 1, dir = !last || directory;
                if (!last && Files.exists(base.resolve(rel.subpath(0, i + 1)))) continue;
                if (dir ? helperDir(name) : helperFile(name)) return I18n.text("Fisier ajutator refuzat: {0}. Scripturile, probele, fisierele temporare, fisierele .md si numele care incep cu _ sau tmp nu se scriu in folderul proiectului. Scrie-le in folderul scratch {1} (comanda poate rula cu proiectul ca folder curent). Livrabilele merg numai in Output sau Controle. Nu repeta si nu ocoli regula.", n, scratchText());
            }
            return null;
        }
        if (zone == Zone.NETWORK) {
            Path root = n.getRoot();
            Path rel = root == null ? n : root.relativize(n);
            int count = rel.toString().isEmpty() ? 0 : rel.getNameCount();
            for (int i = 0; i < count; i++) {
                String name = rel.getName(i).toString();
                boolean last = i == count - 1, dir = !last || directory;
                boolean fresh = last || !Files.exists(root.resolve(rel.subpath(0, i + 1)));
                if (oldName(name) || (fresh && (dir ? helperDir(name) : helperFile(name)))) return serverRefusal(n);
            }
            if (!directory && n.getFileName() != null && !SERVER_EXTENSIONS.contains(extension(n.getFileName().toString()))) return serverRefusal(n);
        }
        return null;
    }

    private static String serverRefusal(Path n) {
        return I18n.text("Pe server nu se scrie: {0}. Merg numai fisiere de productie ({1}); niciodata scripturi, .md, .log, nume care incep cu _ sau tmp, foldere OLD. Nu repeta si nu ocoli regula.", n, String.join(", ", SERVER_EXTENSIONS));
    }

    // ---- card text ----

    static String extensions(Collection<Path> files) {
        Map<String, Integer> counts = new TreeMap<>();
        for (Path f : files) {
            String e = f.getFileName() == null ? "" : extension(f.getFileName().toString());
            counts.merge(e.isEmpty() ? "-" : e, 1, Integer::sum);
        }
        StringBuilder b = new StringBuilder();
        counts.forEach((e, c) -> b.append(b.length() == 0 ? "" : ", ").append(e).append(" x").append(c));
        return b.toString();
    }

    static String list(Collection<Path> files) {
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (Path f : files) {
            if (++n > 30) break;
            b.append("- ").append(f).append('\n');
        }
        if (files.size() > 30) b.append(I18n.text("... si inca {0}", files.size() - 30)).append('\n');
        return b.toString().stripTrailing();
    }

    static String serverDetails(Collection<Path> files) {
        return I18n.text("Modificare pe server: {0} fisier(e) existente vor fi suprascrise, modificate sau sterse.\n{1}\nExtensii: {2}\nFisierele noi si copiile noi pe server merg fara confirmare; confirmarea apare de fiecare data pentru ce exista deja, inclusiv in modul Permite tot, si nu se retine. Pe server merg numai: {3}.",
                files.size(), list(files), extensions(files), String.join(", ", SERVER_EXTENSIONS));
    }

    // ---- 1.28.11: any shell or script text that writes where a server path appears ----

    // 1.28.13: plain copies and mkdir are not here any more: a copy to the server needs no card unless it overwrites an existing file (that is decided from the parsed
    // destination in Tools.guardCommand). What remains is what can overwrite, modify in place, rename or delete.
    private static final String VERBS = "move-item|remove-item|rename-item|new-item|set-item|clear-content|set-content|add-content|out-file|export-csv|export-clixml|tee-object"
            + "|move|del|erase|rmdir|rd|ren|rename|mv|mi|rm|ri|rni|ni|sc|ac|tee|attrib|icacls|takeown";
    private static final Pattern NET_WRITE = Pattern.compile("(?i)writeall(?:text|lines|bytes)|appendall(?:text|lines)"
            + "|\\.(?:write|writelines|write_text|write_bytes|to_csv|to_excel|save\\w*|unlink|touch|rename|mkdir|rmdir|truncate)\\s*\\("
            + "|\\bshutil\\.|\\bos\\.(?:remove|rename|replace|unlink|rmdir|removedirs|makedirs|mkdir|system|popen)\\b|\\bsubprocess\\b"
            + "|\\bopen\\s*\\([^)\\r\\n]*,\\s*(?:mode\\s*=\\s*)?[rbt]*['\"][rbt]*[wax+]|\\bcsv\\.(?:writer|DictWriter)"
            + "|\\[(?:system\\.)?io\\.(?:file|directory)\\]::(?:write|append|copy|move|delete|create|replace)"
            + "|(?<![\\w\\\\/.:$-])(?:" + VERBS + ")(?![\\w-])"
            + "|(?<![0-9&<>=-])>{1,2}(?![&>=]|\\s*nul\\b|\\s*\\$null\\b)");
    private static final Pattern NET_REF = Pattern.compile("(?<![\\w\\\\/:])(?:\\\\{2,4}|//)[\\w.$-]+(?:\\\\{1,2}|/)[^'\"<>|*?\\r\\n]*"
            + "|(?<!\\w)[A-Za-z]:(?:\\\\{1,2}|/)[^'\"<>|*?\\r\\n]*");
    private static final Pattern SCRIPT_REF = Pattern.compile("(?i)\"([^\"\\r\\n]+\\.(?:py|pyw|ps1|bat|cmd|vbs|js))\"|'([^'\\r\\n]+\\.(?:py|pyw|ps1|bat|cmd|vbs|js))'"
            + "|([^\\s\"'<>|&;()]+\\.(?:py|pyw|ps1|bat|cmd|vbs|js))(?![\\w.])");

    /** The command text plus the text of the scripts it runs (best effort: 8 scripts, 512 KB each). */
    static String withScripts(String command, Path cwd) {
        StringBuilder all = new StringBuilder(command);
        Set<Path> seen = new HashSet<>();
        Matcher m = SCRIPT_REF.matcher(command);
        while (m.find() && seen.size() < 8) {
            String raw = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
            Path p = resolve(raw, cwd);
            if (p == null || !seen.add(p)) continue;
            try {
                if (Files.isRegularFile(p) && Files.size(p) <= 524288) all.append('\n').append(new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                // unreadable script: the command text alone is analysed
            }
        }
        return all.toString();
    }

    /**
     * Server paths a command or the scripts it runs may write, overwrite, rename or delete: every server path in the text, when the text
     * also holds any write verb or API (WriteAllText, Set-Content, Copy-Item, open(...,'w'), shutil, >, del ...). Folders are expanded to
     * their files. Empty when nothing on a server is involved. Best effort and deliberately wide: a false alarm only costs a confirmation.
     */
    List<Path> networkWrites(String command, Path cwd) {
        if (!enabled || command == null || command.isBlank()) return List.of();
        String text = withScripts(command, cwd);
        if (!NET_WRITE.matcher(text).find()) return List.of();
        Set<Path> found = new LinkedHashSet<>();
        Matcher m = NET_REF.matcher(text);
        while (m.find() && found.size() < LIMIT) {
            String raw = m.group();
            boolean quoted = m.start() > 0 && (text.charAt(m.start() - 1) == '"' || text.charAt(m.start() - 1) == '\'');
            if (!quoted) for (int i = 0; i < raw.length(); i++) if (Character.isWhitespace(raw.charAt(i))) { raw = raw.substring(0, i); break; }
            raw = raw.replaceAll("[\\s),;]+$", "");
            String s = raw.replaceAll("\\\\{2,}", "\\\\").replace('/', '\\');
            if (raw.startsWith("\\")) s = "\\" + s;
            int var = Math.min(s.indexOf('$') < 0 ? Integer.MAX_VALUE : s.indexOf('$'), s.indexOf('%') < 0 ? Integer.MAX_VALUE : s.indexOf('%'));
            if (var != Integer.MAX_VALUE) s = s.substring(0, Math.max(0, s.lastIndexOf('\\', var)));
            Path p = null;
            for (String t = s; t.length() > 2 && p == null; ) {
                try {
                    Path c = Path.of(t);
                    if (Files.exists(c)) p = c;
                } catch (InvalidPathException e) {
                    break;
                }
                int sp = t.lastIndexOf(' ');
                if (p != null || sp < 0) break;
                t = t.substring(0, sp).stripTrailing();
            }
            if (p == null) {
                try {
                    p = Path.of(s);
                } catch (InvalidPathException e) {
                    continue;
                }
            }
            Path n = norm(p);
            if (!server(n)) continue;
            if (Files.isDirectory(n)) {
                int before = found.size();
                try {
                    Files.walkFileTree(n, EnumSet.noneOf(FileVisitOption.class), 3, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                            if (a.isRegularFile() && found.size() < LIMIT) found.add(f);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path f, IOException e) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
                } catch (IOException | RuntimeException e) {
                    // listing failed: the folder itself is shown
                }
                if (found.size() == before) found.add(n);
            } else found.add(n);
        }
        if (server(cwd)) found.add(norm(cwd));
        return new ArrayList<>(found);
    }

    // ---- stray helper files ----

    /** Helper files left in a project folder. Allowed files and the Output/Controle trees are never listed. */
    List<Path> strays(Path folder, int maxDepth) throws IOException {
        Path root = norm(folder);
        List<Path> found = new ArrayList<>();
        if (!enabled || !Files.isDirectory(root) || zone(root) != Zone.PROJECT) return found;
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), maxDepth, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (!dir.equals(root) && (name.equalsIgnoreCase("Output") || name.equalsIgnoreCase("Controle") || name.equalsIgnoreCase(".git"))) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (found.size() >= LIMIT || !attrs.isRegularFile() || attrs.isSymbolicLink() || allowed(file, false)) return FileVisitResult.CONTINUE;
                Path rel = root.relativize(file);
                boolean stray = helperFile(file.getFileName().toString());
                for (int i = 0; !stray && i < rel.getNameCount() - 1; i++) stray = helperDir(rel.getName(i).toString());
                if (stray) found.add(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        found.sort(Comparator.comparing(Path::toString, String.CASE_INSENSITIVE_ORDER));
        return found;
    }

    // ---- shell command analysis ----

    private static final class Lex {
        record Tok(String text, boolean quoted) {
        }

        final List<List<Tok>> out = new ArrayList<>();
        List<Tok> cur = new ArrayList<>();
        final StringBuilder t = new StringBuilder();
        boolean quoted, has;

        void flush() {
            if (has) cur.add(new Tok(t.toString(), quoted));
            t.setLength(0);
            has = false;
            quoted = false;
        }

        void end() {
            flush();
            if (!cur.isEmpty()) out.add(cur);
            cur = new ArrayList<>();
        }

        static List<List<Tok>> split(String s) {
            Lex x = new Lex();
            int i = 0, n = s.length();
            while (i < n) {
                char c = s.charAt(i);
                if (c == '"' || c == '\'') {
                    x.has = true;
                    x.quoted = true;
                    i++;
                    while (i < n) {
                        char d = s.charAt(i);
                        if (d == c) {
                            if (i + 1 < n && s.charAt(i + 1) == c) { x.t.append(c); i += 2; continue; }
                            break;
                        }
                        x.t.append(d);
                        i++;
                    }
                    i++;
                } else if (c == '\n' || c == '\r' || c == ';' || c == '|' || c == '(' || c == ')' || c == '&') {
                    x.end();
                    if ((c == '|' || c == '&') && i + 1 < n && s.charAt(i + 1) == c) i++;
                    i++;
                } else if (Character.isWhitespace(c)) {
                    x.flush();
                    i++;
                } else if (c == '>') {
                    if (x.has && !x.quoted && x.t.length() == 1 && Character.isDigit(x.t.charAt(0))) { x.t.setLength(0); x.has = false; }
                    x.flush();
                    i++;
                    if (i < n && s.charAt(i) == '>') i++;
                    if (i < n && s.charAt(i) == '&') {
                        i++;
                        while (i < n && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '-')) i++;
                    } else x.cur.add(new Tok(">", false));
                } else if (c == '<') {
                    x.flush();
                    x.cur.add(new Tok("<", false));
                    i++;
                } else {
                    x.t.append(c);
                    x.has = true;
                    i++;
                }
            }
            x.end();
            return x.out;
        }
    }

    /** Writes, directory creations and copies/moves that a shell command visibly performs. Unparseable cases are simply not reported. */
    static List<Op> parse(String command) {
        List<Op> ops = new ArrayList<>();
        scan(command == null ? "" : command, ops, 0);
        return ops;
    }

    private static void scan(String command, List<Op> ops, int depth) {
        for (List<Lex.Tok> segment : Lex.split(command)) segment(segment, ops, depth);
    }

    private static boolean nullDevice(String s) {
        String x = s.trim().toLowerCase(Locale.ROOT);
        return x.isEmpty() || x.equals("nul") || x.equals("$null") || x.equals("/dev/null") || x.equals("con") || x.equals("stdout") || x.equals("stderr");
    }

    private static String verb(String raw) {
        String v = raw.replace('\\', '/');
        v = v.substring(v.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return v.endsWith(".exe") ? v.substring(0, v.length() - 4) : v;
    }

    private static String join(List<Lex.Tok> tokens) {
        if (tokens.size() == 1) return tokens.get(0).text();
        StringBuilder b = new StringBuilder();
        for (Lex.Tok t : tokens) {
            if (b.length() > 0) b.append(' ');
            boolean wrap = t.text().isEmpty() || t.text().chars().anyMatch(Character::isWhitespace);
            b.append(wrap ? "\"" + t.text() + "\"" : t.text());
        }
        return b.toString();
    }

    private static int indexOf(List<Lex.Tok> tokens, String... names) {
        for (int i = 0; i < tokens.size(); i++)
            for (String name : names) if (!tokens.get(i).quoted() && tokens.get(i).text().equalsIgnoreCase(name)) return i;
        return -1;
    }

    private static String first(Map<String, String> named, String... keys) {
        for (String k : keys) if (named.containsKey(k)) return named.get(k);
        return null;
    }

    private static void segment(List<Lex.Tok> tokens, List<Op> ops, int depth) {
        List<Lex.Tok> words = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            Lex.Tok t = tokens.get(i);
            if (!t.quoted() && t.text().equals("<")) { i++; continue; }
            if (!t.quoted() && t.text().equals(">")) {
                if (i + 1 < tokens.size()) {
                    String target = tokens.get(++i).text();
                    if (!nullDevice(target)) ops.add(new Op(Kind.WRITE, null, target, List.of(), false));
                }
                continue;
            }
            words.add(t);
        }
        if (words.isEmpty()) return;
        String verb = verb(words.get(0).text());
        List<Lex.Tok> args = words.subList(1, words.size());
        if (depth < 3) {
            int k = verb.equals("cmd") ? indexOf(args, "/c", "/k") : verb.equals("powershell") || verb.equals("pwsh") ? indexOf(args, "-command", "-c") : -1;
            if (k >= 0) {
                if (k + 1 < args.size()) scan(join(args.subList(k + 1, args.size())), ops, depth + 1);
                return;
            }
        }
        Map<String, String> named = new HashMap<>();
        List<String> pos = new ArrayList<>();
        Set<String> flags = new HashSet<>();
        boolean cmdStyle = COPY_VERBS.contains(verb) || verb.equals("mkdir") || verb.equals("md");
        for (int i = 0; i < args.size(); i++) {
            Lex.Tok t = args.get(i);
            String x = t.text();
            if (!t.quoted() && x.length() > 1 && x.charAt(0) == '-' && Character.isLetter(x.charAt(1))) {
                String name = x.substring(1).toLowerCase(Locale.ROOT);
                int colon = name.indexOf(':');
                if (colon > 0 && colon < name.length() - 1) named.put(name.substring(0, colon), x.substring(colon + 2));
                else {
                    if (colon > 0) name = name.substring(0, colon);
                    if (VALUE_PARAMS.contains(name) && i + 1 < args.size()) named.put(name, args.get(++i).text());
                    else flags.add(name);
                }
            } else if (!t.quoted() && cmdStyle && x.length() > 1 && x.charAt(0) == '/' && !x.startsWith("//")) flags.add(x.toLowerCase(Locale.ROOT));
            else pos.add(x);
        }
        switch (verb) {
            case "out-file", "set-content", "add-content", "tee-object", "tee", "export-csv", "export-clixml" -> {
                String f = first(named, "filepath", "path", "literalpath");
                if (f == null && !pos.isEmpty()) f = pos.get(0);
                if (f != null && !nullDevice(f)) ops.add(new Op(Kind.WRITE, null, f, List.of(), false));
            }
            case "new-item", "ni" -> {
                String path = first(named, "path", "literalpath"), name = named.get("name");
                if (path == null && !pos.isEmpty()) path = pos.remove(0);
                String full = path == null ? name : name == null ? path : path.replaceAll("[\\\\/]+$", "") + "\\" + name;
                String type = Objects.toString(first(named, "itemtype", "type"), "").toLowerCase(Locale.ROOT);
                if (full != null) ops.add(new Op(type.startsWith("dir") || type.startsWith("fold") ? Kind.DIR : Kind.WRITE, null, full, List.of(), false));
            }
            case "mkdir", "md" -> {
                String path = first(named, "path");
                if (path != null) ops.add(new Op(Kind.DIR, null, path, List.of(), false));
                for (String p : pos) ops.add(new Op(Kind.DIR, null, p, List.of(), false));
            }
            default -> {
                if (!COPY_VERBS.contains(verb)) return;
                boolean robo = verb.equals("robocopy");
                boolean move = verb.startsWith("move") || verb.equals("mv") || verb.equals("mi") || (robo && (flags.contains("/mov") || flags.contains("/move")));
                String src, dst;
                List<String> rest = List.of();
                if (named.containsKey("destination")) {
                    src = first(named, "path", "literalpath");
                    if (src == null && !pos.isEmpty()) src = pos.get(0);
                    dst = named.get("destination");
                } else if (first(named, "path", "literalpath") != null) {
                    src = first(named, "path", "literalpath");
                    dst = pos.isEmpty() ? null : pos.get(0);
                } else {
                    src = pos.isEmpty() ? null : pos.get(0);
                    dst = pos.size() < 2 ? null : pos.get(1);
                    if (pos.size() > 2) rest = pos.subList(2, pos.size());
                }
                if (src == null || dst == null) return;
                boolean excludes = flags.stream().anyMatch(f -> f.startsWith("/x"));
                List<String> patterns = new ArrayList<>();
                if (robo && !excludes) for (String r : rest) if (r.contains("*") || r.contains("?") || r.contains(".")) patterns.add(r);
                boolean mirror = robo && (flags.contains("/mir") || flags.contains("/purge"));
                ops.add(new Op(move ? Kind.MOVE : Kind.COPY, src, dst, patterns, mirror));
            }
        }
    }

    /** Raw path text to a Path against cwd; null when it still holds an unexpanded variable or is not a valid path. */
    static Path resolve(String raw, Path cwd) {
        String s = Tools.expand(raw.trim());
        if (s.isEmpty() || s.indexOf('$') >= 0 || s.indexOf('%') >= 0) return null;
        try {
            Path p = Path.of(s);
            return (p.isAbsolute() ? p : cwd.resolve(p)).normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** Files a copy/move source stands for (wildcards, folders included); null when they cannot be listed. */
    static List<Item> sources(Op op, Path cwd) {
        String s = Tools.expand(op.source().trim());
        if (s.isEmpty() || s.indexOf('$') >= 0 || s.indexOf('%') >= 0) return null;
        int cut = Math.max(s.lastIndexOf('\\'), s.lastIndexOf('/'));
        String dirPart = cut >= 0 ? s.substring(0, cut + 1) : "", namePart = s.substring(cut + 1);
        List<Item> out = new ArrayList<>();
        try {
            Path dir = dirPart.isEmpty() ? cwd : resolve(dirPart, cwd);
            if (dir == null) return null;
            if (namePart.contains("*") || namePart.contains("?")) {
                PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + namePart);
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                    for (Path p : ds) if (m.matches(p.getFileName())) collect(p, op.patterns(), out);
                }
                return out;
            }
            Path p = namePart.isEmpty() ? dir : dir.resolve(namePart);
            if (!Files.exists(p)) return null;
            collect(p, op.patterns(), out);
            return out;
        } catch (IOException | InvalidPathException e) {
            return null;
        }
    }

    private static void collect(Path p, List<String> patterns, List<Item> out) throws IOException {
        if (Files.isDirectory(p)) {
            List<PathMatcher> matchers = new ArrayList<>();
            for (String g : patterns) matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + g));
            Files.walkFileTree(p, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                    boolean hit = matchers.isEmpty() || matchers.stream().anyMatch(m -> m.matches(f.getFileName()));
                    if (a.isRegularFile() && hit && out.size() < LIMIT) out.add(new Item(f, p.relativize(f), true));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path f, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } else out.add(new Item(p, p.getFileName(), false));
    }

    /** Where each source file lands; null when the destination is not a plain path. */
    static List<Path> planned(Op op, List<Item> sources, Path cwd) {
        Path dest = resolve(op.dest(), cwd);
        if (dest == null) return null;
        boolean folder = op.dest().endsWith("\\") || op.dest().endsWith("/") || Files.isDirectory(dest) || sources.size() > 1;
        List<Path> out = new ArrayList<>();
        for (Item i : sources) out.add(i.fromFolder() || folder ? dest.resolve(i.relative()) : dest);
        return out;
    }

    // ---- network drives ----

    /** Drive letters mapped to a share (from "net use"), cached for 30 s. Empty when unavailable. */
    static Set<Character> mappedDrives(String configured) {
        Set<Character> all = new HashSet<>();
        if (configured != null) for (String part : configured.split("[,;\\s]+")) if (!part.isBlank() && Character.isLetter(part.charAt(0))) all.add(Character.toUpperCase(part.charAt(0)));
        long now = System.currentTimeMillis();
        if (now - drivesAt > 30000) {
            Set<Character> found = new HashSet<>();
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                try {
                    Process p = new ProcessBuilder("cmd", "/c", "net use").redirectErrorStream(true).start();
                    String text;
                    try (var in = p.getInputStream()) { text = new String(in.readAllBytes()); }
                    p.waitFor(4, java.util.concurrent.TimeUnit.SECONDS);
                    Matcher m = Pattern.compile("(?m)\\b([A-Za-z]):\\s+\\\\\\\\").matcher(text);
                    while (m.find()) found.add(Character.toUpperCase(m.group(1).charAt(0)));
                } catch (IOException | InterruptedException | RuntimeException ignored) {
                    // Without net use only UNC paths and configured letters are protected.
                }
            }
            drivesCache = found;
            drivesAt = now;
        }
        all.addAll(drivesCache);
        return all;
    }
}