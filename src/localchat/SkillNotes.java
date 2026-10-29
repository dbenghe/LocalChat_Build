package localchat;

/**
 * Generic, repo-side adaptation notes appended to read_skill output for specific skills (1.28.5; compact form 1.28.6).
 * They never edit the skill files and carry no production data: a skill written for an older toolchain
 * keeps its text, and the model additionally learns how that toolchain maps to the tools LocalChat has.
 * Only the read_skill result carries a note; the base system prompt is unchanged.
 * A copy of the skill that already points to the pdfcontrol-octop skill (the adapted local copy) gets only the part its text cannot know; any other copy gets the full note.
 */
final class SkillNotes {
    private SkillNotes() {}

    static final String MARKER = "LOCALCHAT_ADAPTATION";

    private static final String HEADER = "\n\n" + MARKER + " (generic note added by LocalChat; the skill text above is unchanged):\n";

    /** Toolchain mapping: only for a skill text that still describes the old webapp. */
    private static final String LEGACY_TOOLCHAIN = "- The old standalone PDFCONTROL webapp is NOT used here: C:\\PDFCONTROL, mbs_webapp.py and the other mbs_*.py modules, START_WEBAPP.bat, 127.0.0.1:8756 and its /api/* calls, direct start_wapening calls. "
        + "Do not start it and do not import its modules with run_command.\n"
        + "- Use the built-in pdfcontrol_* tools: pdfcontrol_status (readiness), pdfcontrol_list_projects, pdfcontrol_project_files and pdfcontrol_read_document (inventory, reading); "
        + "pdfcontrol_start_check (OCR check), pdfcontrol_start_extract (Extract PDF), pdfcontrol_start_pairs (V/W pairs), pdfcontrol_start_compare (OUD/NIEUW), pdfcontrol_start_workflow (complete flow), "
        + "then pdfcontrol_job_status, pdfcontrol_job_summary, pdfcontrol_job_findings, pdfcontrol_cancel_job; pdfcontrol_rapporten and pdfcontrol_regenerate_report for reports; "
        + "pdfcontrol_catalogue_status for the factory catalogue; pdfcontrol_compare_import and pdfcontrol_prepare_fast_import for import work; pdfcontrol_compare_server_revisions (read-only: server project folder versus the downloaded one, newer/same/older revision per drawing); pdfcontrol_fix_missing_pairs when a V drawing has no W pair (reads the mesh number on the drawing and copies that W from the final/separate drawings under the pair name). "
        + "Pass relative paths exactly as pdfcontrol_project_files lists them.\n";

    /** Always useful: what the skill text cannot know about this installation. */
    private static final String COMMON = "- Publishing to ChatGPT projects/conversations is NOT integrated in LocalChat. Mark that step pending_cloud_transfer: keep the local project notes and Pentru_ChatGPT_<number>.txt up to date, "
        + "report the step as pending and never claim it was published.\n"
        + "- Copy, network (UNC) and archive steps run through run_command and the file tools under the normal access control and approvals; never assume server access.\n";

    /** Marker of a skill text that already refers to the pdfcontrol-octop skill, so its toolchain text is up to date. */
    static final String ADAPTED_TEXT = "pdfcontrol-octop";

    private static boolean applies(String name) {
        return name != null && name.strip().equalsIgnoreCase("productie-projecten");
    }

    /** The full note for a skill (an unadapted copy), or an empty string when LocalChat has none. */
    static String forSkill(String name) {
        return applies(name) ? HEADER + LEGACY_TOOLCHAIN + COMMON : "";
    }

    /** The note for a skill given its text: the compact form when the text already maps to pdfcontrol_*, otherwise the full note. */
    static String forSkill(String name, String skillText) {
        if (!applies(name)) return "";
        return skillText != null && skillText.contains(ADAPTED_TEXT) ? HEADER + COMMON : forSkill(name);
    }
}
