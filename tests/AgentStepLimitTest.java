package localchat;

import java.nio.file.*;
import java.util.*;

/** Actual Agent/HTTP/tool/persistence regression; synthetic local files, no external model or production writes. */
public final class AgentStepLimitTest {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static class Ui implements Agent.Ui {
        int operations,continuations,finalizations;StringBuilder text=new StringBuilder();
        public void text(String value){text.append(value);}public void step(){}
        public void toolStarted(ChatClient.ToolCall call){operations++;}public void toolFinished(ChatClient.ToolCall call,Tools.Result result){}
        public void stepBoundary(boolean finalResponse){if(finalResponse)finalizations++;else continuations++;}
    }
    static Tools tools(Path home){return new Tools(new Tools.Context(home,home.resolve("skills"),home.resolve("rules.md"),home.resolve("settings.properties"),Map.of(),null,new Mcp(),PathMap.none(),true,EnumSet.allOf(Tools.Access.class),(a,b)->Tools.Decision.YES),List.of());}
    static FeatureTest.Response call(int n,String tool,Object args){return InteractionRecoveryTest.reply("",InteractionRecoveryTest.call("step-"+n,tool,args));}
    static void run(Agent agent,FeatureTest.Server server,Ui ui,boolean stream)throws Exception{agent.run(Provider.CUSTOM,server.url(),"","fixture","Fixture","Keep permissions and scope unchanged.",List.of(ChatClient.Message.user("Check every source and save the report; finish without asking me to continue.")),stream,ui);}
    static void longTurn(Path home,int count,boolean stream)throws Exception{
        InteractionRecoveryTest.streaming=stream;
        List<FeatureTest.Response> replies=new ArrayList<>();
        for(int n=0;n<count-1;n++){Files.writeString(home.resolve("source-"+n+".txt"),"Source evidence "+n);replies.add(call(n,"read_file",Json.obj("path","source-"+n+".txt")));}
        Path report=home.resolve("report-"+count+"-"+stream+".txt");
        replies.add(call(count-1,"write_file",Json.obj("path",report.toString(),"content","Confirmed report for "+count+" steps")));
        replies.add(FeatureTest.json(FeatureTest.answer("All requested checks completed; report saved.",10,8)));
        try(var server=new FeatureTest.Server(replies.toArray(FeatureTest.Response[]::new))){
            Ui ui=new Ui();Agent agent=new Agent(tools(home));run(agent,server,ui,stream);
            check(server.requests.size()==count+1,"final request follows the last tool result after "+count+" steps");
            check(ui.operations==count,"each operation executes once");check(Files.readString(report).startsWith("Confirmed report"),"last report write succeeds");
            check(ui.text.toString().contains("All requested checks completed"),"visible final answer without a second user message");
            check(!agent.recoveryIncomplete(),"productive long turn is not marked partial");check(ui.continuations==count/80,"checkpoint continues automatically");
            Object last=server.requests.get(count);check(Json.write(last).contains("Confirmed report for "+count+" steps"),"final wire request keeps the saved report result");
            var added=agent.collect("","Fixture");Path chat=home.resolve("saved-"+count+"-"+stream+".json");
            Files.writeString(chat,Json.write(added.stream().map(ChatClient.Message::toJson).toList()));
            check(Files.readString(chat).contains("All requested checks completed"),"final answer remains in persisted transcript");
        }
    }
    static void noProgress(Path home)throws Exception{
        InteractionRecoveryTest.streaming=false;List<FeatureTest.Response> replies=new ArrayList<>();
        for(int n=0;n<80;n++){Files.createDirectories(home.resolve("inventory-"+n));replies.add(call(n,"list_dir",Json.obj("path","inventory-"+n)));}
        replies.add(FeatureTest.json(FeatureTest.answer("Only inventories checked; article verification remains incomplete.",8,6)));
        try(var server=new FeatureTest.Server(replies.toArray(FeatureTest.Response[]::new))){Ui ui=new Ui();Agent agent=new Agent(tools(home));run(agent,server,ui,false);
            check(server.requests.size()==81&&ui.operations==80,"unproductive inventory window is bounded");
            check(InteractionRecoveryTest.tools(server.requests.get(80)).isEmpty(),"final request has no operational tools");
            check(agent.recoveryIncomplete()&&ui.text.toString().contains("incomplete"),"honest visible partial result");
        }
    }
    static void bounded(Path home,String mode)throws Exception{
        InteractionRecoveryTest.streaming=false;
        FeatureTest.Response end=switch(mode){
            case "tool"->call(3,"write_file",Json.obj("path","must-not-run.txt","content","NO"));
            case "blank"->FeatureTest.json(FeatureTest.answer("",3,0));
            case "error"->new FeatureTest.Response(400,"application/json","{\"error\":{\"message\":\"fixture rejection\"}}");
            default->FeatureTest.json(FeatureTest.answer("Checked only the available inventory; remaining work is incomplete.",9,7));};
        Files.createDirectories(home.resolve("bounded-inventory"));
        try(var server=new FeatureTest.Server(call(1,"list_dir",Json.obj("path","bounded-inventory")),call(2,"read_file",Json.obj("path","source-0.txt")),end)){
            Ui ui=new Ui();Agent agent=new Agent(tools(home)).maxSteps(3);run(agent,server,ui,false);
            check(server.requests.size()==3&&ui.operations==2,"explicit child limit reserves its final request: "+mode);
            check(InteractionRecoveryTest.tools(server.requests.get(2)).isEmpty(),"bounded finalization cannot extend scope");
            check(agent.recoveryIncomplete()&&!ui.text.isEmpty(),"missing or refused final output has a local partial fallback: "+mode);
            check(!Files.exists(home.resolve("must-not-run.txt")),"provider cannot run a hidden tool during finalization");
            var messages=agent.collect(ui.text.toString(),"Fixture");if(!mode.equals("normal"))check(messages.stream().filter(m->m.content.startsWith("LocalChat:")).count()==1,"local fallback is persisted once");Set<String> results=new HashSet<>();for(var m:messages)if(m.role.equals("tool"))results.add(m.toolCallId);
            check(messages.stream().flatMap(m->m.toolCalls.stream()).allMatch(c->results.contains(c.id)),"every requested tool has a matched result");
        }
    }
    static void stopAtBoundary(Path home)throws Exception{
        InteractionRecoveryTest.streaming=false;List<FeatureTest.Response> replies=new ArrayList<>();
        for(int n=0;n<80;n++)replies.add(call(n,"read_file",Json.obj("path","source-"+n+".txt")));
        try(var server=new FeatureTest.Server(replies.toArray(FeatureTest.Response[]::new))){Agent agent=new Agent(tools(home));Ui ui=new Ui(){public void toolFinished(ChatClient.ToolCall call,Tools.Result result){if(operations==80)agent.cancel();}};run(agent,server,ui,false);
            check(server.requests.size()==80&&ui.continuations==0,"Stop prevents continuation and final inference");
        }
    }
    static void uiTurn(Path home)throws Exception{
        InteractionRecoveryTest.streaming=false;List<FeatureTest.Response> replies=new ArrayList<>();
        for(int n=0;n<79;n++)replies.add(call(n,"read_file",Json.obj("path","source-"+n+".txt")));
        replies.add(call(79,"write_file",Json.obj("path","ui-report.txt","content","UI_REPORT")));
        replies.add(FeatureTest.json(FeatureTest.answer("UI_FINAL_AFTER_EIGHTY",10,4)));
        try(var server=new FeatureTest.Server(replies.toArray(FeatureTest.Response[]::new))){
            LocalChat[] app={null};
            try{
                FeatureTest.edt(()->{app[0]=AttachmentTest.window(home,server.url());((javax.swing.JComboBox<?>)FeatureTest.field(app[0],"permissionBox")).setSelectedIndex(1);AttachmentTest.input(app[0]).setText("Read the local sources, write the requested report, and give the final answer.");FeatureTest.call(app[0],"send",new Class<?>[]{});});
                for(int n=0;n<2250;n++){boolean[] busy={true};FeatureTest.edt(()->busy[0]=FeatureTest.field(app[0],"busy"));if(!busy[0])break;Thread.sleep(20);}
                FeatureTest.edt(()->{
                    check(!FeatureTest.<Boolean>field(app[0],"busy"),"UI worker finishes without Continue");
                    Path file=FeatureTest.field(app[0],"conversationFile");var saved=Conversations.load(file);
                    check(saved.messages().stream().anyMatch(m->m.content.contains("UI_FINAL_AFTER_EIGHTY")),"actual conversation file contains final answer");
                    check(saved.messages().stream().filter(m->m.role.equals("user")).count()==1,"no synthetic Continue message inserted");
                    check(server.requests.size()==81,"UI makes exactly one final request after eighty operations");
                    check(Files.readString(home.resolve("ui-report.txt")).equals("UI_REPORT"),"UI report saved before final answer");
                });
            }finally{if(app[0]!=null)FeatureTest.edt(()->app[0].dispose());}
        }
    }
    static void nativeTurn(Path home)throws Exception{
        Agent agent=new Agent(tools(home));
        ChatClient client=new ChatClient(()->{try{return new CodexConnection(List.of(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-cp",System.getProperty("java.class.path"),AgentStepLimitTest.class.getName(),"--native-fixture"));}catch(Exception e){throw new java.io.IOException(e);}});
        client.nativeAccess(tools(home).nativeAccess());var field=Agent.class.getDeclaredField("client");field.setAccessible(true);field.set(agent,client);
        ContextGuard guard=new ContextGuard(500000,1024,false,80,1.25);agent.guard(guard);
        var latest=new java.util.concurrent.atomic.AtomicReference<List<ChatClient.Message>>();
        Ui ui=new Ui(){public void context(List<ChatClient.Message> messages){latest.set(messages);}public void toolFinished(ChatClient.ToolCall call,Tools.Result result){check(!result.isError,"native fixture tool succeeds");if(operations==80)guard.memory=new ContextGuard.Memory("Confirmed native report",latest.get().size(),"Complete native fixture",List.of());}};
        agent.run(Provider.CODEX,CodexConnection.ROUTE,"","test-model","Fixture","system",List.of(ChatClient.Message.user("Complete native fixture")),true,ui);
        check(ui.operations==80&&ui.continuations==1,"Codex resumes the native RPC after step 80");
        check(ui.text.toString().equals("NATIVE_FINAL"),"Codex supplies its final response after compaction at the boundary");
        check(Files.readString(home.resolve("native-report.txt")).equals("NATIVE_REPORT"),"native report is written once before final response");
    }
    static void nativeFixture()throws Exception{
        var input=new java.io.BufferedReader(new java.io.InputStreamReader(System.in,java.nio.charset.StandardCharsets.UTF_8));int number=0;
        for(String line;(line=input.readLine())!=null;){Object packet=Json.parse(line);Object id=Json.get(packet,"id");String method=Json.str(packet,"method");
            if(method==null&&Json.get(packet,"result")!=null){
                if(++number==80){if(!Json.write(packet).contains("native-report.txt"))throw new AssertionError("last native report result was lost");CodexConnectionTest.delta("NATIVE_FINAL");CodexConnectionTest.done("completed");}
                else nativeCall(number);continue;
            }
            switch(Objects.toString(method,"")){
                case "initialize"->CodexConnectionTest.result(id,Json.obj("userAgent","fixture"));
                case "initialized"->{}
                case "config/read"->CodexConnectionTest.result(id,Json.obj("config",Json.obj()));
                case "account/read"->CodexConnectionTest.result(id,Json.obj("account",Json.obj("type","chatgpt")));
                case "thread/start"->CodexConnectionTest.result(id,Json.obj("thread",Json.obj("id","thread1"),"model","test-model","modelProvider","openai"));
                case "turn/start"->{CodexConnectionTest.result(id,Json.obj("turn",Json.obj("id","turn1")));nativeCall(number);}
                default->throw new AssertionError("Unexpected native fixture method: "+method);
            }
        }
    }
    static void nativeCall(int number){CodexConnectionTest.emit(Json.obj("id",900+number,"method","item/tool/call","params",Json.obj("threadId","thread1","turnId","turn1","callId","native-"+number,"namespace","localchat","tool",number==79?"write_file":"read_file","arguments",number==79?Json.obj("path","native-report.txt","content","NATIVE_REPORT"):Json.obj("path","source-"+number+".txt"))));}
    public static void main(String[] args)throws Exception{
        if(args.length>0&&args[0].equals("--native-fixture")){nativeFixture();return;}
        Path home=Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("localchat.home"))),"steps-");
        longTurn(home,80,false);longTurn(home,80,true);longTurn(home,161,false);nativeTurn(home);uiTurn(home);
        noProgress(home);for(String mode:List.of("normal","blank","tool","error"))bounded(home,mode);stopAtBoundary(home);
        AgentProgress progress=new AgentProgress();var command=new ChatClient.ToolCall("c","run_command",Json.obj("command","echo fixture"));
        progress.completed(command,Tools.Result.error("failed"));check(!progress.advance(),"errors do not extend a turn");
        progress.completed(command,Tools.Result.ok("first"));check(progress.advance(),"successful new operation is progress");
        progress.completed(command,Tools.Result.ok("different timestamp"));check(!progress.advance(),"changed stdout of repeated command is not progress");
        for(String lang:I18n.CODES){I18n.setLanguage(lang);check(I18n.contains("Pregatesc raspunsul final din rezultatele pastrate."),"final status localized: "+lang);}
        System.out.println("PASS: "+checks+" step-limit checks");System.exit(0);
    }
}
