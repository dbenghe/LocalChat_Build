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
        /** Updated context after a reply or tool result, without starting another API request. */
        default void context(List<ChatClient.Message> conversation) {}
        default void calibration(double scale) {}
        default void compactRequest(String system,List<ChatClient.Message> conversation,int fragment,int total) throws IOException {}
        default void compacted() {}
        default void retry(boolean compaction,int next,int total,int status){}
        default void usage(TokenUsage usage) {}
        default void request(String system, List<ChatClient.Message> conversation, List<ChatClient.ToolSpec> tools) throws IOException {}
    }

    private ContextGuard guard;
    Agent guard(ContextGuard value){guard=value;return this;}
    private final ChatClient client = new ChatClient();
    private final Tools tools; // null: plain chat
    private final List<ChatClient.Message> added = new ArrayList<>(); // guarded by itself
    private final java.util.Map<String,Tools.Result> startedPdfJobs=new java.util.HashMap<>();
    private final java.util.Map<String,Integer> failedPdfCalls=new java.util.HashMap<>();
    private boolean replyPending; // guarded by added
    private volatile boolean cancelled;

    Agent(Tools tools) {
        this.tools = tools;
    }

    void outputLimit(int tokens) {client.outputLimit(tokens);}

    void cancel() {
        cancelled = true;
        client.cancel();if(guard!=null)guard.cancel();
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
            if(guard!=null){conversation=guard.prepare(p,url,key,model,system,conversation,specs,step,ui);client.outputLimit(guard.outputLimit());}
            if(cancelled)return;
            ChatClient.Reply reply=InferenceRetry.chat(client,p,url,key,model,system,conversation,specs,stream,ui::text,new InferenceRetry.Hooks(){
                public void before(String s,List<ChatClient.Message> messages)throws IOException{ui.request(s,messages,specs);}
                public void usage(TokenUsage usage){if(guard!=null)guard.observe(usage,ui);ui.usage(usage);}
                public boolean cancelled(){return cancelled;}
                public void retry(int next,int total,int status){ui.retry(false,next,total,status);}
            });
            if (cancelled) return;
            synchronized (added) {
                if (!reply.text.isEmpty() || !reply.calls.isEmpty()) {
                    added.add(ChatClient.Message.assistant(reply.text, reply.calls, label));
                }
                replyPending = false;
            }
            publishContext(base, ui);
            if (reply.calls.isEmpty()) return;
            for (ChatClient.ToolCall call : reply.calls) {
                if (cancelled) return;
                ui.toolStarted(call);
                boolean pdfStart=call.name.contains("pdfcontrol_start_")||call.name.endsWith("pdfcontrol_regenerate_report");
                String signature=pdfStart?call.name+"|"+Json.write(new java.util.TreeMap<>(call.args)):"";
                Tools.Result result=pdfStart?startedPdfJobs.get(signature):null;
                if(result==null)result=tools==null?Tools.Result.error("Tool-urile sunt dezactivate in aplicatie."):tools.execute(call);
                if(pdfStart&&!result.isError)startedPdfJobs.put(signature,result);
                if(pdfStart&&result.isError)failedPdfCalls.merge(signature,1,Integer::sum);
                if (cancelled) return;
                synchronized (added) {
                    added.add(ChatClient.Message.toolResult(call.id, result.text, result.isError));
                }
                publishContext(base, ui);
                ui.toolFinished(call, result);
                if(pdfStart&&failedPdfCalls.getOrDefault(signature,0)>=3)throw new IOException(I18n.text("PDFCONTROL: acelasi apel a esuat de trei ori. Verifica eroarea si sursele; lucrarea nu este finalizata."));
            }
        }
        throw new IOException("Oprit dupa " + MAX_STEPS + " pasi fara un raspuns final.");
    }

    private void publishContext(List<ChatClient.Message> base, Ui ui) {
        List<ChatClient.Message> snapshot = new ArrayList<>(base);
        synchronized (added) { snapshot.addAll(added); }
        ui.context(List.copyOf(guard==null?snapshot:guard.requestView(snapshot)));
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
                if (!answered.contains(c.id)) fixed.add(ChatClient.Message.toolResult(c.id, "Anulat: utilizatorul a oprit raspunsul.", true));
            }
        }
        return fixed;
    }
}
