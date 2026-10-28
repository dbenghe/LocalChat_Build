package localchat;

import java.nio.file.*;
import java.util.*;

/** 1.28.5: the productie-projecten skill gets a generic LocalChat mapping on read_skill only; the skill files and the base prompt are untouched. 1.28.6: an adapted copy gets the compact note. */
public final class SkillNotesTest {
    static int checks;
    static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    static Path skill(Path root, String name) throws Exception {
        Files.createDirectories(root.resolve(name));
        Path file = root.resolve(name).resolve("SKILL.md");
        Files.writeString(file, "---\nname: " + name + "\ndescription: Fixture " + name + "\n---\nLegacy steps for " + name + ": start START_WEBAPP.bat then call mbs_webapp.\n");
        return file;
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("localchat.home"))), "skill-notes-");
        Path dir = home.resolve("skills");
        Path legacy = skill(dir, "productie-projecten");
        skill(dir, "other-skill");
        String before = Files.readString(legacy);
        var skills = Skills.load(dir);

        String note = Skills.read(skills, "productie-projecten", s -> s);
        check(note.contains("SKILL_CONTENT:\n---\nname: productie-projecten") && note.contains("Legacy steps for productie-projecten"), "the skill text is delivered first and unchanged");
        check(note.contains(SkillNotes.MARKER), "the adaptation note is appended on read_skill");
        check(note.indexOf(SkillNotes.MARKER) > note.indexOf("SKILL_CONTENT:"), "the note follows the skill text");
        for (String needed : List.of("START_WEBAPP.bat", "127.0.0.1:8756", "C:\\PDFCONTROL", "mbs_webapp.py", "pdfcontrol_status", "pdfcontrol_list_projects", "pdfcontrol_project_files",
            "pdfcontrol_start_check", "pdfcontrol_start_extract", "pdfcontrol_start_pairs", "pdfcontrol_start_compare", "pdfcontrol_start_workflow", "pdfcontrol_job_summary", "pdfcontrol_rapporten",
            "pdfcontrol_compare_import", "pending_cloud_transfer", "NOT integrated", "run_command", "UNC"))
            check(note.contains(needed), "note mentions " + needed);
        check(Skills.read(skills, "Productie-Projecten ", s -> s).contains(SkillNotes.MARKER), "the lookup stays case-insensitive");
        String other = Skills.read(skills, "other-skill", s -> s);
        check(!other.contains(SkillNotes.MARKER) && !other.contains("pending_cloud_transfer"), "other skills get no note");
        check(SkillNotes.forSkill("other-skill").isEmpty() && SkillNotes.forSkill(null).isEmpty(), "no note for other names or null");
        check(SkillNotes.forSkill("productie-projecten").length() < 2300, "the note stays token-cheap: " + SkillNotes.forSkill("productie-projecten").length() + " chars");
        check(Files.readString(legacy).equals(before), "the skill file itself is never rewritten");
        check(Skills.resourceIndex(note).indexOf(SkillNotes.MARKER) < 0, "the resource index kept for repeated loads carries no note");

        // 1.28.6: an adapted copy (its text already points to pdfcontrol-octop) gets only the compact note.
        Path dir2 = home.resolve("skills-adapted");
        Files.createDirectories(dir2.resolve("productie-projecten"));
        Path adapted = dir2.resolve("productie-projecten").resolve("SKILL.md");
        Files.writeString(adapted, "---\nname: productie-projecten\ndescription: Adapted fixture\n---\nExecuta cu uneltele pdfcontrol_* ; apelurile urmeaza skillul pdfcontrol-octop.\n");
        String adaptedBefore = Files.readString(adapted);
        String compact = Skills.read(Skills.load(dir2), "productie-projecten", s -> s);
        check(compact.contains("SKILL_CONTENT:\n---\nname: productie-projecten") && compact.contains("urmeaza skillul pdfcontrol-octop"), "the adapted skill text is delivered first and unchanged");
        check(compact.contains(SkillNotes.MARKER) && compact.indexOf(SkillNotes.MARKER) > compact.indexOf("SKILL_CONTENT:"), "the compact note still follows the skill text");
        check(compact.contains("pending_cloud_transfer") && compact.contains("NOT integrated") && compact.contains("run_command") && compact.contains("UNC"), "the compact note keeps what the text cannot know");
        for (String gone : List.of("START_WEBAPP.bat", "127.0.0.1:8756", "mbs_webapp.py", "pdfcontrol_start_extract", "pdfcontrol_compare_import", "start_wapening"))
            check(!compact.contains(gone), "the compact note drops the redundant toolchain text: " + gone);
        int compactLength = compact.length() - compact.indexOf(SkillNotes.MARKER);
        check(compactLength < note.length() - note.indexOf(SkillNotes.MARKER) - 800 && compactLength < 900, "the compact note is much cheaper: " + compactLength + " chars");
        check(SkillNotes.forSkill("productie-projecten", "no pointer here").equals(SkillNotes.forSkill("productie-projecten")), "a text without the pointer keeps the full note");
        check(SkillNotes.forSkill("productie-projecten", null).equals(SkillNotes.forSkill("productie-projecten")), "no text keeps the full note");
        check(SkillNotes.forSkill("other-skill", "pdfcontrol-octop").isEmpty(), "other skills still get no note");
        check(Files.readString(adapted).equals(adaptedBefore), "the adapted skill file is never rewritten");
        check(Skills.resourceIndex(compact).indexOf(SkillNotes.MARKER) < 0, "the resource index carries no compact note");

        // Not in the base prompt.
        LocalChat[] app = {null};
        FeatureTest.edt(() -> { try { app[0] = AttachmentTest.window(home.resolve("ui"), "http://127.0.0.1:9/v1"); } catch (Exception e) { throw new RuntimeException(e); } });
        String[] prompt = {null};
        FeatureTest.edt(() -> { try { prompt[0] = (String) FeatureTest.call(app[0], "agentPrompt", new Class<?>[] {Path.class, List.class, PathMap.class}, home, skills, PathMap.none()); } catch (Exception e) { throw new RuntimeException(e); } });
        FeatureTest.edt(() -> app[0].dispose());
        check(prompt[0] != null && prompt[0].contains("productie-projecten"), "the skill is listed in the base prompt");
        check(!prompt[0].contains(SkillNotes.MARKER) && !prompt[0].contains("pending_cloud_transfer") && !prompt[0].contains("127.0.0.1:8756"), "the base prompt carries no adaptation text");
        System.out.println("SkillNotesTest OK: " + checks + " checks, note " + SkillNotes.forSkill("productie-projecten").length() + " chars, base prompt " + prompt[0].length() + " chars");
        System.exit(0);
    }
}
