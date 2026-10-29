package localchat;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Relevance-based tool sets for MBS requests. File-changing tools and the command tool stay hidden until the task needs
 * them; the result is derived statelessly from the conversation, so it is deterministic and only grows within a
 * conversation (stable prefix, no repeated cache or anchor invalidation).
 */
final class ToolRelevance {
    static final String ENABLE = "localchat_enable_tools";
    static final String FILES = "files";
    static final String COMMAND = "command";
    static final List<String> GROUPS = List.of(FILES, COMMAND);
    private static final List<String> FILE_TOOLS = List.of("write_file", "append_file", "edit_file", "make_dir", "copy_path", "move_path",
            "extract_attachments", "save_rule", "pdfcontrol_prepare_fast_import", "pdfcontrol_fix_missing_pairs", "pdfcontrol_compare_server_revisions", "download_url", "list_stray_files", "cleanup_stray_files");
    private static final List<String> COMMAND_TOOLS = List.of("run_command");
    /** Word-start stems (ASCII, lower case) in Romanian, Dutch, English, Spanish, Polish and Turkish. */
    private static final List<String> FILE_STEMS = List.of(
            "scrie", "scrii", "salva", "salveaz", "creeaz", "creaz", "modific", "editeaz", "copia", "copie", "copii", "muta", "mutare", "redenum", "adaug", "inlocui", "exporta", "retine", "memoreaz", "tine minte", "atasament", "fast", "descarc", "descarg", "download", "safelink",
            "schrijf", "opslaan", "sla op", "maak", "aanmaken", "wijzig", "pas aan", "bewerk", "kopieer", "verplaats", "hernoem", "voeg", "vervang", "exporteer", "onthoud", "bewaar", "bijlage", "downloaden", "binnenhalen",
            "write", "save", "create", "modify", "edit", "change", "update", "copy", "move", "rename", "add", "replace", "export", "remember", "delete", "attachment", "fix",
            "escrib", "guard", "crea", "cambi", "mover", "renombr", "agreg", "anad", "reemplaz", "recuerd", "adjunto", "descarg",
            "zapisz", "zapis", "utworz", "stworz", "zmien", "zmodyf", "edytuj", "kopiuj", "skopiuj", "przenies", "dodaj", "zastap", "eksport", "zapamietaj", "zalacznik", "pobierz", "pobran", "sciagn",
            "yaz", "kaydet", "olustur", "degistir", "duzenle", "kopyala", "tasi", "ekle", "hatirla", "ek dosya", "indirin", "indirme", "indirir",
            // 1.28.11: cleaning stray helper files
            "curat", "sterg", "stray", "clean", "opruim", "verwijder", "limpi", "elimin", "wyczy", "usun", "temiz",
            // 1.28.13: missing V/W pairs (plasa pereche, wapening, malla, siatka, hasir)
            "pereche", "plasa", "plase", "paar", "pair", "mesh", "pareja", "malla", "siatk", "hasir", "cift",
            // 1.28.13: server versus downloaded revisions
            "revizi", "revisi", "revision", "server", "servidor", "serwer", "sunucu", "rewizj", "revizy");
    private static final List<String> COMMAND_STEMS = List.of(
            "comand", "command", "script", "python", "powershell", "terminal", "instal", "cmd", "pip", "git", "bat", "ps1", "py",
            "opdracht", "uitvoer", "comando", "ejecut", "polecen", "uruchom", "komut", "betik", "calistir", "run_command");

    private ToolRelevance() {}

    static String group(String tool) {
        return FILE_TOOLS.contains(tool) ? FILES : COMMAND_TOOLS.contains(tool) ? COMMAND : null;
    }

    static List<String> names(String group) {
        return FILES.equals(group) ? FILE_TOOLS : COMMAND_TOOLS;
    }

    static ChatClient.ToolSpec spec(List<String> locked) {
        return new ChatClient.ToolSpec(ENABLE,
                "Unlock hidden tools when the task needs them. files: write/edit/append/copy/move files, make_dir, extract_attachments, save_rule, FAST import. command: run_command. Changes still ask the user for permission.",
                Json.obj("type", "object", "properties", Json.obj("group", Json.obj("type", "string", "enum", new ArrayList<Object>(locked))),
                        "required", List.of("group"), "additionalProperties", false));
    }

    /** Groups unlocked by the conversation so far plus the message being sent. */
    static Set<String> unlocked(List<ChatClient.Message> history, String latest) {
        Set<String> groups = new LinkedHashSet<>();
        List<String> users = new ArrayList<>();
        for (ChatClient.Message message : history) {
            if (message.role.equals("user")) users.add(message.content);
            else if (message.role.equals("assistant")) for (ChatClient.ToolCall call : message.toolCalls) {
                String group = call.name.equals(ENABLE) ? String.valueOf(call.args.get("group")) : group(call.name);
                if (group != null && GROUPS.contains(group)) groups.add(group);
            }
        }
        if (latest != null) users.add(latest);
        for (String user : users) {
            if (groups.size() == GROUPS.size()) break;
            Set<String> tokens = tokens(user);
            String text = " " + String.join(" ", tokens) + " ";
            if (!groups.contains(FILES) && matches(tokens, text, FILE_STEMS)) groups.add(FILES);
            if (!groups.contains(COMMAND) && matches(tokens, text, COMMAND_STEMS)) groups.add(COMMAND);
        }
        return groups;
    }

    private static boolean matches(Set<String> tokens, String text, List<String> stems) {
        for (String stem : stems) {
            if (stem.contains(" ")) {
                if (text.contains(" " + stem)) return true;
            } else if (stem.length() <= 3) {
                if (tokens.contains(stem)) return true;
            } else for (String token : tokens) if (token.startsWith(stem)) return true;
        }
        return false;
    }

    private static Set<String> tokens(String text) {
        String ascii = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .replace('\u0131', 'i').replace('\u0142', 'l').toLowerCase(Locale.ROOT);
        Set<String> out = new LinkedHashSet<>();
        for (String token : ascii.split("[^a-z0-9_]+")) if (!token.isEmpty()) out.add(token);
        return out;
    }
}