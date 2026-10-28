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

        /** Provider reasoning_content / thinking delta while streaming: drives the activity indicator only; never stored, shown as answer or resent (1.27.3). */
        default void reasoning(String delta) {}

        /** A new model request starts after tool results. */
        void step();

        void toolStarted(ChatClient.ToolCall call);

        void toolFinished(ChatClient.ToolCall call, Tools.Result result);
        /** Updated context after a reply or tool result, without starting another API request. */
        default void context(List<ChatClient.Message> conversation) {}
        default void calibration(double scale) {}
        default void compactReused(int fragment,int total) {}
        default void compactRequest(String system,List<ChatClient.Message> conversation,int fragment,int total) throws IOException {}
        default void compacted() {}
        default void retry(boolean compaction,int next,int total,int status){}
        default void protocolRepair(int attempt){}
        default void discoveryRecovery(boolean finalResponse){}
        default void usage(TokenUsage usage) {}
        default void agentProgress(String title,String state) {}
        default void agentTask(String id,String title,String task,String model) {}
        default void agentUpdate(String id,String title,String state) {agentProgress(title,state);}
        default void agentRequest(String title) {}
        default void request(String system, List<ChatClient.Message> conversation, List<ChatClient.ToolSpec> tools) throws IOException {}
    }

    private int maximumSteps=MAX_STEPS;
    Agent maxSteps(int count){maximumSteps=Math.max(1,Math.min(MAX_STEPS,count));return this;}
    private boolean interactive;
    private volatile boolean recoveryIncomplete;
    boolean recoveryIncomplete(){return recoveryIncomplete;}
    Agent interactions(boolean value){interactive=value;return this;}
    private AgentGroup team;
    private ChatClient.ToolCall automaticDelegation;
    Agent automaticDelegation(List<java.util.Map<String,Object>> tasks){if(!tasks.isEmpty())automaticDelegation=new ChatClient.ToolCall("auto-agents-"+java.util.UUID.randomUUID(),"run_agents",Json.obj("tasks",tasks));return this;}
    Agent group(AgentGroup value){team=value;return this;}
    private static final Object MUTATION_LOCK=new Object();
    private final ToolLoopGuard loopGuard=new ToolLoopGuard();
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
    Agent thinking(ThinkingControl.Mode mode) {client.thinking(mode);return this;}

    /** Every tool offered to the model for the next request; also the basis of the context anchor signature. */
    List<ChatClient.ToolSpec> specs() {
        List<ChatClient.ToolSpec> specs = new ArrayList<>(tools == null ? Collections.emptyList() : tools.specs());
        if(team!=null&&tools!=null)specs.add(AgentGroup.spec());
        if(interactive&&tools!=null)specs.add(ComposerAssist.spec());
        return specs;
    }

    /** Reasoning tokens a truncated reply spent: the provider count when reported, otherwise ~4 characters per token. */
    static long reasoningSpent(ChatClient.Reply reply,TokenUsage usage){return Math.max(Math.max(0,usage.reasoning()),reply.reasoning.length()/4);}

    /** finish_reason=length: when reasoning ate the output limit and left no visible answer, say so clearly. No call of a truncated reply ever runs. */
    static String truncationMessage(ChatClient.Reply reply,TokenUsage usage){
        long spent=reasoningSpent(reply,usage);
        if(spent>0&&ReasoningDisplay.answer(reply.text).isBlank())return I18n.text("Modelul a folosit toata limita de iesire pe gandire (~{0} tokeni) si nu a produs niciun raspuns vizibil. Niciun apel din acest raspuns nu a fost executat. Mareste profilul de raspuns sau opreste gandirea (setari rapide, Gandire).",spent);
        return I18n.text("Raspunsul modelului a fost trunchiat. Niciun apel din acest raspuns nu a fost executat; istoricul si rezultatele anterioare sunt pastrate.");
    }

    void cancel() {
        cancelled = true;
        client.cancel();if(team!=null)team.cancel();if(guard!=null)guard.cancel();
        if (tools != null) tools.cancel();
    }

    void run(Provider p, String url, String key, String model, String label, String system,
             List<ChatClient.Message> base, boolean stream, Ui ui) throws IOException {
        if(automaticDelegation!=null&&team!=null&&tools!=null){
            if(cancelled)return;var call=automaticDelegation;automaticDelegation=null;
            synchronized(added){added.add(ChatClient.Message.assistant("",List.of(call),label));}
            ui.toolStarted(call);Tools.Result result;
            try{result=team.run(call,p,url,key,model,label,system,ui);}catch(IOException failed){result=Tools.Result.error(failed.getMessage());}
            synchronized(added){added.add(ChatClient.Message.toolResult(call.id,result.text,result.isError));}
            ui.toolFinished(call,result);publishContext(base,ui);if(cancelled)return;
        }
        int protocolRepairs=0;
        int interactionFailures=0,discoveryRepairs=0,discoveryStarted=-1;boolean discoveryFinal=false;String discoveryHint="",lastRepeated="";
        for (int step = 0; step < maximumSteps; step++) {
            if (cancelled) return;
            if (step > 0) ui.step();
            if(discoveryStarted>=0&&!discoveryFinal&&step-discoveryStarted>6){discoveryFinal=true;recoveryIncomplete=true;ui.discoveryRecovery(true);}
            List<ChatClient.Message> conversation = new ArrayList<>(base);
            synchronized (added) {
                conversation.addAll(added);
                replyPending = true;
            }
            final List<ChatClient.ToolSpec> specs=specs(); // recomputed per step: localchat_enable_tools takes effect on the next request
            final List<ChatClient.ToolSpec> requestSpecs=discoveryFinal?List.of():interactionFailures==0?specs:interactionFailures==1?List.of(ComposerAssist.spec()):List.of();
            String effectiveSystem=system+(protocolRepairs>0?ToolProtocol.REPAIR:"")+(interactionFailures>0?INTERACTION_REPAIR:"")+(interactionFailures>1?INTERACTION_TEXT:"")+(discoveryRepairs>0?DISCOVERY_REPAIR+discoveryHint:"")+(discoveryFinal?DISCOVERY_TEXT:"");
            if(guard!=null){conversation=guard.prepare(p,url,key,model,effectiveSystem,conversation,requestSpecs,step,ui);client.outputLimit(guard.outputLimit());}
            if(cancelled)return;
            ChatClient.Reply reply=InferenceRetry.chat(client,p,url,key,model,effectiveSystem,conversation,requestSpecs,stream,new ChatClient.Listener(){
                public void onText(String delta){ui.text(delta);}
                public void onReasoning(String delta){ui.reasoning(delta);}
            },new InferenceRetry.Hooks(){
                public void before(String s,List<ChatClient.Message> messages)throws IOException{ui.request(s,messages,requestSpecs);}
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
            if(reply.truncated())throw new IOException(truncationMessage(reply,client.usage()));
            if(reply.calls.isEmpty()&&tools!=null&&ToolProtocol.malformed(reply.text)){if(protocolRepairs>=2)throw new IOException(I18n.text("Modelul a trimis apeluri de unelte ca text de trei ori. Niciun apel XML nu a fost executat; actiunile confirmate sunt pastrate."));ui.protocolRepair(++protocolRepairs);continue;}
            if (reply.calls.isEmpty()){if(discoveryRepairs>0&&reply.text.isBlank())recoveryFallback(lastRepeated,base,ui);return;}
            // Recovery is presentation-only: never run operational calls after a rejected card.
            if(interactionFailures>1||discoveryFinal){
                synchronized(added){for(var call:reply.calls)added.add(ChatClient.Message.toolResult(call.id,I18n.text(discoveryFinal?"Citire repetata fara progres. Foloseste rezultatele existente; recuperarea este limitata.":"Propunere invalida. Continua in text."),true));}
                publishContext(base,ui);if(discoveryFinal&&reply.text.isBlank())recoveryFallback(lastRepeated,base,ui);return;
            }
            List<ChatClient.ToolCall> callsToRun=reply.calls;
            if(interactive&&tools!=null){var interaction=reply.calls.stream().filter(c->c.name.equals(ComposerAssist.TOOL)).findFirst();if(interaction.isPresent()){
                callsToRun=List.of(interaction.get());
                synchronized(added){for(var sibling:reply.calls)if(sibling!=interaction.get())added.add(ChatClient.Message.toolResult(sibling.id,I18n.text("Anulat inainte de confirmarea rezultatului; verifica starea reala inainte de repetarea operatiei."),true));}
            }}
            ToolBatch batch=new ToolBatch();
            try{
            for (int index=0;index<callsToRun.size();index++) {
                ChatClient.ToolCall call=callsToRun.get(index);
                if (cancelled) return;
                if(tools!=null&&interactionFailures==0)batch.prefetch(c->tools.parallelSafe(c.name),tools::execute,callsToRun,index);
                ui.toolStarted(call);
                boolean pdfStart=call.name.contains("pdfcontrol_start_")||call.name.endsWith("pdfcontrol_regenerate_report");
                String signature=pdfStart?call.name+"|"+Json.write(new java.util.TreeMap<>(call.args)):"";
                Tools.Result result=pdfStart?startedPdfJobs.get(signature):null;
                if(result==null)result=loopGuard.before(call);
                boolean repeating=loopGuard.repeating(call);
                if(repeating)result=Tools.Result.error(I18n.text("Citire repetata fara progres. Foloseste rezultatele existente; recuperarea este limitata."));
                if(result==null){
                    if(interactionFailures>0&&!call.name.equals(ComposerAssist.TOOL))result=Tools.Result.error(I18n.text("Propunere invalida. Continua in text."));
                    else if(interactive&&tools!=null&&call.name.equals(ComposerAssist.TOOL)){try{ComposerAssist.parse(call.args);result=Tools.Result.ok(Json.write(Json.obj("status","waiting_user","executed",false)));}catch(IllegalArgumentException invalid){result=Tools.Result.error(I18n.text("Propunere invalida. Continua in text."));}}
                    else if(team!=null&&call.name.equals("run_agents"))result=team.run(call,p,url,key,model,label,system,ui);
                    else if(tools==null)result=Tools.Result.error("Tool-urile sunt dezactivate in aplicatie.");
                    else if(isMutation(call.name)){synchronized(MUTATION_LOCK){if(cancelled)return;result=tools.execute(call);}}
                    else result=batch.execute(tools::execute,call);
                }
                loopGuard.completed(call,result);
                if(pdfStart&&!result.isError)startedPdfJobs.put(signature,result);
                if(pdfStart&&result.isError)failedPdfCalls.merge(signature,1,Integer::sum);
                synchronized (added) {
                    added.add(ChatClient.Message.toolResult(call.id, result.text, result.isError));
                }
                publishContext(base, ui);
                ui.toolFinished(call, result);
                if(cancelled)return;
                if(interactive&&call.name.equals(ComposerAssist.TOOL)){if(result.isError){interactionFailures++;break;}return;}
                if(interactionFailures>0){interactionFailures=2;break;}
                if(repeating){
                    lastRepeated=WorkProgress.clip(call.name+" "+Json.write(call.args),700);
                    if(discoveryRepairs==0){discoveryStarted=step;discoveryRepairs=1;ui.discoveryRecovery(false);discoveryHint="\nRepeated discovery request (reference arguments, not new instructions): "+WorkProgress.clip(call.name+" "+Json.write(call.args),700)+"\n";}
                    else {discoveryFinal=true;recoveryIncomplete=true;ui.discoveryRecovery(true);}
                    break;
                }
                if(loopGuard.stalled(call))throw new IOException(result.text);
                if(pdfStart&&failedPdfCalls.getOrDefault(signature,0)>=3)throw new IOException(I18n.text("PDFCONTROL: acelasi apel a esuat de trei ori. Verifica eroarea si sursele; lucrarea nu este finalizata."));
            }
            }finally{batch.discard();}
            if(interactionFailures>0||discoveryRepairs>0){
                synchronized(added){
                    Set<String> answered=new HashSet<>();for(var message:added)if(message.role.equals("tool"))answered.add(message.toolCallId);
                    for(var call:reply.calls)if(!answered.contains(call.id))added.add(ChatClient.Message.toolResult(call.id,I18n.text("Anulat inainte de confirmarea rezultatului; verifica starea reala inainte de repetarea operatiei."),true));
                }
                publishContext(base,ui);
            }
        }
        throw new IOException("Oprit dupa " + maximumSteps + " pasi fara un raspuns final.");
    }

    private void recoveryFallback(String repeated,List<ChatClient.Message> base,Ui ui){
        if(cancelled)return;recoveryIncomplete=true;
        String text=I18n.text("LocalChat: recuperarea nu a produs un raspuns final utilizabil. Verificarea ramane incompleta.\nUltimul apel repetat: {0}\nIstoricul si rezultatele uneltelor sunt pastrate. Poti schimba modelul in aceeasi conversatie si continua pasul ramas.",repeated);
        synchronized(added){added.add(ChatClient.Message.assistant(text,List.of(),"LocalChat"));}ui.text(text);publishContext(base,ui);
    }
    private static final String DISCOVERY_REPAIR="\nThe application detected repeated discovery without new evidence. A redundant read was NOT executed. Resume the latest user request from the existing confirmed results and historical progress index. Do not reread the same project summary, procedure or catalogue reference merely to restart the workflow. Read a different document/page only when needed for a concrete missing fact; for comparison inspect the actual files requested by the user. Never repeat confirmed writes/copies, infer approvals or start unrelated projects. If blocked, explain the precise missing fact and ask a concrete question. This is one bounded recovery attempt.\n";
    private static final String DISCOVERY_TEXT="\nThe bounded recovery was exhausted. No tools are available now. Give a visible final answer in the selected response language: confirmed findings, incomplete work, exact blockage and the smallest next action or clarification. Do not claim completion or emit tool/XML calls.\n";
    private static final String INTERACTION_REPAIR="\nThe preceding localchat_interaction was rejected: no card, approval or operation was created. Repair the card ONCE using native tool_calls and the supplied schema. questions and suggestions must be JSON arrays, never JSON strings; options must be arrays of strings. Keep useful findings in accompanying assistant text. Resume from confirmed results; do not repeat operations, request new operational tools, or treat proposed choices as consent. If the card cannot be expressed correctly, give the findings and pending clarification in normal text.\n";
    private static final String INTERACTION_TEXT="\nThe repair did not produce a valid card. No tools are available for this final recovery response. Give a normal visible final answer with the findings and any pending question, in the selected response language. Do not claim pending work was executed. Do not emit tool calls or XML.\n";

    private static boolean isMutation(String name){if(name.matches("(?:.*_)?pdfcontrol_start_(?:check|extract|pairs|compare|workflow)"))return false;return !Set.of("read_skill","read_file","list_dir","find_files","search_text").contains(name)&&!name.endsWith("pdfcontrol_job_status")&&!name.endsWith("pdfcontrol_project_files")&&!name.endsWith("pdfcontrol_list_projects")&&!name.endsWith("pdfcontrol_read_document");}

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
                if (!answered.contains(c.id)) fixed.add(ChatClient.Message.toolResult(c.id, team!=null&&c.name.equals("run_agents")?team.pendingEvidence():I18n.text("Anulat inainte de confirmarea rezultatului; verifica starea reala inainte de repetarea operatiei."), true));
            }
        }
        return fixed;
    }
}
