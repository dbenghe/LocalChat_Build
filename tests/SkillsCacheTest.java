package localchat;

import java.io.OutputStream;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Skills discovery is cached between messages and invalidated by file changes, /skills and reloads. */
public final class SkillsCacheTest {
    static int checks;
    static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    static void skill(Path root, String name, String description) throws Exception {
        Files.createDirectories(root.resolve(name).resolve("references"));
        Files.writeString(root.resolve(name).resolve("SKILL.md"), "---\nname: " + name + "\ndescription: " + description + "\n---\nBody of " + name + "\n");
        Files.writeString(root.resolve(name).resolve("references").resolve("notes.md"), "reference of " + name);
    }
    static List<String> names(List<Skills.Skill> list) { return list.stream().map(Skills.Skill::name).toList(); }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("localchat.home"))), "skills-cache-");
        Path dir = home.resolve("skills");
        for (int n = 0; n < 12; n++) skill(dir, "skill-" + String.format("%02d", n), "Fixture " + n);
        int base = Skills.scans();
        long coldStart = System.nanoTime();
        var first = Skills.load(dir);
        long cold = System.nanoTime() - coldStart;
        check(first.size() == 12 && Skills.scans() == base + 1, "first load scans once");
        long warm = 0;
        for (int n = 0; n < 50; n++) { long t = System.nanoTime(); var again = Skills.load(dir); warm += System.nanoTime() - t; check(again.equals(first), "cached list is identical"); }
        check(Skills.scans() == base + 1, "fifty further loads cause no rescan");
        System.out.println("skills scan " + cold / 1000 + " us, cached load average " + warm / 50 / 1000 + " us");
        var mutable = Skills.load(dir); mutable.clear();
        check(Skills.load(dir).size() == 12, "callers cannot damage the cache");

        // Content change (same size, later time), size change, new and removed skills, drop-in zip.
        Path file = dir.resolve("skill-03").resolve("SKILL.md");
        String text = Files.readString(file);
        Files.writeString(file, text.replace("Fixture 3", "Fixture X"));
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5000));
        var changed = Skills.load(dir);
        check(Skills.scans() == base + 2 && changed.stream().anyMatch(s -> s.description().equals("Fixture X")), "an edited SKILL.md is picked up");
        Skills.load(dir); check(Skills.scans() == base + 2, "and cached again afterwards");
        skill(dir, "added", "Newly added");
        check(Skills.load(dir).size() == 13 && Skills.scans() == base + 3, "a new skill folder is picked up");
        Files.walk(dir.resolve("added")).sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        check(Skills.load(dir).size() == 12 && Skills.scans() == base + 4, "a removed skill disappears");
        Path nested = dir.resolve("skill-05").resolve("references").resolve("deeper"); Files.createDirectories(nested); Files.writeString(nested.resolve("SKILL.md"), "---\nname: nested\ndescription: Deep\n---\n");
        check(names(Skills.load(dir)).contains("nested"), "a skill added below an existing skill is found");
        int afterNested = Skills.scans();
        Thread.sleep(80); // directory timestamps are coarse; the zip must land in a later tick than the scan above (intermittent failure otherwise)
        Path zip = dir.resolve("packed.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) { out.putNextEntry(new ZipEntry("packed/SKILL.md")); out.write("---\nname: packed\ndescription: From zip\n---\n".getBytes("UTF-8")); out.closeEntry(); }
        check(names(Skills.load(dir)).contains("packed") && Skills.scans() == afterNested + 1, "a dropped zip is unpacked and listed");
        int afterZip = Skills.scans();
        Skills.load(dir); check(Skills.scans() == afterZip, "unpacked zip result is cached");

        // Explicit invalidation: /skills, reloading tools or settings, and a different folder.
        Skills.reload(dir); check(Skills.scans() == afterZip + 1, "reload always rescans");
        Skills.load(dir); check(Skills.scans() == afterZip + 1, "cached after reload");
        Skills.invalidate(); Skills.load(dir); check(Skills.scans() == afterZip + 2, "invalidate drops every cached scan");
        Path other = home.resolve("other"); skill(other, "solo", "Another folder");
        check(names(Skills.load(other)).equals(List.of("solo")) && names(Skills.load(dir)).size() > 1, "folders are cached separately");
        int both = Skills.scans(); Skills.load(other); Skills.load(dir); check(Skills.scans() == both, "both stay cached");
        Files.delete(other.resolve("solo").resolve("SKILL.md"));
        check(Skills.load(other).isEmpty(), "a deleted SKILL.md is noticed");
        System.out.println("PASS: " + checks + " skills cache checks");
        System.exit(0);
    }
}
