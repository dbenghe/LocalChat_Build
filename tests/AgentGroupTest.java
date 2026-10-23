package localchat;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Runtime child agents use a local HTTP fixture and synthetic local files only. */
public final class AgentGroupTest {
    static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static final class Ui implements Agent.Ui {
        final Map<String,String> queued=new ConcurrentHashMap<>();final Set<String> updated=ConcurrentHashMap.newKeySet();
        public void agentTask(String id,String title,String task,String model){queued.put(id,task+"|"+model);}
        public void agentUpdate(String id,String title,String state){updated.add(id);agentProgress(title,state);}
        final AtomicInteger requests=new AtomicInteger(),usages=new AtomicInteger();final List<String> progress=Collections.synchronizedList(new ArrayList<>());
        public void text(String text){throw new AssertionError("child reasoning must not stream into parent transcript");}
        public void step(){}public void toolStarted(ChatClient.ToolCall call){}public void toolFinished(ChatClient.ToolCall call,Tools.Result result){}
        public void usage(TokenUsage usage){usages.incrementAndGet();}public void agentRequest(String title){requests.incrementAndGet();}public void agentProgress(String title,String state){progress.add(title+":"+state);}
    }
    static final class Server implements AutoCloseable {
        final ServerSocket socket=new ServerSocket(0,20,InetAddress.getByName("127.0.0.1"));final ExecutorService pool=Executors.newCachedThreadPool(r->{Thread t=new Thread(r);t.setDaemon(true);return t;});
        final List<Object> requests=Collections.synchronizedList(new ArrayList<>());final AtomicInteger active=new AtomicInteger(),max=new AtomicInteger();final CountDownLatch arrived=new CountDownLatch(1);final String mode;final Path file;volatile Throwable error;
        Server(String mode,Path file)throws IOException{this.mode=mode;this.file=file;pool.submit(()->{while(!socket.isClosed())try{Socket c=socket.accept();pool.submit(()->serve(c));}catch(SocketException e){if(!socket.isClosed())error=e;}catch(Exception e){error=e;}});}
        String url(){return "http://127.0.0.1:"+socket.getLocalPort()+"/v1";}
        void serve(Socket c){int count=active.incrementAndGet();max.accumulateAndGet(count,Math::max);try(c){c.setSoTimeout(3000);InputStream in=c.getInputStream();ByteArrayOutputStream head=new ByteArrayOutputStream();int b;while((b=in.read())>=0){head.write(b);if(head.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n"))break;}int length=0;for(String line:head.toString(StandardCharsets.US_ASCII).split("\r\n"))if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(15).strip());Object body=Json.parse(new String(in.readNBytes(length),StandardCharsets.UTF_8));requests.add(body);arrived.countDown();if(mode.equals("slow"))Thread.sleep(15000);else Thread.sleep(160);
            String wire=Json.write(body);boolean result=wire.contains("tool_call_id")||wire.contains("tool_result");String text=mode.equals("empty")?"":wire.contains("task-a-only")?"Final A from verified local input":wire.contains("task-b-only")?"Final B": "Final C";
            Object message=Json.obj("content",text);if((wire.contains("task-a-only")&&!result)||mode.equals("loop"))message=Json.obj("content","","tool_calls",List.of(Json.obj("id","read-"+UUID.randomUUID(),"type","function","function",Json.obj("name","read_file","arguments",Json.write(Json.obj("path",file.toString()))))));
            boolean anthropic=head.toString(StandardCharsets.US_ASCII).startsWith("POST /v1/messages ");Object response;
            if(anthropic){Object content=Json.get(message,"tool_calls") instanceof List?List.of(Json.obj("type","tool_use","id","anthropic-read","name","read_file","input",Json.obj("path",file.toString()))):List.of(Json.obj("type","text","text",text));response=Json.obj("model",Json.str(body,"model"),"content",content,"usage",Json.obj("input_tokens",80,"output_tokens",12));}
            else response=Json.obj("model",Json.str(body,"model"),"choices",List.of(Json.obj("message",message)),"usage",Json.obj("prompt_tokens",80,"completion_tokens",12));
            byte[] data=Json.write(response).getBytes(StandardCharsets.UTF_8);OutputStream out=c.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+data.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(data);
        }catch(IOException e){/* Stop closes the fixture socket. */}catch(InterruptedException e){Thread.currentThread().interrupt();}catch(Throwable e){error=e;}finally{active.decrementAndGet();}}
        public void close()throws Exception{socket.close();pool.shutdownNow();if(error!=null)throw new Exception(error);}
    }
    static Tools tools(Path home){return new Tools(new Tools.Context(home,home.resolve("skills"),home.resolve("rules.md"),home.resolve("settings.properties"),Map.of(),null,new Mcp(),PathMap.none(),true,EnumSet.allOf(Tools.Access.class),(a,b)->Tools.Decision.NO),List.of()).readOnly(true);}
    static ChatClient.ToolCall request(String...taskTexts){List<Object> tasks=new ArrayList<>();for(int i=0;i<taskTexts.length;i++)tasks.add(Json.obj("title","Child "+(i+1),"task",taskTexts[i]));return new ChatClient.ToolCall("group","run_agents",Json.obj("tasks",tasks));}
    static void normal(Path home)throws Exception{
        Files.createDirectories(home);Path file=home.resolve("proof.txt");Files.writeString(file,"synthetic proof");AtomicInteger freshTools=new AtomicInteger();Ui ui=new Ui();Path archives=home.resolve("archives");
        try(var server=new Server("normal",file)){
            var group=new AgentGroup(()->{freshTools.incrementAndGet();return tools(home);},archives,65536,16000,false,70);Tools.Result result=group.run(request("task-a-only: read proof","task-b-only: independent analysis","task-c-only: independent analysis"),Provider.CUSTOM,server.url(),"fixture-private-key","chosen-current-model","provider label","shared-parent-system preference",ui);Object root=Json.parse(result.text);check(!result.isError&&"done".equals(Json.str(root,"status")),"all three actual final answers completed");check(freshTools.get()==3&&server.max.get()==2,"fresh tools per child, at most two concurrent");check(server.requests.size()==4&&ui.requests.get()==4&&ui.usages.get()==4,"each child inference counted exactly once with usage");check(ui.progress.stream().anyMatch(p->p.endsWith(":done")),"status progress reaches parent without child text");
            for(Object body:server.requests){check(Json.str(body,"model").equals("chosen-current-model"),"same currently selected model");long max=TokenUsage.number(body,"max_tokens",0);check(max>0&&max<=16000,"child output bounded by selected profile and context");String wire=Json.write(body);check(!(wire.contains("task-a-only")&&wire.contains("task-b-only"))&&!(wire.contains("task-b-only")&&wire.contains("task-c-only")),"child histories isolated");check(!wire.contains("\"name\":\"run_agents\""),"no recursive delegation tool");}
            List<?> rows=(List<?>)Json.get(root,"tasks");check(rows.size()==3,"all tasks returned");check(ui.queued.size()==3&&ui.updated.equals(ui.queued.keySet()),"queued and progress events use the same distinct task ids");check(ui.queued.values().stream().allMatch(v->v.contains("chosen-current-model")),"task announcements preserve the selected model");check(((List<?>)Json.get(rows.get(0),"completed_tools")).size()==1,"actual local tool outcome retained");for(Object row:rows){Path archive=Path.of(Json.str(row,"archive"));String saved=Files.readString(archive);check(saved.contains("\"messages\"")&&!saved.contains("fixture-private-key")&&!saved.contains("shared-parent-system preference"),"private archive has actual messages but no API credential or system");check("done".equals(Json.str(row,"status"))&&!Json.str(row,"answer").isBlank(),"done requires visible final answer");}
            var cached=group.run(request("task-a-only: read proof","task-b-only: independent analysis","task-c-only: independent analysis"),Provider.CUSTOM,server.url(),"fixture-private-key","chosen-current-model","provider label","shared-parent-system preference",ui);check(cached.text.equals(result.text)&&server.requests.size()==4,"same delegated task set reuses completed result without inference");
            group.run(request("second distinct independent analysis"),Provider.CUSTOM,server.url(),"fixture-private-key","chosen-current-model","provider label","shared-parent-system preference",ui);check(server.requests.size()==5,"second distinct group allowed");
            var denied=group.run(request("third distinct independent analysis"),Provider.CUSTOM,server.url(),"fixture-private-key","chosen-current-model","provider label","shared-parent-system preference",ui);check(denied.isError&&server.requests.size()==5&&denied.text.contains("agent_group_limit"),"third distinct group rejected without inference");
            check(AgentGroup.spec().name.equals("run_agents")&&((Number)Json.get(AgentGroup.spec().schema,"properties","tasks","maxItems")).intValue()==3,"bounded delegation schema");
        }
    }
    static void anthropic(Path home)throws Exception{
        Files.createDirectories(home);Path file=home.resolve("proof.txt");Files.writeString(file,"synthetic proof");Ui ui=new Ui();try(var server=new Server("normal",file)){var group=new AgentGroup(()->tools(home),home.resolve("archives"),65536,1024,false,70);Object result=Json.parse(group.run(request("task-a-only: verify local proof"),Provider.ANTHROPIC,server.url(),"fixture-anthropic-key","current-anthropic-model","selected provider","system",ui).text);check("done".equals(Json.str(result,"status"))&&server.requests.size()==2,"Anthropic transport also supports child tool roundtrip");check(server.requests.stream().allMatch(r->"current-anthropic-model".equals(Json.str(r,"model"))),"Anthropic uses current selected model");check(ui.requests.get()==2&&ui.usages.get()==2,"Anthropic child requests and usage counted");}
    }
    static void boundaries(Path home)throws Exception{
        Files.createDirectories(home);Path file=home.resolve("proof.txt");Files.writeString(file,"synthetic proof");
        try(var server=new Server("empty",file)){
            var group=new AgentGroup(()->tools(home),home.resolve("empty-archives"),65536,1024,false,70);var invalid=group.run(request("one","two","three","four"),Provider.CUSTOM,server.url(),"","model","label","system",new Ui());check(invalid.isError&&server.requests.isEmpty(),"more than three tasks rejected before inference");var empty=Json.parse(group.run(request("empty answer"),Provider.CUSTOM,server.url(),"","model","label","system",new Ui()).text);check("failed".equals(Json.str(empty,"status"))&&!"done".equals(Json.str(empty,"tasks",0,"status")),"empty answer is failure, not done");
        }
        try(var server=new Server("normal",file)){
            var small=new AgentGroup(()->tools(home),home.resolve("small-archives"),128,4096,false,70);Object result=Json.parse(small.run(request("bounded context"),Provider.CUSTOM,server.url(),"","model","label","system",new Ui()).text);check(!"done".equals(Json.str(result,"status"))&&server.requests.isEmpty(),"context overflow stops before inference");
            Path blocked=home.resolve("archive-file");Files.writeString(blocked,"occupied");var group=new AgentGroup(()->null,blocked,65536,1024,false,70);Object archiveFailure=Json.parse(group.run(request("independent analysis"),Provider.CUSTOM,server.url(),"","model","label","system",new Ui()).text);check("done".equals(Json.str(archiveFailure,"tasks",0,"status"))&&Json.str(archiveFailure,"tasks",0,"archive_error")!=null,"archive failure is separate from completed task");
        }
        try(var server=new Server("loop",file)){
            var group=new AgentGroup(()->tools(home),home.resolve("loop-archives"),65536,1024,false,70);Object result=Json.parse(group.run(request("never ending read loop"),Provider.CUSTOM,server.url(),"","model","label","system",new Ui()).text);check(!"done".equals(Json.str(result,"status"))&&server.requests.size()<=16,"child loop limited to sixteen requests or less");check(!((List<?>)Json.get(result,"tasks",0,"completed_tools")).isEmpty(),"bounded failure retains completed tools");
        }
        try(var server=new Server("normal",file)){
            Ui ui=new Ui();var group=new AgentGroup(()->null,home.resolve("profile-archives"),65536,8192,false,70);group.run(request("profile output"),Provider.CUSTOM,server.url(),"","model","label","system",ui);check(TokenUsage.number(server.requests.get(0),"max_tokens",0)==8192,"child respects selected response profile without hidden 2K/4K cap");check(ui.progress.stream().anyMatch(p->p.endsWith(":waiting_api")),"visible waiting API state");
        }
        try(var server=new Server("slow",file)){
            var group=new AgentGroup(()->tools(home),home.resolve("cancel-archives"),65536,1024,false,70);ExecutorService worker=Executors.newSingleThreadExecutor();try{Future<Tools.Result> future=worker.submit(()->group.run(request("slow analysis"),Provider.CUSTOM,server.url(),"","model","label","system",new Ui()));check(server.arrived.await(5,TimeUnit.SECONDS),"slow child started");long start=System.nanoTime();group.cancel();Object evidence=Json.parse(group.pendingEvidence());check("slow analysis".equals(Json.str(evidence,"tasks",0,"task"))&&!"done".equals(Json.str(evidence,"tasks",0,"status")),"Stop snapshot preserves task without claiming completion");check(Json.str(evidence,"group_id")!=null&&"model".equals(Json.str(evidence,"model")),"Stop snapshot retains stable group identity and selected model");Object result=Json.parse(future.get(6,TimeUnit.SECONDS).text);check("cancelled".equals(Json.str(result,"status"))&&TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-start)<6,"Stop cancels active children promptly");}finally{worker.shutdownNow();}
        }
    }
    public static void main(String[] args)throws Exception{Path home=Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("localchat.home"))),"run-");normal(home.resolve("normal"));anthropic(home.resolve("anthropic"));boundaries(home.resolve("boundaries"));System.out.println("PASS: "+checks+" runtime agent-group checks; selected model, isolated contexts, bounded parallelism, actual tool outcomes, private archives and Stop");System.exit(0);}
}
