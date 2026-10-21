package localchat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Explicit, bounded child tasks using the selected provider/model and independent histories. */
final class AgentGroup {
    static final int MAX_TASKS=3, PARALLELISM=2, CHILD_STEPS=16, DEADLINE_SECONDS=180;
    private final Supplier<Tools> factory;
    private final Path archiveDir;
    private final int contextLimit,outputLimit,threshold;
    private final boolean autoCompact;
    private final Set<Agent> active=ConcurrentHashMap.newKeySet(),children=ConcurrentHashMap.newKeySet();
    private volatile ExecutorService executor;
    private volatile boolean cancelled;
    private final Map<String,Tools.Result> completed=new HashMap<>();
    private int groupsStarted;

    AgentGroup(Supplier<Tools> factory,Path archiveDir,int contextLimit,int outputLimit,boolean autoCompact,int threshold){
        this.factory=Objects.requireNonNull(factory);this.archiveDir=Objects.requireNonNull(archiveDir);
        this.contextLimit=Math.max(0,contextLimit);this.outputLimit=Math.max(1,Math.min(4096,outputLimit));
        this.autoCompact=autoCompact;this.threshold=Math.max(30,Math.min(95,threshold));
    }
    static ChatClient.ToolSpec spec(){
        var task=Json.obj("type","object","properties",Json.obj("title",Json.obj("type","string","maxLength",100),"task",Json.obj("type","string","maxLength",12000)),"required",List.of("title","task"),"additionalProperties",false);
        return new ChatClient.ToolSpec("run_agents","Run 1-3 explicitly user-requested independent tasks with the current provider and model; at most two execute concurrently. Each child receives only its own task and the current system instructions, fresh tools under the same permissions, a 16-step cap, and a 180-second group deadline. No recursive delegation. Use only when the user explicitly asks for agents/parallel delegated work. Return actual outcomes and private local archives; verify each result before claiming completion.",Json.obj("type","object","properties",Json.obj("tasks",Json.obj("type","array","minItems",1,"maxItems",MAX_TASKS,"items",task)),"required",List.of("tasks"),"additionalProperties",false));
    }
    String pendingEvidence(){return Json.write(Json.obj("status",cancelled?"cancelled":"pending","completion_pending",true,"agent_archive_directory",archiveDir.toString()));}
    void cancel(){cancelled=true;for(Agent child:children)child.cancel();ExecutorService running=executor;if(running!=null)running.shutdownNow();}

