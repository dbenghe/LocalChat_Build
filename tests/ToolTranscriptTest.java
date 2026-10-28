package localchat;

import java.util.*;

/** 1.28.5: the tool-call transcript text moved out of LocalChat unchanged. */
public final class ToolTranscriptTest {
    static int checks;
    static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    static ChatClient.ToolCall call(String name, Object... kv) {
        Map<String, Object> args = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) args.put((String) kv[i], kv[i + 1]);
        return new ChatClient.ToolCall("id1", name, args);
    }

    public static void main(String[] args) {
        I18n.setLanguage("ro");
        check(ToolTranscript.describeCall(call("list_dir")).equals("list_dir()"), "no arguments");
        check(ToolTranscript.describeCall(call("read_file", "path", "C:\\a\\b.txt", "limit", 5)).equals("read_file(path: C:\\a\\b.txt, limit: 5)"), "string and number arguments in order");
        check(ToolTranscript.describeCall(call("run_command", "command", "a\r\nb\nc")).equals("run_command(command: a | b | c)"), "line breaks become ' | ' and CR is dropped");
        String longText = "x".repeat(200);
        String shown = ToolTranscript.describeCall(call("search_text", "query", longText));
        check(shown.equals("search_text(query: " + "x".repeat(140) + "\u2026)"), "ordinary arguments are cut at 140 characters with an ellipsis");
        check(ToolTranscript.describeCall(call("write_file", "path", "a.txt", "content", longText)).equals("write_file(path: a.txt, content: (200 caractere))"), "bulk arguments show only their length");
        check(ToolTranscript.describeCall(call("edit_file", "old_text", longText, "new_text", "short")).equals("edit_file(old_text: (200 caractere), new_text: short)"), "old_text counted, short new_text shown");

        check(ToolTranscript.preview(new Tools.Result("one line", false)).equals("one line"), "single line preview");
        check(ToolTranscript.preview(new Tools.Result("first\nsecond\r\nthird", false)).equals("first   (+2 randuri)"), "multi-line preview counts the rest");
        check(ToolTranscript.preview(new Tools.Result("boom", true)).equals("eroare: boom"), "errors are prefixed");
        check(ToolTranscript.preview(new Tools.Result("y".repeat(300), false)).equals("y".repeat(160) + "\u2026"), "long first line cut at 160 with an ellipsis");
        check(ToolTranscript.preview(new Tools.Result("   ", false)).isEmpty(), "blank result gives an empty preview");
        System.out.println("ToolTranscriptTest OK: " + checks + " checks");
    }
}
