package localchat;

import java.util.Arrays;
import java.util.Map;

/** One-line transcript text for a tool call and its result (extracted unchanged from LocalChat in 1.28.5). */
final class ToolTranscript {
    private ToolTranscript() {}

    static String describeCall(ChatClient.ToolCall call) {
        StringBuilder b = new StringBuilder(call.name).append('(');
        boolean first = true;
        for (Map.Entry<String, Object> e : call.args.entrySet()) {
            if (!first) b.append(", ");
            first = false;
            Object v = e.getValue();
            String s = (v instanceof String ? (String) v : Json.write(v)).replace("\r", "").replace("\n", " | ");
            boolean bulk = Arrays.asList("content", "old_text", "new_text").contains(e.getKey());
            if (s.length() > 140) s = bulk ? I18n.text("({0} caractere)", s.length()) : s.substring(0, 140) + "…";
            b.append(e.getKey()).append(": ").append(s);
        }
        return b.append(')').toString();
    }

    static String preview(Tools.Result r) {
        String[] lines = r.text.strip().split("\r?\n");
        String first = lines.length == 0 ? "" : lines[0];
        if (first.length() > 160) first = first.substring(0, 160) + "…";
        return (r.isError ? I18n.text("eroare: ") : "") + first + (lines.length > 1 ? I18n.text("   (+{0} randuri)", lines.length - 1) : "");
    }
}
