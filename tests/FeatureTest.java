package localchat;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import java.lang.reflect.*;

/** Offline protocol + persistence + actual UI workflow integration tests against a loopback fixture. */
public final class FeatureTest {
    static int checks;
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    @SuppressWarnings("unchecked") static <T>T field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return (T)f.get(target);}
    static Object call(Object target,String name,Class<?>[] types,Object... args)throws Exception{Method m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(target,args);}
    static void command(Object target,String text)throws Exception{call(target,"executeCommand",new Class<?>[]{String.class},text);}
    interface Work{void run()throws Exception;}
    static void edt(Work work)throws Exception{Throwable[] failure={null};SwingUtilities.invokeAndWait(()->{try{work.run();}catch(Throwable e){failure[0]=e;}});if(failure[0]!=null)throw new Exception(failure[0]);}
    static void idle(LocalChat app)throws Exception{for(int n=0;n<500;n++){boolean[] busy={false};edt(()->busy[0]=field(app,"busy"));if(!busy[0])return;Thread.sleep(20);}throw new AssertionError("UI worker did not finish");}
    record Response(int status,String type,String body){}
    static Response json(String body){return new Response(200,"application/json",body);}
    static Response sse(String body){return new Response(200,"text/event-stream",body);}
    static String event(Object obj){return "data: "+Json.write(obj)+"\n\n";}
    static String answer(String text,long in,long out){return Json.write(Json.obj("choices",List.of(Json.obj("message",Json.obj("content",text))),"usage",Json.obj("prompt_tokens",in,"completion_tokens",out)));}
    static final class Server implements AutoCloseable {
        final ServerSocket socket=new ServerSocket(0,20,InetAddress.getByName("127.0.0.1"));
        final BlockingQueue<Response> replies=new LinkedBlockingQueue<>();
        final List<Object> requests=Collections.synchronizedList(new ArrayList<>());
        volatile Throwable error;
        Server(Response... supplied)throws Exception{
            replies.addAll(Arrays.asList(supplied));
            Thread worker=new Thread(()->{try{while(!socket.isClosed())try(Socket connection=socket.accept()){
                connection.setSoTimeout(5000);InputStream in=connection.getInputStream();ByteArrayOutputStream header=new ByteArrayOutputStream();
                int b;while((b=in.read())>=0){header.write(b);if(header.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n"))break;}
                int length=0;for(String line:header.toString(StandardCharsets.US_ASCII).split("\r\n"))if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(15).strip());
                requests.add(Json.parse(new String(in.readNBytes(length),StandardCharsets.UTF_8)));
                Response r=replies.poll(5,TimeUnit.SECONDS);if(r==null)throw new AssertionError("Unexpected API call");byte[] data=r.body.getBytes(StandardCharsets.UTF_8);
                OutputStream out=connection.getOutputStream();out.write(("HTTP/1.1 "+r.status+" Test\r\nContent-Type: "+r.type+"\r\nContent-Length: "+data.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(data);out.flush();
            }}catch(SocketException e){if(!socket.isClosed())error=e;}catch(Throwable e){error=e;}},"fixture-api");worker.setDaemon(true);worker.start();
        }
        String url(){return "http://127.0.0.1:"+socket.getLocalPort()+"/v1";}
        public void close()throws Exception{socket.close();if(error!=null)throw new Exception(error);}
    }
    static ChatClient.Reply request(ChatClient client,Server server,Provider provider,boolean stream)throws Exception{
        return client.chat(provider,server.url(),"test-not-a-real-key","test-model","Test system",List.of(ChatClient.Message.user("hello")),List.of(),stream,t->{});
    }
    static void protocols(Path home)throws Exception {
        try(Server server=new Server(json("{\"choices\":[{\"message\":{\"content\":\"hello\"}}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":4,\"prompt_tokens_details\":{\"cached_tokens\":3}}}"))){
            ChatClient c=new ChatClient();check(request(c,server,Provider.OPENAI,false).text.equals("hello"),"plain text preserved");
            check(c.usage().input()==12 && c.usage().output()==4 && c.usage().cached()==3 && c.usage().complete(),"plain usage and cached subset");
        }
        String stream=event(Json.obj("choices",List.of(Json.obj("delta",Json.obj("content","Hi")))))
                +event(Json.obj("choices",List.of(),"usage",Json.obj("prompt_tokens",21,"completion_tokens",6)))+"data: [DONE]\n\n";
        try(Server server=new Server(sse(stream))){ChatClient c=new ChatClient();check(request(c,server,Provider.CUSTOM,true).text.equals("Hi"),"stream text preserved");
            check(c.usage().input()==21 && c.usage().output()==6 && c.usage().complete(),"stream usage-only chunk");
            check(Boolean.TRUE.equals(Json.get(server.requests.get(0),"stream_options","include_usage")),"stream usage requested");}
        String claude=event(Json.obj("type","message_start","message",Json.obj("usage",Json.obj("input_tokens",5,"cache_read_input_tokens",10,"cache_creation_input_tokens",2,"output_tokens",1))))
                +event(Json.obj("type","content_block_delta","delta",Json.obj("type","text_delta","text","Hello")))
                +event(Json.obj("type","message_delta","usage",Json.obj("output_tokens",3)))
                +event(Json.obj("type","message_delta","usage",Json.obj("output_tokens",7)))+event(Json.obj("type","message_stop"));
        try(Server server=new Server(sse(claude))){ChatClient c=new ChatClient();check(request(c,server,Provider.ANTHROPIC,true).text.equals("Hello"),"Claude streaming text");
            check(c.usage().input()==17 && c.usage().output()==7 && c.usage().complete(),"Claude cumulative usage, cache counted once");
            check(Json.get(server.requests.get(0),"stream_options")==null,"Claude has no OpenAI options");}
        try(Server server=new Server(json("{\"choices\":[{\"message\":{\"content\":\"No usage\"}}]}"))){ChatClient c=new ChatClient();request(c,server,Provider.CUSTOM,false);
            check(c.usage().input()==-1 && !c.usage().complete(),"missing usage stays unknown");}
        try(Server server=new Server(sse(stream))){ChatClient c=new ChatClient();c.chat(Provider.CUSTOM,server.url(),"","test","",List.of(),List.of(),true,t->c.cancel());
            check(!c.usage().complete(),"cancelled stream not claimed complete");}
        try(Server server=new Server(new Response(400,"application/json","{\"error\":{\"message\":\"Unsupported stream_options\"}}"),sse("data: {\"choices\":[{\"delta\":{\"content\":\"retry\"}}]}\n\ndata: [DONE]\n\n"))){
            ChatClient c=new ChatClient();check(request(c,server,Provider.CUSTOM,true).text.equals("retry"),"custom server optional usage fallback");
            check(server.requests.size()==2 && Json.get(server.requests.get(1),"stream_options")==null,"retry removes only optional usage field");
            check(Json.get(server.requests.get(0),"messages").equals(Json.get(server.requests.get(1),"messages")),"fallback preserves request messages");}
        TokenUsage.Ledger ledger=new TokenUsage.Ledger();ledger.add(new TokenUsage(10,3,2,0,true));ledger.add(TokenUsage.UNKNOWN);
        TokenUsage.Ledger reloaded=TokenUsage.Ledger.fromJson(ledger.toJson());check(reloaded.input==10 && reloaded.requests==2 && reloaded.unknown==1,"partial ledger roundtrip");
        String toolResponse="{\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":[{\"id\":\"call1\",\"function\":{\"name\":\"list_dir\",\"arguments\":\"{\\\"path\\\":\\\".\\\"}\"}}]}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2}}";
        try(Server server=new Server(json(toolResponse),json(answer("Done",30,4)))){
            Mcp mcp=new Mcp();PathMap paths=new PathMap(Map.of(),home.toString(),home.toString());
            Tools tools=new Tools(new Tools.Context(home,home.resolve("skills"),home.resolve("reguli.md"),home.resolve("setari.properties"),Map.of(),null,mcp,paths,false,EnumSet.noneOf(Tools.Access.class),(a,b)->Tools.Decision.NO),List.of());
            TokenUsage.Ledger total=new TokenUsage.Ledger();AtomicInteger calls=new AtomicInteger();
            new Agent(tools).run(Provider.CUSTOM,server.url(),"","test-model","test","system",List.of(ChatClient.Message.user("List files")),false,new Agent.Ui(){
                public void text(String t){}public void step(){}public void toolStarted(ChatClient.ToolCall c){calls.incrementAndGet();}public void toolFinished(ChatClient.ToolCall c,Tools.Result r){check(!r.isError,"real local list_dir succeeded");}public void usage(TokenUsage u){total.add(u);}});
            check(calls.get()==1 && total.requests==2 && total.input==40 && total.output==6,"agent sums all tool-loop requests");
            check(Json.str(server.requests.get(1),"messages",3,"role").equals("tool"),"tool result preserved in next request");
        }
    }
    static void files(Path home)throws Exception {
        Path project=home.resolve("project");Files.createDirectories(project);Path report=project.resolve("raport română.pdf");Files.writeString(report,"test report");
        check(ReportFiles.discover(Json.write(Json.obj("report",report.toString())),project).equals(List.of(report.toRealPath())),"JSON report with spaces and Unicode");
        check(ReportFiles.discover("Report: "+report,project).contains(report.toRealPath()),"plain-text Windows report path");
        Path outside=home.resolve("outside.pdf");Files.writeString(outside,"outside");
        check(ReportFiles.discover(Json.write(Json.obj("report",outside.toString())),project).isEmpty(),"outside project report rejected");
        check(!ReportFiles.allowed(project.resolve("missing.pdf"),project),"missing report rejected");
        Path key=project.resolve("setari.properties");Files.writeString(key,"test-only");check(!ReportFiles.allowed(key,project),"settings file not an artifact");
        Path old=home.resolve("old.json");Files.writeString(old,"{\"title\":\"Old chat\",\"messages\":[{\"role\":\"user\",\"content\":\"original\"}]}");
        var loaded=Conversations.load(old);check(loaded.state().usage.historicalUnknown && loaded.messages().get(0).content.equals("original"),"legacy history preserved and unknown usage");
        check(!Conversations.newFile(home).equals(Conversations.newFile(home)),"session filenames do not collide");
        String material="Întrebare 👷 ".repeat(1000);List<String> fragments=ContextMemory.chunks(material,128);
        check(String.join("",fragments).equals(material),"chunking preserves all Unicode data");
        check(fragments.size()>1 && fragments.stream().allMatch(v->ContextMemory.estimate(v)<=128),"chunk estimates fit their budget");
        List<ChatClient.Message> paired=new ArrayList<>();paired.add(ChatClient.Message.user("first"));
        paired.add(ChatClient.Message.assistant("",List.of(new ChatClient.ToolCall("id1","list_dir",Map.of())),"fixture"));
        paired.add(ChatClient.Message.toolResult("id1","files",false));paired.add(ChatClient.Message.assistant("done",List.of(),"fixture"));
        for(int i=0;i<3;i++){paired.add(ChatClient.Message.user("question "+i));paired.add(ChatClient.Message.assistant("answer",List.of(),"fixture"));}
        ContextMemory memory=new ContextMemory();memory.through=memory.boundary(paired);memory.summary="prior factual memory";
        check(memory.through==6 && memory.view(paired).size()==5,"compaction boundary preserves complete tool-call pairs");
        check(paired.get(1).toolCalls.get(0).id.equals(paired.get(2).toolCallId),"original tool-call evidence remains paired");
    }
    static void ui(Path home)throws Exception {
        System.setProperty("localchat.home",home.resolve("ui").toString());Files.createDirectories(Path.of(System.getProperty("localchat.home")));
        String summary="Project 507123: preserve Hoofdregel 80. Sources reviewed; PDF check remains pending. No server transfer performed.";
        try(Server server=new Server(json(answer(summary,80,25)),json(answer("Continued",40,8)))){
            LocalChat[] ref={null};Path[] alpha={null},beta={null};int[] originalCount={0};
            edt(()->{
                Constructor<LocalChat> c=LocalChat.class.getDeclaredConstructor();c.setAccessible(true);ref[0]=c.newInstance();LocalChat app=ref[0];
                JComboBox<Provider> provider=field(app,"providerBox");provider.setSelectedItem(Provider.CUSTOM);
                ((JTextField)field(app,"urlField")).setText(server.url());((JComboBox<?>)field(app,"modelBox")).setSelectedItem("alpha-model");
                ((JTextField)field(app,"workDirField")).setText(home.resolve("project").toString());((JCheckBox)field(app,"toolsBox")).setSelected(false);
                command(app,"/new Alpha");alpha[0]=field(app,"conversationFile");
                List<ChatClient.Message> history=field(app,"history");
                for(int i=0;i<6;i++){history.add(ChatClient.Message.user("OLD-TURN-"+i+" source information "+"detail ".repeat(350)));history.add(ChatClient.Message.assistant("decision "+i,List.of(),"fixture"));}
                originalCount[0]=history.size();((JTextArea)field(app,"input")).setText("draft-alpha");command(app,"/context 64000");
                call(app,"saveConversation",new Class<?>[]{});
                command(app,"/new Beta");beta[0]=field(app,"conversationFile");
                check(((List<?>)field(app,"history")).isEmpty(),"new session does not inherit history");
                ((JComboBox<?>)field(app,"modelBox")).setSelectedItem("beta-model");command(app,"/context 32000");
                ((JTextField)field(app,"systemField")).setText("Beta-only instructions");((JCheckBox)field(app,"toolsBox")).setSelected(true);
                ((JTextArea)field(app,"input")).setText("draft-beta");
                command(app,"/switch "+Conversations.shortId(alpha[0]));
                check(((JTextArea)field(app,"input")).getText().equals("draft-alpha"),"draft restored per chat");
                check(((JComboBox<?>)field(app,"modelBox")).getEditor().getItem().equals("alpha-model"),"model restored per chat");
                WorkspacePanel panel=field(app,"workspace");check(panel.limit.getValue().equals(64000),"context limit restored per chat");
                check(((JTextField)field(app,"systemField")).getText().isBlank(),"system instructions isolated per chat");
                check(!((JCheckBox)field(app,"toolsBox")).isSelected(),"tool mode restored per chat");
                check(server.requests.isEmpty(),"loading or listing chats makes no API calls");
                check(panel.sessions.size()==2,"started chats visible in sidebar");
                check(panel.list.getSelectedValue().file().equals(alpha[0]),"active sidebar row follows slash navigation");
                command(app,"/pin");check(Conversations.load(alpha[0]).state().pinned,"pin persisted");
                command(app,"/compact");
            });
            idle(ref[0]);
            edt(()->{
                LocalChat app=ref[0];Conversations.State state=field(app,"sessionState");
                check(state.memory.summary.equals(summary) && state.memory.through>0,"compact summary accepted");
                check(((List<?>)field(app,"history")).size()==originalCount[0],"compact does not remove original messages");
                List<ChatClient.Message> view=state.memory.view(field(app,"history"));
                check(view.size()==5 && view.get(1).content.startsWith("OLD-TURN-4"),"compact request view preserves last two exchanges");
                check(state.usage.input==80 && state.usage.output==25,"compact request counted in this chat");
                check(Conversations.load(alpha[0]).messages().size()==originalCount[0],"original transcript stored intact");
                command(app,"/switch "+Conversations.shortId(beta[0]));
                check(((Conversations.State)field(app,"sessionState")).memory.summary.isEmpty(),"compact memory isolated from other chats");
                check(((Conversations.State)field(app,"sessionState")).usage.requests==0,"usage isolated from other chats");
                check(((JTextArea)field(app,"input")).getText().equals("draft-beta"),"other draft retained");
                check(((JTextField)field(app,"systemField")).getText().equals("Beta-only instructions"),"other chat system instructions retained");
                command(app,"/resume "+Conversations.shortId(alpha[0]));
                check(((Conversations.State)field(app,"sessionState")).memory.summary.equals(summary),"compact memory restored when reopening chat");
                ((JTextArea)field(app,"input")).setText("continue after compact");call(app,"send",new Class<?>[]{});
                check(Conversations.load(alpha[0]).messages().size()==originalCount[0]+1,"new question saved before API answer");
            });
            idle(ref[0]);
            edt(()->{
                LocalChat app=ref[0];var state=(Conversations.State)field(app,"sessionState");
                check(state.usage.requests==2 && state.usage.input==120 && state.usage.output==33,"per-chat usage aggregates summary and reply");
                check(state.last.requests==1 && state.last.input==40,"last-turn usage is separate");
                String sent=Json.write(server.requests.get(1));check(sent.contains(summary) && !sent.contains("OLD-TURN-0"),"next request uses compacted view");
                check(Conversations.load(alpha[0]).messages().size()==originalCount[0]+2,"full archive continues after compacting");
                call(app,"addReports",new Class<?>[]{String.class},Json.write(Json.obj("report",home.resolve("project/raport română.pdf").toString())));
                check(((JPanel)field(app,"reportBar")).isVisible(),"existing report available in chat");
                WorkspacePanel panel=field(app,"workspace");
                for(int i=0;i<panel.sessions.size();i++)if(panel.sessions.get(i).file().equals(beta[0])){panel.list.setSelectedIndex(i);break;}
                check(((Path)field(app,"conversationFile")).equals(beta[0]),"single sidebar click opens session");
                for(int i=0;i<panel.sessions.size();i++)if(panel.sessions.get(i).file().equals(alpha[0])){panel.list.setSelectedIndex(i);break;}
                check(((Conversations.State)field(app,"sessionState")).memory.summary.equals(summary),"sidebar restores only selected compact memory");
                call(app,"addReports",new Class<?>[]{String.class},Json.write(Json.obj("report",home.resolve("project/raport română.pdf").toString())));
                int before=server.requests.size();command(app,"/status");command(app,"/token");command(app,"/history");command(app,"/skills");command(app,"/connectors");command(app,"/help");command(app,"/not-a-command");
                check(server.requests.size()==before,"local slash commands do not use API");
                String[][] commands=field(app,"COMMANDS");
                for(String code:I18n.CODES){I18n.setLanguage(code);for(String[] command:commands)check(I18n.contains(command[1]),"command description catalogued");}
                JComboBox<String> languages=field(app,"languageBox"),themes=field(app,"themeBox");languages.setSelectedIndex(1);themes.setSelectedIndex(1);
                app.addNotify();app.validate();
                java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(app.getWidth(),app.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);
                var g=image.createGraphics();app.getRootPane().printAll(g);g.dispose();
                javax.imageio.ImageIO.write(image,"png",home.resolve("workspace-dark.png").toFile());
                app.dispose();
            });
        }
    }

    @SuppressWarnings("unchecked")
    static void fragmented(Path home,boolean fail)throws Exception {
        Path local=home.resolve(fail?"failed-compact":"bounded-compact");Files.createDirectories(local);
        System.setProperty("localchat.home",local.toString());
        String summary="Project 507123; Hoofdregel 80; PDF review pending. No transfer.";
        Response[] replies=new Response[8];Arrays.fill(replies,json(answer(summary,10,4)));
        if(fail)replies[1]=new Response(500,"application/json","{\"error\":{\"message\":\"fixture failure\"}}");
        try(Server server=new Server(replies)) {
            LocalChat[] app={null};
            edt(()->{
                Constructor<LocalChat> c=LocalChat.class.getDeclaredConstructor();c.setAccessible(true);app[0]=c.newInstance();
                ((JComboBox<Provider>)field(app[0],"providerBox")).setSelectedItem(Provider.CUSTOM);
                ((JTextField)field(app[0],"urlField")).setText(server.url());((JComboBox<?>)field(app[0],"modelBox")).setSelectedItem("bounded-model");
                ((JCheckBox)field(app[0],"toolsBox")).setSelected(false);command(app[0],"/new bounded");command(app[0],"/context 4096");
                List<ChatClient.Message> history=field(app[0],"history");
                for(int i=0;i<4;i++){history.add(ChatClient.Message.user("source "+i+" "+"detail ".repeat(1200)));history.add(ChatClient.Message.assistant("decision",List.of(),"fixture"));}
                ((JTextArea)field(app[0],"input")).setText("next");call(app[0],"send",new Class<?>[]{});
                check(server.requests.isEmpty() && !((Boolean)field(app[0],"busy")),"oversized normal request blocked locally");
                command(app[0],"/compact");
            });
            idle(app[0]);
            edt(()->{
                Conversations.State state=field(app[0],"sessionState");
                check(server.requests.size()>=2,"long history compacted in multiple API requests");
                check(state.usage.requests==server.requests.size(),"all compaction requests counted");
                if(fail)check(state.memory.summary.isBlank() && state.memory.through==0 && state.usage.unknown==1,"failed fragment keeps active memory and marks missing usage");
                else check(state.memory.summary.equals(summary) && state.memory.through==4,"bounded compaction applied after all fragments");
                check(((List<?>)field(app[0],"history")).size()==8,"fragmentation preserves full archive");
                for(Object request:server.requests){
                    check(TokenUsage.number(request,"max_tokens",0)==1024,"output budget reserved");
                    long estimate=ContextMemory.estimate(Json.write(Json.get(request,"messages")))+1024;
                    check(estimate<4096,"each fragment and output reserve fit configured window");
                }
                app[0].dispose();
            });
        }
    }

    public static void main(String[] args)throws Exception{
        Path home=Path.of(System.getProperty("localchat.home")).resolve("features");Files.createDirectories(home);
        try{protocols(home);files(home);ui(home);fragmented(home,false);fragmented(home,true);System.out.println("PASS: "+checks+" feature checks (protocols, sessions, compact memory, reports, commands)");}
        finally{edt(()->{for(java.awt.Window w:java.awt.Window.getWindows())w.dispose();});}
        System.exit(0);
    }
}