    private record Task(String title,String text){}
    private static final class State {
        final Task task;final List<Object> outcomes=Collections.synchronizedList(new ArrayList<>());final Map<String,Object> deliveries=Collections.synchronizedMap(new LinkedHashMap<>()),pendingJobs=Collections.synchronizedMap(new LinkedHashMap<>());volatile boolean failedPdf,incompletePdf;final StringBuffer partial=new StringBuffer();
        volatile Agent child;volatile Map<String,Object> result;volatile String stop="";
        State(Task task){this.task=task;}
        Map<String,Object> interrupted(String reason){return Json.obj("title",task.title,"task",clip(task.text,1000),"status","cancelled","answer",clip(ReasoningDisplay.answer(partial.toString()),4000),"completed_tool_count",outcomes.size(),"completed_tools",toolSummary(new ArrayList<>(outcomes)),"error",reason,"completion_pending",child!=null&&result==null,"deliveries",deliverySummary(deliveries),"verified_delivery_count",deliveries.size(),"deliveries_truncated",deliveryTruncated(deliveries),"pending_jobs",pendingSummary(pendingJobs),"pending_job_count",pendingJobs.size(),"pending_jobs_truncated",pendingJobs.size()>16);}
    }
    private static List<Task> tasks(ChatClient.ToolCall call){
        if(!"run_agents".equals(call.name)||!(Json.get(call.args,"tasks") instanceof List<?> rows)||rows.isEmpty()||rows.size()>MAX_TASKS)return List.of();
        List<Task> tasks=new ArrayList<>();for(Object row:rows){String title=Json.str(row,"title"),text=Json.str(row,"task");if(title==null||text==null||title.isBlank()||text.isBlank()||title.length()>100||text.length()>12000)return List.of();tasks.add(new Task(title.strip(),text));}return tasks;
    }
    synchronized Tools.Result run(ChatClient.ToolCall call,Provider provider,String url,String key,String model,String label,String system,Agent.Ui parentUi)throws IOException{
        List<Task> tasks=tasks(call);if(tasks.isEmpty())return Tools.Result.error(Json.write(Json.obj("status","failed","error","invalid_tasks","maximum_tasks",MAX_TASKS)));
        if(cancelled)return Tools.Result.error(Json.write(Json.obj("status","cancelled","tasks",List.of())));
        String signature=provider.name()+"|"+url+"|"+model+"|"+ModelCatalog.hash(key)+"|"+Json.write(tasks.stream().map(t->Json.obj("title",t.title,"task",t.text)).toList());
        Tools.Result prior=completed.get(signature);if(prior!=null)return prior;
        if(groupsStarted>=2)return Tools.Result.error(Json.write(Json.obj("status","failed","error","agent_group_limit","maximum_groups",2,"maximum_tasks_total",6)));
        groupsStarted++;
        String group=UUID.randomUUID().toString().replace("-","");
        ExecutorService pool=Executors.newFixedThreadPool(PARALLELISM,r->{Thread t=new Thread(r,"localchat-child-agent");t.setDaemon(true);return t;});executor=pool;
        List<Future<Map<String,Object>>> futures=new ArrayList<>();List<State> states=new ArrayList<>();List<Map<String,Object>> results=new ArrayList<>();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS);
        boolean timedOut=false;
        try{
            for(Task task:tasks)states.add(new State(task));
            for(int i=0;i<tasks.size();i++){final int index=i;State state=states.get(i);try{futures.add(pool.submit(()->{var result=execute(group,index,state,provider,url,key,model,label,system,parentUi);state.result=result;return result;}));}catch(RejectedExecutionException e){if(cancelled)break;throw e;}}
            waitForTasks:for(var future:futures)while(true){
                if(cancelled){break waitForTasks;}
                long left=until-System.nanoTime();if(left<=0){timedOut=true;break waitForTasks;}
                try{future.get(Math.min(left,TimeUnit.MILLISECONDS.toNanos(100)),TimeUnit.NANOSECONDS);break;}
                catch(TimeoutException ignored){}
                catch(InterruptedException e){cancel();Thread.currentThread().interrupt();break waitForTasks;}
                catch(CancellationException|ExecutionException e){break;}
            }
        }finally{
            if(cancelled||timedOut){for(State state:states)state.stop=cancelled?"cancelled":"group_deadline";for(Agent child:active)child.cancel();}
            pool.shutdownNow();
            try{pool.awaitTermination(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            executor=null;
        }
        for(State state:states)results.add(state.result!=null?state.result:state.interrupted(cancelled?"cancelled":timedOut?"group_deadline":"child_failed"));
        // A timed-out/cancelled task may still have completed operations: its child always writes its own final archive.
        boolean allDone=results.stream().allMatch(r->"done".equals(r.get("status")));
        boolean someDone=results.stream().anyMatch(r->Set.of("done","partial").contains(r.get("status")));
        String status=cancelled?"cancelled":allDone?"done":someDone?"partial":"failed";
        Tools.Result result=new Tools.Result(Json.write(Json.obj("status",status,"group_id",group,"model",model,"parallelism",PARALLELISM,"tasks",results)),!allDone);completed.put(signature,result);return result;
    }
    private Map<String,Object> notStarted(Task task,String state,String error){return Json.obj("title",task.title,"task",clip(task.text,1000),"status",state,"answer","","completed_tools",List.of(),"error",error);}
    private Map<String,Object> execute(String group,int index,State state,Provider provider,String url,String key,String model,String label,String system,Agent.Ui parentUi){
        Task task=state.task;Agent child=null;StringBuffer partial=state.partial;String error="";List<ChatClient.Message> messages=List.of();boolean stopped=false;
        Map<String,Object> result;
        String childLabel=label+" / "+task.title;
        try{
            if(cancelled||Thread.currentThread().isInterrupted())return notStarted(task,"cancelled","cancelled");
            child=new Agent(factory.get()).maxSteps(CHILD_STEPS);state.child=child;children.add(child);child.outputLimit(outputLimit);
            child.guard(new ContextGuard(contextLimit,outputLimit,autoCompact,threshold,1.25));active.add(child);
            if(cancelled){child.cancel();return notStarted(task,"cancelled","cancelled");}
            parentUi.agentProgress(task.title,"running");
            Agent.Ui ui=new Agent.Ui(){
                public void text(String delta){partial.append(delta);}
                public void step(){partial.setLength(0);}
                public void toolStarted(ChatClient.ToolCall c){parentUi.agentProgress(task.title,"tool:"+c.name);}
                public void toolFinished(ChatClient.ToolCall c,Tools.Result r){state.outcomes.add(Json.obj("name",c.name,"success",!r.isError,"result",clip(r.text,3000),"truncated",r.text.length()>3000));if(!r.isError)recordPdfEvidence(state,c.name,r.text);}
                public void usage(TokenUsage u){parentUi.usage(u);}
                public void request(String s,List<ChatClient.Message> messages,List<ChatClient.ToolSpec> tools){parentUi.agentRequest(task.title);}
                public void compactRequest(String s,List<ChatClient.Message> messages,int fragment,int total){parentUi.agentRequest(task.title);}
                public void retry(boolean compaction,int next,int total,int status){parentUi.retry(compaction,next,total,status);}
            };
            child.run(provider,url,key,model,childLabel,system+"\nYou are a delegated child agent. Work only on your assigned task. No further delegation is available. Perform control jobs, copies or other mutations only when your assigned task explicitly requires them; an analysis-only task does not request a recontrol or copying. Keep completed work and limitations explicit. Do not claim actions without tool evidence.\n",List.of(ChatClient.Message.user(task.text)),false,ui);
        }catch(Exception e){error=e.getClass().getSimpleName();if(e instanceof ChatClient.HttpFailure h)error+=" HTTP "+h.status;stopped=cancelled||Thread.currentThread().isInterrupted();}
        finally{if(child!=null){messages=child.collect(partial.toString(),childLabel);active.remove(child);}}
        String answer="";for(int i=messages.size()-1;i>=0;i--){var m=messages.get(i);if(m.role.equals("assistant")&&m.toolCalls.isEmpty()){answer=ReasoningDisplay.answer(m.content).strip();break;}}
        List<Object> outcomes=new ArrayList<>(state.outcomes);
        boolean hasActions=outcomes.stream().anyMatch(o->Boolean.TRUE.equals(Json.get(o,"success")));
        boolean toolErrors=state.failedPdf||state.incompletePdf||outcomes.stream().anyMatch(o->Boolean.FALSE.equals(Json.get(o,"success")));
        boolean pendingJobs=!state.pendingJobs.isEmpty();
        String status=cancelled||stopped||!state.stop.isEmpty()?"cancelled":error.isEmpty()&&!answer.isBlank()&&!toolErrors&&!pendingJobs?"done":hasActions||!answer.isBlank()?"partial":"failed";
        if(!state.stop.isEmpty())error=state.stop;else if(error.isEmpty()&&answer.isBlank())error="no_visible_final_answer";else if(error.isEmpty()&&toolErrors)error="tool_errors";else if(error.isEmpty()&&pendingJobs)error="pending_jobs";
        result=Json.obj("title",task.title,"task",clip(task.text,1000),"task_truncated",task.text.length()>1000,"status",status,"answer",clip(answer,4000),"answer_truncated",answer.length()>4000,"completed_tool_count",outcomes.size(),"completed_tools",toolSummary(outcomes),"tools_truncated",outcomes.size()>8,"deliveries",deliverySummary(state.deliveries),"verified_delivery_count",state.deliveries.size(),"deliveries_truncated",deliveryTruncated(state.deliveries),"pending_jobs",pendingSummary(state.pendingJobs),"pending_job_count",state.pendingJobs.size(),"pending_jobs_truncated",state.pendingJobs.size()>16);
        if(!error.isBlank())result.put("error",error);
        try{
            Files.createDirectories(archiveDir);Path file=archiveDir.resolve(group+"-"+(index+1)+".json");Path temp=Files.createTempFile(archiveDir,"agent-",".tmp");
            try{Files.writeString(temp,Json.write(Json.obj("group_id",group,"title",task.title,"task",task.text,"status",status,"model",model,"messages",archiveMessages(task.text,messages))),StandardCharsets.UTF_8);try{Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(temp);}
            result.put("archive",file.toString());
        }catch(IOException e){result.put("archive_error",e.getClass().getSimpleName());}
        parentUi.agentProgress(task.title,status);return result;
    }
    private static final Set<String> PDF_OUTPUT_TOOLS=Set.of("pdfcontrol_job_status","pdfcontrol_start_check","pdfcontrol_start_compare","pdfcontrol_start_pairs","pdfcontrol_start_extract","pdfcontrol_start_workflow","pdfcontrol_regenerate_report");
    private static void recordPdfEvidence(State state,String tool,String text){
        if(PDF_OUTPUT_TOOLS.stream().noneMatch(name->tool.equals(name)||tool.endsWith("_"+name)))return;
        try{Object value=Json.parse(text);String id=Json.str(value,"job_id"),status=Json.str(value,"status");if(id==null||id.isBlank())return;
            if(Set.of("pending","queued","running").contains(Objects.toString(status,""))){Map<String,Object> pending=Json.obj("job_id",id,"status",status);Object project=Json.get(value,"project");if(project!=null)pending.put("project",project);state.pendingJobs.put(id,pending);return;}
            if(Set.of("done","partial","failed","cancelled").contains(Objects.toString(status,"")))state.pendingJobs.remove(id);
            if(Set.of("failed","cancelled").contains(Objects.toString(status,""))){state.failedPdf=true;return;}
            if(Set.of("done","partial").contains(Objects.toString(status,""))){if(Boolean.TRUE.equals(Json.get(value,"delivery_verified")))state.deliveries.put(id,value);if(status.equals("partial")||!Boolean.TRUE.equals(Json.get(value,"delivery_verified")))state.incompletePdf=true;}
        }catch(RuntimeException ignored){}
    }
    private static List<Object> pendingSummary(Map<String,Object> pending){synchronized(pending){var values=new ArrayList<>(pending.values());return new ArrayList<>(values.subList(Math.max(0,values.size()-16),values.size()));}}
    private static List<Object> deliverySummary(Map<String,Object> deliveries){
        List<Object> values; synchronized(deliveries){values=new ArrayList<>(deliveries.values());}
        List<Object> out=new ArrayList<>();int remaining=12000;
        for(int i=values.size()-1;i>=0&&out.size()<8;i--){Object value=values.get(i);Map<String,Object> summary=Json.obj("job_id",Json.str(value,"job_id"),"status",Json.str(value,"status"),"delivery_verified",true);boolean cut=false;
            for(String field:List.of("report","differences_report")){String path=Json.str(value,field);if(path!=null&&!path.isBlank()){if(path.length()<=4096)summary.put(field,path);else cut=true;}}
            List<Object> artifacts=new ArrayList<>();Object raw=Json.get(value,"artifacts");if(raw instanceof List<?> rows)for(Object row:rows){String path=row instanceof String?row.toString():Json.str(row,"path");if(path==null||path.isBlank())continue;if(path.length()>4096||artifacts.size()>=16){cut=true;continue;}var candidate=Json.obj("path",path);artifacts.add(candidate);summary.put("artifacts",artifacts);if(Json.write(summary).length()>remaining){artifacts.remove(artifacts.size()-1);cut=true;break;}}
            summary.put("artifacts",artifacts);summary.put("artifacts_truncated",cut);int size=Json.write(summary).length();if(size>remaining)continue;remaining-=size;out.add(summary);
        }
        return out;
    }
    private static boolean deliveryTruncated(Map<String,Object> deliveries){var summary=deliverySummary(deliveries);return summary.size()<deliveries.size()||summary.stream().anyMatch(row->Boolean.TRUE.equals(Json.get(row,"artifacts_truncated")));}
    private static List<Object> archiveMessages(String task,List<ChatClient.Message> messages){List<Object> out=new ArrayList<>();out.add(ChatClient.Message.user(task).toJson());for(var m:messages)out.add(m.toJson());return out;}
    private static List<Object> toolSummary(List<Object> outcomes){List<Object> summary=new ArrayList<>();for(Object outcome:outcomes.subList(Math.max(0,outcomes.size()-8),outcomes.size())){String text=Objects.toString(Json.str(outcome,"result"),"");summary.add(Json.obj("name",Json.str(outcome,"name"),"success",Json.get(outcome,"success"),"result",clip(text,600),"truncated",text.length()>600||Boolean.TRUE.equals(Json.get(outcome,"truncated"))));}return summary;}
    private static String clip(String value,int limit){return value.length()<=limit?value:value.substring(0,limit);}
}
