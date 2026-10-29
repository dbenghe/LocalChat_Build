package localchat;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 1.28.13: defaults for the pdfcontrol_start_* tools when the model omits them, so a request such as "control the drawings"
 * needs no typed project number and no hand-picked PDFs. The project comes from the working folder; the files are the
 * original drawings of that project (never OLD, Controle, Output, archive, helper folders), one path per file name
 * because the engine rejects duplicate names in one batch. Compare uses the project's OUD and NIEUW folders.
 */
final class PdfAutoFill {
    static final int MAX_FILES = 500;
    private static final Pattern SKIP_DIR = Pattern.compile("(?i)^(controle|output|old|oud|archive|archief|backup|[_.].*|tmp.*|temp.*)$");
    private static final Pattern FINAL_DIR = Pattern.compile("(?i).*(final|finale|definitiv|definitief|separat|separate|gescheiden|individual|losse|los).*");
    static final Set<String> START_TOOLS = Set.of("pdfcontrol_start_check", "pdfcontrol_start_extract", "pdfcontrol_start_pairs", "pdfcontrol_start_workflow");

    private PdfAutoFill() {}

    /** Tools whose project and file arguments may be omitted. */
    static boolean defaults(String tool) {return START_TOOLS.contains(tool) || tool.equals("pdfcontrol_start_compare");}

    /** The same schema without project/files in "required", so a model may omit them. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> optional(Map<String, Object> schema) {
        Map<String, Object> copy = new LinkedHashMap<>(schema);
        Object required = copy.get("required");
        if (required instanceof List<?> list) {
            List<Object> kept = new ArrayList<>();
            for (Object name : list) if (!Set.of("project", "files", "old_files", "new_files").contains(String.valueOf(name))) kept.add(name);
            copy.put("required", kept);
        }
        return copy;
    }

    /** Last six-digit folder name of the working folder, or null. */
    static String project(Path workDir) {
        String found = null;
        if (workDir != null) for (Path segment : workDir) if (segment.toString().matches("[0-9]{6}")) found = segment.toString();
        return found;
    }

    private static List<String> sourcePdfs(List<?> rows) {
        List<String> out = new ArrayList<>();
        for (Object row : rows) {
            String path = Json.str(row, "path");
            if (path != null && Boolean.TRUE.equals(Json.get(row, "pdf_source")) && path.toLowerCase(Locale.ROOT).endsWith(".pdf")) out.add(path.replace('\\', '/'));
        }
        return out;
    }

    private static int rank(String path) {
        String[] parts = path.split("/");
        int score = parts.length * 10;
        for (int i = 0; i < parts.length - 1; i++) if (FINAL_DIR.matcher(parts[i]).matches()) score -= 100;
        return score;
    }

    /** One path per file name; a final/separate folder, then the shallowest path, wins. */
    static List<String> uniqueByName(List<String> paths) {
        Map<String, String> best = new TreeMap<>();
        for (String path : paths) {
            String key = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT), old = best.get(key);
            if (old == null || rank(path) < rank(old) || rank(path) == rank(old) && path.compareToIgnoreCase(old) < 0) best.put(key, path);
        }
        List<String> out = new ArrayList<>(best.values());
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private static boolean skipped(String path) {
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 1; i++) if (SKIP_DIR.matcher(parts[i]).matches()) return true;
        return false;
    }

    /** Rows of pdfcontrol_project_files to the original drawings of the project; empty when none. */
    static List<String> drawings(List<?> rows) {
        List<String> paths = new ArrayList<>();
        for (String path : sourcePdfs(rows)) if (!skipped(path)) paths.add(path);
        return uniqueByName(paths);
    }

    /** [old, new] from the engine's OUD and NIEUW folders (an OLD archive folder is not a comparison source) of the project; null when either side is empty. */
    static List<List<String>> compareDefaults(List<?> rows) {
        List<String> oldFiles = new ArrayList<>(), newFiles = new ArrayList<>();
        for (String path : sourcePdfs(rows)) {
            String[] parts = path.split("/");
            boolean generated = false, old = false, fresh = false;
            for (int i = 0; i < parts.length - 1; i++) {
                String p = parts[i].toLowerCase(Locale.ROOT);
                if (p.equals("controle") || p.equals("output")) generated = true;
                else if (p.equals("oud")) old = true;
                else if (p.equals("nieuw")) fresh = true;
            }
            if (generated || old == fresh) continue;
            (old ? oldFiles : newFiles).add(path);
        }
        oldFiles = uniqueByName(oldFiles);
        newFiles = uniqueByName(newFiles);
        return oldFiles.isEmpty() || newFiles.isEmpty() ? null : List.of(oldFiles, newFiles);
    }

    /** Fills a missing project (from the working folder) and missing files (all original drawings). Throws a clear message when it cannot. */
    static Map<String, Object> fill(String tool, Map<String, Object> args, Path workDir, java.util.function.BiFunction<String, Map<String, Object>, Object> call) throws Exception {
        boolean compare = tool.equals("pdfcontrol_start_compare");
        if (!compare && !START_TOOLS.contains(tool)) return args;
        Map<String, Object> out = new LinkedHashMap<>(args);
        Object given = out.get("project");
        if (given == null || String.valueOf(given).isBlank()) {
            String project = project(workDir);
            if (project == null) throw new IllegalArgumentException("project is required: the working folder has no six-digit project folder. Ask which project, or pdfcontrol_list_projects.");
            out.put("project", project);
        }
        String project = String.valueOf(out.get("project"));
        boolean missing = compare ? isEmpty(out.get("old_files")) || isEmpty(out.get("new_files")) : isEmpty(out.get("files"));
        if (!missing) return out;
        List<Object> rows = new ArrayList<>();
        Object offset = 0;
        for (int page = 0; page < 400; page++) {
            Object result = call.apply("pdfcontrol_project_files", Json.obj("project", project, "offset", offset));
            Object part = Json.get(result, "files");
            if (part instanceof List) rows.addAll((List<?>) part);
            offset = Json.get(result, "next_offset");
            if (!(offset instanceof Number)) break;
        }
        if (compare) {
            List<List<String>> sides = compareDefaults(rows);
            if (sides == null || sides.get(0).size() > MAX_FILES || sides.get(1).size() > MAX_FILES)
                throw new IllegalArgumentException("old_files/new_files are required: project " + project + " has no usable OUD and NIEUW folders. List the files with pdfcontrol_project_files and pass exact paths.");
            out.put("old_files", sides.get(0));
            out.put("new_files", sides.get(1));
        } else {
            List<String> files = drawings(rows);
            if (files.isEmpty()) throw new IllegalArgumentException("files is required: project " + project + " has no original PDF drawings outside OLD/Controle/Output.");
            if (files.size() > MAX_FILES) throw new IllegalArgumentException("files is required: project " + project + " has " + files.size() + " PDFs, over the limit of " + MAX_FILES + " per job. Pass a subset from pdfcontrol_project_files.");
            out.put("files", files);
        }
        return out;
    }

    private static boolean isEmpty(Object value) {
        return value == null || value instanceof List<?> list && list.isEmpty() || value instanceof String text && text.isBlank();
    }
}
