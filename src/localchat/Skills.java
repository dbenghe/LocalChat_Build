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

    static List<Skill> load(Path dir) {
        List<Skill> out = new ArrayList<>();
        try {
            Files.createDirectories(dir);
            unpackZips(dir);
            try (Stream<Path> files = Files.walk(dir, 4)) {
                files.filter(p -> p.getFileName().toString().equalsIgnoreCase("SKILL.md")).sorted().forEach(p -> out.add(parse(p)));
            }
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
        StringBuilder b = new StringBuilder("Skill: " + skill.name() + "\nFolder: " + dir + "\n\n");
        b.append(translatePaths.apply(Files.readString(file, StandardCharsets.UTF_8)));
        List<String> others = new ArrayList<>();
        try (Stream<Path> files = Files.walk(dir, 3)) {
            files.filter(Files::isRegularFile).filter(p -> !p.equals(file)).forEach(p -> others.add(dir.relativize(p).toString()));
        }
        if (!others.isEmpty()) {
            b.append("\n\nAlte fisiere ale skill-ului (citeste-le cu read_file):\n");
            for (String o : others) b.append("- ").append(dir.resolve(o)).append('\n');
        }
        return b.toString();
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
                    if (value.isEmpty() || value.matches("[>|][-+]?")) {
                        // folded / multi-line YAML value: the indented lines that follow
                        StringBuilder b = new StringBuilder();
                        while (i + 1 < lines.length && (lines[i + 1].startsWith(" ") || lines[i + 1].startsWith("\t"))) {
                            if (b.length() > 0) b.append(' ');
                            b.append(lines[++i].trim());
                        }
                        value = b.toString();
                    }
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
