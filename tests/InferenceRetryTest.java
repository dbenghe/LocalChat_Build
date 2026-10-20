package localchat;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.swing.*;

/** Explicit HTTP gateway errors only; synthetic API and real local tools, no user documents. */
public final class InferenceRetryTest {
    static int checks;
    static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
    static FeatureTest.Response error(int code){return new FeatureTest.Response(code,"application/json","{\"error\":\"gateway fixture\"}");}
    static class Hooks extends MemoryReducerTest.Hooks {
        int retries;boolean cancelOnRetry;
        public void retry(int next,int total,int code){retries++;if(cancelOnRetry)stop=true;}
    }
    static ChatClient.Reply request(FeatureTest.Server server,Hooks hooks,boolean stream,ChatClient.Listener listener)throws IOException{
        return InferenceRetry.chat(new ChatClient(),Provider.CUSTOM,server.url(),"","fixture","system",List.of(ChatClient.Message.user("exact question")),List.of(),stream,listener,hooks);
    }
    static void transport()throws Exception{
        try(var server=new FeatureTest.Server(error(502),error(503),FeatureTest.json(FeatureTest.answer("Recovered",15,4)))){
            Hooks h=new Hooks();check(request(server,h,false,s->{}).text.equals("Recovered"),"502 then 503 recover with the same inference");
            check(h.attempts==3&&h.usage==3&&h.retries==2,"each actual inference counted once including unknown failed usage");
            check(Json.write(server.requests.get(0)).equals(Json.write(server.requests.get(1)))&&Json.write(server.requests.get(1)).equals(Json.write(server.requests.get(2))),"all gateway retries preserve the exact body");
        }
        try(var server=new FeatureTest.Server(error(504),error(504),error(504))){
            Hooks h=new Hooks();try{request(server,h,false,s->{});throw new AssertionError("endless retry accepted");}catch(ChatClient.HttpFailure expected){check(expected.status==504&&server.requests.size()==3&&h.usage==3&&h.retries==2,"persistent gateway failure stops after three attempts");}
        }
        for(int status:new int[]{400,401,403,404,413,429,500})try(var server=new FeatureTest.Server(error(status))){
            Hooks h=new Hooks();try{request(server,h,false,s->{});throw new AssertionError("failure accepted");}catch(ChatClient.HttpFailure expected){check(expected.status==status&&server.requests.size()==1&&h.retries==0,"no automatic replay for HTTP "+status);}
        }
        try(var server=new FeatureTest.Server(error(502))){Hooks h=new Hooks();h.cancelOnRetry=true;try{request(server,h,false,s->{});throw new AssertionError("cancel ignored");}catch(InterruptedIOException expected){check(server.requests.size()==1&&h.usage==1,"Stop during backoff prevents another request");}}
        String partial=FeatureTest.event(Json.obj("choices",List.of(Json.obj("delta",Json.obj("content","partial reply")))))+FeatureTest.event(Json.obj("error","interrupted stream fixture"));
        try(var server=new FeatureTest.Server(FeatureTest.sse(partial))){Hooks h=new Hooks();StringBuilder visible=new StringBuilder();try{request(server,h,true,visible::append);throw new AssertionError("broken stream accepted");}catch(IOException expected){check(server.requests.size()==1&&h.retries==0&&visible.toString().equals("partial reply"),"an interrupted partial stream is never silently replayed");}}
    }
    static String memory="Project 507123. Completed folder creation recorded. No files copied yet. Pending: verify PDF report. C:/project/RAPPORT.pdf.";
    static void summaries()throws Exception{
        try(var server=new FeatureTest.Server(error(502),FeatureTest.json(FeatureTest.answer(memory,60,20)))){
            Hooks h=new Hooks();String result=MemoryReducer.reduce(new ChatClient(),Provider.CUSTOM,server.url(),"","fixture","Summarize historical data",List.of(ChatClient.Message.user("evidence")),256,8192,1.25,h);
            check(result.equals(memory)&&server.requests.size()==2&&h.attempts==2&&h.usage==2,"manual summary retries the same failed fragment and records both requests");
            check(server.requests.stream().allMatch(r->Json.get(r,"tools")==null),"summary retries cannot execute tools");
        }
        try(var server=new FeatureTest.Server(error(502),error(502),error(502))){
            Hooks h=new Hooks();try{MemoryReducer.reduce(new ChatClient(),Provider.CUSTOM,server.url(),"","fixture","Summarize",List.of(ChatClient.Message.user("evidence")),256,8192,1.25,h);throw new AssertionError("summary failure ignored");}catch(IOException expected){check(expected.getMessage().contains(I18n.text("Compactarea a esuat la API; memoria si istoricul au fost pastrate: {0}","")),"terminal error identifies compaction rather than context overflow");}
        }
        List<FeatureTest.Response> replies=new ArrayList<>();replies.add(error(502));for(int i=0;i<20;i++)replies.add(FeatureTest.json(FeatureTest.answer(memory,100,30)));
        try(var server=new FeatureTest.Server(replies.toArray(FeatureTest.Response[]::new))){
            List<ChatClient.Message> original=List.of(ChatClient.Message.user("Exact pending question 507123"),ChatClient.Message.assistant("evidence ".repeat(1200),List.of(),"fixture"));String before=Json.write(original.stream().map(ChatClient.Message::toJson).toList());
            int[] retry={0};var ui=new ContextGuardTest.Ui(){public void retry(boolean compact,int next,int total,int status){if(compact)retry[0]++;}};
            ContextGuard guard=new ContextGuard(8192,1024,true,30,1.25);var active=guard.prepare(Provider.CUSTOM,server.url(),"","fixture","system",original,List.of(),1,ui);
            check(guard.memory!=null&&retry[0]==1&&active.stream().anyMatch(m->m.content.equals(original.get(0).content)),"tool-loop compaction recovers from a gateway error and retains the exact question");
            check(before.equals(Json.write(original.stream().map(ChatClient.Message::toJson).toList())),"compaction recovery preserves the original archive");
            check(ui.usage==server.requests.size()&&ui.summaries==server.requests.size(),"all fragment requests and retries accounted once");
        }
    }
    static void tools(Path home)throws Exception{
        Path file=home.resolve("write-once.txt");Tools tools=new Tools(new Tools.Context(home,home.resolve("skills"),home.resolve("rules.md"),home.resolve("settings.properties"),Map.of(),null,new Mcp(),PathMap.none(),true,EnumSet.allOf(Tools.Access.class),(a,b)->Tools.Decision.NO),List.of());
        String write=PdfAgentTest.request("write_once","write_file",Json.obj("path",file.toString(),"content","verified fixture"));
        try(var server=new FeatureTest.Server(FeatureTest.json(write),error(502),FeatureTest.json(FeatureTest.answer("Done",100,10)))){
            int[] started={0},finished={0},retries={0},usage={0};Agent agent=new Agent(tools);
            agent.run(Provider.CUSTOM,server.url(),"","fixture","test","system",List.of(ChatClient.Message.user("Write the selected fixture once")),false,new Agent.Ui(){public void text(String s){}public void step(){}public void toolStarted(ChatClient.ToolCall c){started[0]++;}public void toolFinished(ChatClient.ToolCall c,Tools.Result r){finished[0]++;}public void usage(TokenUsage u){usage[0]++;}public void retry(boolean compact,int next,int total,int code){retries[0]++;}});
            check(Files.readString(file).equals("verified fixture")&&started[0]==1&&finished[0]==1,"gateway recovery after a real write executes the tool exactly once");
            check(server.requests.size()==3&&retries[0]==1&&usage[0]==3,"agent retry includes failed request in usage");
            check(Json.write(server.requests.get(1)).equals(Json.write(server.requests.get(2))),"resumed inference contains the same completed write evidence");
            check(agent.collect("","test").stream().filter(m->m.role.equals("tool")).count()==1,"one tool result in the complete archive");
        }
    }
    static void manualUi(Path home,boolean fail)throws Exception{
        List<FeatureTest.Response> responses=new ArrayList<>();responses.add(error(502));for(int i=0;i<15;i++)responses.add(fail?error(502):FeatureTest.json(FeatureTest.answer(memory,100,30)));
        try(var server=new FeatureTest.Server(responses.toArray(FeatureTest.Response[]::new))){
            LocalChat[] app={null};String[] before={null};String draft="Unsent exact question 507123";
            FeatureTest.edt(()->{app[0]=AttachmentTest.window(home,server.url());WorkspacePanel workspace=FeatureTest.field(app[0],"workspace");workspace.limit.setValue(32768);var history=FeatureTest.<List<ChatClient.Message>>field(app[0],"history");history.add(ChatClient.Message.user("Prior facts "+"evidence ".repeat(2000)));history.add(ChatClient.Message.assistant("prior verified answer",List.of(),"fixture"));history.add(ChatClient.Message.user("second recent question"));history.add(ChatClient.Message.assistant("second recent answer",List.of(),"fixture"));history.add(ChatClient.Message.user("most recent question"));history.add(ChatClient.Message.assistant("latest verified answer",List.of(),"fixture"));before[0]=Json.write(history.stream().map(ChatClient.Message::toJson).toList());AttachmentTest.input(app[0]).setText(draft);FeatureTest.call(app[0],"compactMemory",new Class<?>[]{});});
            FeatureTest.idle(app[0]);FeatureTest.edt(()->{var state=AttachmentTest.state(app[0]);var history=FeatureTest.<List<ChatClient.Message>>field(app[0],"history");check(AttachmentTest.input(app[0]).getText().equals(draft)&&before[0].equals(Json.write(history.stream().map(ChatClient.Message::toJson).toList())),"manual compaction preserves draft and complete archive, fail="+fail);
                check(state.usage.requests==server.requests.size()&&server.requests.stream().allMatch(r->Json.get(r,"tools")==null),"UI accounts summary retries without operational tools, fail="+fail);
                if(fail)check(state.memory.summary.isEmpty()&&state.memory.through==0&&server.requests.size()==3,"persistent gateway failure applies no incomplete memory or automatic send");
                else check(!state.memory.summary.isEmpty()&&state.memory.through==2,"manual compaction applies the complete recovered summary");app[0].dispose();});
        }
    }
    public static void main(String[] args)throws Exception{
        Path home=Files.createDirectories(Path.of(System.getProperty("localchat.home")));
        try{transport();summaries();tools(home);manualUi(home.resolve("success"),false);manualUi(home.resolve("failure"),true);System.out.println("PASS: "+checks+" inference recovery checks; synthetic HTTP, compaction, cancellation, usage and one real write");}
        finally{FeatureTest.edt(()->{for(var w:java.awt.Window.getWindows())w.dispose();});}System.exit(0);
    }
}
