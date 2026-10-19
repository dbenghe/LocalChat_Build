package localchat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Runs one user turn: asks the model, runs the tools it calls, and repeats until it answers without tools. */
final class Agent {
    static final int MAX_STEPS = 80;

    interface Ui {
        /** Streamed reply text. */
        void text(String delta);

        /** A new model request starts after tool results. */
        void step();

        void toolStarted(ChatClient.ToolCall call);

        void toolFinished(ChatClient.ToolCall call, Tools.Result result);
        default void usage(TokenUsage usage) {}
        default void request(String system, List<ChatClient.Message> conversation, List<ChatClient.ToolSpec> tools) throws IOException {}
    }

    private final ChatClient client = new ChatClient();
    private final Tools tools; // null: plain chat
    private final List<ChatClient.Message> added = new ArrayList<>(); // guarded by itself
    private boolean replyPending; // guarded by added
    private volatile boolean cancelled;

    Agent(Tools tools) {
        this.tools = tools;
    }

    void cancel() {
        cancelled = true;
        client.cancel();
        if (tools != null) tools.cancel();
    }

    void run(Provider p, String url, String key, String model, String label, String system,
             List<ChatClient.Message> base, boolean stream, Ui ui) throws IOException {
        List<ChatClient.ToolSpec> specs = tools == null ? Collections.emptyList() : tools.specs();
        for (int step = 0; step < MAX_STEPS; step++) {
            if (cancelled) return;
            if (step > 0) ui.step();
            List<ChatClient.Message> conversation = new ArrayList<>(base);
            synchronized (added) {
                conversation.addAll(added);
                replyPending = true;
            }
            ui.request(system, conversation, specs);
            ChatClient.Reply reply;
            try { reply = client.chat(p, url, key, model, system, conversation, specs, stream, ui::text); }
            finally { ui.usage(client.usage()); }
            if (cancelled) return;
            synchronized (added) {
                if (!reply.text.isEmpty() || !reply.calls.isEmpty()) {
                    added.add(ChatClient.Message.assistant(reply.text, reply.calls, label));
                }
                replyPending = false;
            }
            if (reply.calls.isEmpty()) return;
            for (ChatClient.ToolCall call : reply.calls) {
                if (cancelled) return;
                ui.toolStarted(call);
                Tools.Result result = tools == null
                        ? Tools.Result.error("Tool-urile sunt dezactivate în aplicație.")
                        : tools.execute(call);
                if (cancelled) return;
                synchronized (added) {
                    added.add(ChatClient.Message.toolResult(call.id, result.text, result.isError));
                }
                ui.toolFinished(call, result);
            }
        }
        throw new IOException("Oprit după " + MAX_STEPS + " pași fără un răspuns final.");
    }

    /**
     * The messages this turn produced, safe to keep in the conversation: text streamed before a Stop is kept,
     * and every tool call gets a result so the next request is valid.
     */
    List<ChatClient.Message> collect(String partialText, String label) {
        List<ChatClient.Message> out;
        synchronized (added) {
            out = new ArrayList<>(added);
            if (replyPending && !partialText.isEmpty()) out.add(ChatClient.Message.assistant(partialText, Collections.emptyList(), label));
        }
        Set<String> answered = new HashSet<>();
        for (ChatClient.Message m : out) if (m.role.equals("tool")) answered.add(m.toolCallId);
        List<ChatClient.Message> fixed = new ArrayList<>();
        for (ChatClient.Message m : out) {
            fixed.add(m);
            for (ChatClient.ToolCall c : m.toolCalls) {
                if (!answered.contains(c.id)) fixed.add(ChatClient.Message.toolResult(c.id, "Anulat: utilizatorul a oprit răspunsul.", true));
            }
        }
        return fixed;
    }
}
