package localchat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Skills: folders with a SKILL.md (same layout as Claude skills). A .zip dropped in the skills folder is unpacked. */
final class Skills {
    record Skill(String name, String description, Path file) {
    }

    private Skills() {
    }

    /** Last modified time and size of one scanned path (size -1 for folders): enough to notice any change. */
    private record Stamp(long time, long size) {
    }

    private record Cached(List<Skill> skills, java.util.Map<Path, Stamp> stamps) {
    }

    private static final java.util.Map<Path, Cached> CACHE = new java.util.HashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger SCANS = new java.util.concurrent.atomic.AtomicInteger();

    /** Full folder scans since start (tests and diagnostics). */
    static int scans() {
        return SCANS.get();
    }

    /** The installed skills. Repeated calls reuse the last scan until a folder or SKILL.md under it changes. */
    static List<Skill> load(Path dir) {
        return load(dir, false);
    }

    /** Rescans the folder even when nothing seems to have changed (the /skills command, reloading tools and settings). */
    static List<Skill> reload(Path dir) {
        return load(dir, true);
    }

    /** Forgets every cached scan; the next load rescans. */
    static synchronized void invalidate() {
        CACHE.clear();
    }

    private static Stamp stamp(Path p) throws IOException {
        java.nio.file.attribute.BasicFileAttributes a = Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class);
        return new Stamp(a.lastModifiedTime().toMillis(), a.isDirectory() ? -1 : a.size());
    }

    private static boolean fresh(Cached cached) {
        try {
            for (java.util.Map.Entry<Path, Stamp> e : cached.stamps().entrySet()) if (!stamp(e.getKey()).equals(e.getValue())) return false;
            return true;
        } catch (IOException | RuntimeException gone) {
            return false;
        }
    }

    private static synchronized List<Skill> load(Path dir, boolean force) {
        Path key = dir.toAbsolutePath().normalize();
        Cached cached = CACHE.get(key);
        if (!force && cached != null && fresh(cached)) return new ArrayList<>(cached.skills());
        CACHE.remove(key);
        SCANS.incrementAndGet();
        List<Skill> out = new ArrayList<>();
        try {
            Files.createDirectories(dir);
            unpackZips(dir);
            java.util.Map<Path, Stamp> stamps = new java.util.HashMap<>();
            List<Path> manifests;
            try (Stream<Path> files = Files.walk(dir, 4)) {
                List<Path> all = files.toList();
                // Stamped before parsing: a change in between only causes one more scan, never a stale list.
                for (Path p : all) if (Files.isDirectory(p) || p.getFileName().toString().equalsIgnoreCase("SKILL.md")) stamps.put(p, stamp(p));
                manifests = all.stream().filter(p -> p.getFileName().toString().equalsIgnoreCase("SKILL.md")).sorted().toList();
            }
            for (Path p : manifests) out.add(parse(p));
            CACHE.put(key, new Cached(List.copyOf(out), stamps));
        } catch (IOException ignored) {
            // no skills folder (read-only medium): run without skills
        }
        return out;
    }

    /** The skill's text, with paths written for another computer translated to this one. */
    static String read(List<Skill> skills, String name, UnaryOperator<String> translatePaths) throws IOException {
        Skill skill = null;
        for (Skill s : skills) if (s.name().equalsIgnoreCase(name.trim())) skill = s;
        if (skill == null) {
            List<String> names = new ArrayList<>();
            for (Skill s : skills) names.add(s.name());
            return "Nu exista skill-ul '" + name + "'. Disponibile: " + (names.isEmpty() ? "niciunul" : String.join(", ", names)) + ".";
        }
        Path file = skill.file();
        Path dir = file.getParent();
        StringBuilder b = new StringBuilder("Skill: " + skill.name() + "\nFolder: " + dir + "\nSKILL_FILE: " + file + "\nSKILL_RESOURCES:\n");
        try (Stream<Path> files = Files.walk(dir, 3)) {
            files.filter(Files::isRegularFile).filter(p -> !p.equals(file)).sorted()
                .forEach(p -> b.append("- ").append(p).append('\n'));
        }
        b.append("\nSKILL_CONTENT:\n").append(translatePaths.apply(Files.readString(file, StandardCharsets.UTF_8)));

        return b.toString();
    }

    /** Keep exact installed paths visible when a repeated skill load is suppressed. */
    static String resourceIndex(String loaded) {
        int boundary=loaded.indexOf("\nSKILL_CONTENT:\n");
        String index=boundary>=0?loaded.substring(0,boundary):loaded.lines().limit(3).collect(java.util.stream.Collectors.joining("\n"));
        return index.length()>6000?index.substring(0,6000):index;
    }

    /** Recover only a misplaced immediate references/file when the exact root file exists. */
    static Path misplacedReference(List<Skill> skills,Path requested) {
        if(Files.exists(requested,java.nio.file.LinkOption.NOFOLLOW_LINKS))return null;
        for(Skill skill:skills)try {
            Path dir=skill.file().toAbsolutePath().normalize().getParent();
            if(!dir.resolve("references").equals(requested.getParent()))continue;
            Path actual=dir.resolve(requested.getFileName());
            if(Files.isRegularFile(actual)&&actual.toRealPath().startsWith(dir.toRealPath()))return actual;
        }catch(IOException ignored){}
        return null;
    }

    private static Skill parse(Path file) {
        String name = null;
        String description = "";
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.startsWith("﻿")) text = text.substring(1);
            if (text.startsWith("---")) {
                int end = text.indexOf("\n---", 3);
                String[] lines = end > 0 ? text.substring(3, end).split("\r?\n") : new String[0];
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i];
                    int colon = line.indexOf(':');
                    if (colon <= 0 || Character.isWhitespace(line.charAt(0))) continue;
                    String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                    String value = line.substring(colon + 1).trim();
                    // Folded/literal blocks (">", "|", or empty) and plain or quoted scalars wrapped over several
                    // lines: the indented lines that follow belong to the same value.
                    StringBuilder b = new StringBuilder(value.isEmpty() || value.matches("[>|][-+]?") ? "" : value);
                    while (i + 1 < lines.length && (lines[i + 1].startsWith(" ") || lines[i + 1].startsWith("\t"))) {
                        if (b.length() > 0) b.append(' ');
                        b.append(lines[++i].trim());
                    }
                    value = b.toString();
                    if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    if (key.equals("name")) name = value;
                    else if (key.equals("description")) description = value;
                }
            }
        } catch (IOException ignored) {
            // listed without a description
        }
        if (name == null || name.isBlank()) name = file.getParent().getFileName().toString();
        return new Skill(name, description, file);
    }

    private static void unpackZips(Path dir) throws IOException {
        List<Path> zips = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")).forEach(zips::add);
        }
        for (Path zipPath : zips) {
            String base = zipPath.getFileName().toString();
            Path target = dir.resolve(base.substring(0, base.length() - 4)).normalize();
            if (Files.exists(target)) continue;
            try (ZipFile zip = new ZipFile(zipPath.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    Path out = target.resolve(e.getName()).normalize();
                    if (!out.startsWith(target)) continue; // entry tries to escape the folder
                    if (e.isDirectory()) {
                        Files.createDirectories(out);
                    } else {
                        Files.createDirectories(out.getParent());
                        try (InputStream in = zip.getInputStream(e)) {
                            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            }
        }
    }
}
