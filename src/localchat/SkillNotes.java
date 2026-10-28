package localchat;

/**
 * Generic, repo-side adaptation notes appended to read_skill output for specific skills (1.28.5).
 * They never edit the skill files and carry no production data: a skill written for an older toolchain
 * keeps its text, and the model additionally learns how that toolchain maps to the tools LocalChat has.
 * Only the read_skill result carries a note; the base system prompt is unchanged.
 */
final class SkillNotes {
    private SkillNotes() {}

    static final String MARKER = "LOCALCHAT_ADAPTATION";

    private static final String PRODUCTIE_PROJECTEN = "\n\n" + MARKER + " (generic note added by LocalChat; the skill text above is unchanged):\n"
        + "- The old standalone PDFCONTROL webapp is NOT used here: C:\\PDFCONTROL, mbs_webapp.py and the other mbs_*.py modules, START_WEBAPP.bat, 127.0.0.1:8756 and its /api/* calls, direct start_wapening calls. "
        + "Do not start it and do not import its modules with run_command.\n"
        + "- Use the built-in pdfcontrol_* tools: pdfcontrol_status (readiness), pdfcontrol_list_projects, pdfcontrol_project_files and pdfcontrol_read_document (inventory, reading); "
        + "pdfcontrol_start_check (OCR check), pdfcontrol_start_extract (Extract PDF), pdfcontrol_start_pairs (V/W pairs), pdfcontrol_start_compare (OUD/NIEUW), pdfcontrol_start_workflow (complete flow), "
        + "then pdfcontrol_job_status, pdfcontrol_job_summary, pdfcontrol_job_findings, pdfcontrol_cancel_job; pdfcontrol_rapporten and pdfcontrol_regenerate_report for reports; "
        + "pdfcontrol_catalogue_status for the factory catalogue; pdfcontrol_compare_import and pdfcontrol_prepare_fast_import for import work. "
        + "Pass relative paths exactly as pdfcontrol_project_files lists them.\n"
        + "- Publishing to ChatGPT projects/conversations is NOT integrated in LocalChat. Mark that step pending_cloud_transfer: keep the local project notes and Pentru_ChatGPT_<number>.txt up to date, "
        + "report the step as pending and never claim it was published.\n"
        + "- Copy, network (UNC) and archive steps run through run_command and the file tools under the normal access control and approvals; never assume server access.\n";

    /** The note for a skill, or an empty string when LocalChat has none. */
    static String forSkill(String name) {
        return name != null && name.strip().equalsIgnoreCase("productie-projecten") ? PRODUCTIE_PROJECTEN : "";
    }
}
