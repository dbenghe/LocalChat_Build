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
        volatile boolean cancellationExpected;
        volatile int delayMillis;
        Server(Response... supplied)throws Exception{
            replies.addAll(Arrays.asList(supplied));
            Thread worker=new Thread(()->{try{while(!socket.isClosed())try(Socket connection=socket.accept()){
                connection.setSoTimeout(5000);InputStream in=connection.getInputStream();ByteArrayOutputStream header=new ByteArrayOutputStream();
                int b;while((b=in.read())>=0){header.write(b);if(header.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n"))break;}
                int length=0;for(String line:header.toString(StandardCharsets.US_ASCII).split("\r\n"))if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(15).strip());
                requests.add(Json.parse(new String(in.readNBytes(length),StandardCharsets.UTF_8)));
                Response r=replies.poll(5,TimeUnit.SECONDS);if(r==null)throw new AssertionError("Unexpected API call");byte[] data=r.body.getBytes(StandardCharsets.UTF_8);
                if(delayMillis>0)Thread.sleep(delayMillis);
                OutputStream out=connection.getOutputStream();out.write(("HTTP/1.1 "+r.status+" Test\r\nContent-Type: "+r.type+"\r\nContent-Length: "+data.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(data);out.flush();
            }}catch(SocketException e){if(!socket.isClosed() && !cancellationExpected)error=e;}catch(Throwable e){error=e;}},"fixture-api");worker.setDaemon(true);worker.start();
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
            TokenUsage.Ledger total=new TokenUsage.Ledger();AtomicInteger calls=new AtomicInteger();List<List<ChatClient.Message>> contexts=new ArrayList<>();
            new Agent(tools).run(Provider.CUSTOM,server.url(),"","test-model","test","system",List.of(ChatClient.Message.user("List files")),false,new Agent.Ui(){
                public void context(List<ChatClient.Message> snapshot){contexts.add(snapshot);}public void text(String t){}public void step(){}public void toolStarted(ChatClient.ToolCall c){calls.incrementAndGet();}public void toolFinished(ChatClient.ToolCall c,Tools.Result r){check(!r.isError,"real local list_dir succeeded");}public void usage(TokenUsage u){total.add(u);}});
            check(calls.get()==1 && total.requests==2 && total.input==40 && total.output==6,"agent sums all tool-loop requests");
            check(contexts.size()==3 && contexts.get(0).size()==2 && contexts.get(1).size()==3 && contexts.get(2).size()==4,"live snapshots after reply, tool and final reply are independent");
            check(contexts.get(1).get(2).role.equals("tool") && ContextMemory.messages(contexts.get(1))>ContextMemory.messages(contexts.get(0)),"tool result contributes to live context BEFORE next model request");
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
                check(TokenUsage.number(server.requests.get(1),"max_tokens",0)>0 && TokenUsage.number(server.requests.get(1),"max_tokens",0)<=4096,"selected reply budget reaches the actual API request");String sent=Json.write(server.requests.get(1));check(sent.contains(summary) && !sent.contains("OLD-TURN-0"),"next request uses compacted view");
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


    @SuppressWarnings("unchecked")
    static void automatic(Path home,int mode)throws Exception {
        Path local=home.resolve("automatic-"+mode);Files.createDirectories(local);System.setProperty("localchat.home",local.toString());
        String summary="Project 507123: Hoofdregel 80. Previous sources reviewed; no transfer performed.";
        Response[] responses=mode==3 || mode==4?new Response[]{json(answer("auto reply",40,8))}
                :mode==5?new Response[]{new Response(500,"application/json","{\"error\":{\"message\":\"auto fixture failure\"}}")}
                :new Response[]{json(answer(summary,80,25)),json(answer("auto reply",40,8))};
        try(Server server=new Server(responses)) {
            if(mode==1 || mode==2)server.delayMillis=200;
            server.cancellationExpected=mode==2;
            LocalChat[] app={null};Path[] file={null};
            edt(()->{
                Constructor<LocalChat> c=LocalChat.class.getDeclaredConstructor();c.setAccessible(true);app[0]=c.newInstance();
                ((JComboBox<Provider>)field(app[0],"providerBox")).setSelectedItem(Provider.CUSTOM);
                ((JTextField)field(app[0],"urlField")).setText(server.url());((JComboBox<?>)field(app[0],"modelBox")).setSelectedItem("auto-model");
                ((JCheckBox)field(app[0],"toolsBox")).setSelected(false);command(app[0],"/new Auto test");
                List<ChatClient.Message> history=field(app[0],"history");
                for(int i=0;i<6;i++){history.add(ChatClient.Message.user("source "+i+" "+"detail ".repeat(350)));history.add(ChatClient.Message.assistant("decision",List.of(),"fixture"));}
                command(app[0],"/context "+(mode==3?0:8192));WorkspacePanel panel=field(app[0],"workspace");
                check(!panel.autoCompact.isSelected(),"auto compaction is off by default");
                if(mode!=4)panel.autoCompact.doClick();panel.autoThreshold.setValue(30);
                file[0]=field(app[0],"conversationFile");
                check(!panel.autoThreshold.isEnabled() || mode!=3,"unknown context does not activate threshold");
                ((JTextArea)field(app[0],"input")).setText("pending message");call(app[0],"send",new Class<?>[]{});
                if(mode==1)((JTextArea)field(app[0],"input")).setText("edited draft");
                if(mode==2)command(app[0],"/stop");
            });
            idle(app[0]);if(mode==2)Thread.sleep(250);
            edt(()->{
                Conversations.State state=field(app[0],"sessionState");List<ChatClient.Message> history=field(app[0],"history");
                if(mode==0) {
                    check(server.requests.size()==2 && state.memory.through>0,"threshold triggers summary then one answer");
                    check(history.size()==14 && history.get(12).content.equals("pending message"),"pending message sent exactly once after compacting");
                    check(state.usage.requests==2 && state.usage.input==120,"automatic summary and reply usage counted together");
                    check(Json.write(server.requests.get(1)).contains(summary) && !Json.write(server.requests.get(1)).contains("source 0"),"auto reply uses compacted context");
                    check(state.last.requests==1 && state.last.input==40,"last response excludes prior compaction requests");
                } else if(mode==1) {
                    check(server.requests.size()==1 && state.memory.through>0,"edited draft permits summary but prevents automatic send");
                    check(history.size()==12 && ((JTextArea)field(app[0],"input")).getText().equals("edited draft"),"edited draft retained");
                } else if(mode==2) {
                    check(server.requests.size()<=1 && state.memory.through==0,"Stop prevents auto send and applying unfinished summary");
                    check(history.size()==12 && ((JTextArea)field(app[0],"input")).getText().equals("pending message"),"Stop preserves pending message");
                } else if(mode==5) {
                    check(server.requests.size()==1 && state.memory.through==0,"failed auto compaction never sends pending message");
                    check(history.size()==12 && ((JTextArea)field(app[0],"input")).getText().equals("pending message"),"failed auto compaction retains draft and archive");
                } else check(server.requests.size()==1 && state.memory.through==0 && history.size()==14,"disabled or unknown-limit auto mode sends normally without compacting");
                var saved=Conversations.load(file[0]);check(saved.state().autoCompact==(mode!=4) && saved.state().autoThreshold==30,"auto preferences persisted per chat");
                command(app[0],"/new Different chat");
                check(!((Conversations.State)field(app[0],"sessionState")).autoCompact,"new chat has independent auto preference; mode="+mode+" status="+((JLabel)field(app[0],"status")).getText());
                command(app[0],"/switch "+Conversations.shortId(file[0]));
                WorkspacePanel panel=field(app[0],"workspace");check(panel.autoCompact.isSelected()==(mode!=4) && panel.autoThreshold.getValue().equals(30),"auto controls restored on chat switch; mode="+mode+" selected="+panel.autoCompact.isSelected()+" threshold="+panel.autoThreshold.getValue()+" status="+((JLabel)field(app[0],"status")).getText());
                if(mode==0){
                    JComboBox<String> language=field(app[0],"languageBox"),theme=field(app[0],"themeBox");language.setSelectedIndex(1);theme.setSelectedIndex(1);
                    app[0].addNotify();app[0].validate();var image=new java.awt.image.BufferedImage(app[0].getWidth(),app[0].getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);
                    var g=image.createGraphics();app[0].getRootPane().printAll(g);g.dispose();javax.imageio.ImageIO.write(image,"png",home.resolve("automatic-dark.png").toFile());
                }
                app[0].dispose();
            });
        }
    }

    static void management(Path home)throws Exception {
        Path dir=home.resolve("management");Files.createDirectories(dir);
        var state=new Conversations.State();state.archived=true;state.responseProfile=2;state.memory.summary="verified summary";state.memory.through=2;state.draft="unsent";
        Path a=Conversations.newFile(dir);Conversations.save(a,home.toString(),List.of(ChatClient.Message.user("first"),ChatClient.Message.assistant("answer",List.of(),"model")),state);
        var loaded=Conversations.load(a);check(loaded.state().archived && loaded.state().responseProfile==2 && loaded.messages().size()==2,"archive/profile backward-compatible roundtrip");
        Path zip=home.resolve("all-chats.zip");Conversations.export(Conversations.list(dir),zip);
        try(var in=new java.util.zip.ZipInputStream(Files.newInputStream(zip))) {
            check(in.getNextEntry().getName().equals(a.getFileName().toString()),"all-chat export preserves unique filenames");
            Object json=Json.parse(new String(in.readAllBytes(),StandardCharsets.UTF_8));
            check(Json.str(json,"state","draft").equals("unsent") && Json.str(json,"state","summary").equals("verified summary"),"JSON export contains draft and compact memory");
            check(((List<?>)Json.get(json,"messages")).size()==2 && Json.get(json,"key")==null,"JSON export retains complete original transcript without settings keys");
        }
        var snapshot=Conversations.list(dir);Path deleted=Conversations.trash(a);
        byte[] previousExport=Files.readAllBytes(zip);boolean failed=false;try{Conversations.export(snapshot,zip);}catch(IOException expected){failed=true;}
        check(failed && Arrays.equals(previousExport,Files.readAllBytes(zip)),"failed export preserves previous export file");check(!Files.exists(a)&&Files.exists(deleted)&&Conversations.list(dir).isEmpty(),"reversible deletion is excluded from listings and exports");
        Mcp mcp=new Mcp();Tools tools=new Tools(new Tools.Context(home,home.resolve("skills"),home.resolve("read-only-rules.md"),home.resolve("setari.properties"),Map.of(),null,mcp,PathMap.none(),true,EnumSet.allOf(Tools.Access.class),(x,y)->{throw new AssertionError("Read only must not prompt for writes");}),List.of()).readOnly(true);
        check(!tools.execute(new ChatClient.ToolCall("r","list_dir",Json.obj("path",home.toString()))).isError,"read only permits reading selected folder");
        check(tools.execute(new ChatClient.ToolCall("w","write_file",Json.obj("path",home.resolve("blocked.txt").toString(),"content","bad"))).isError && !Files.exists(home.resolve("blocked.txt")),"read only overrides full-access and session grants");
        check(tools.execute(new ChatClient.ToolCall("s","save_rule",Json.obj("rule","blocked"))).isError && !Files.exists(home.resolve("read-only-rules.md")),"read only blocks saved rules too");
        check(tools.execute(new ChatClient.ToolCall("c","run_command",Json.obj("command","echo bad"))).isError,"read only blocks command execution");
        check(PathMap.workspacePath("C:\\Users\\Old\\Downloads\\PRODUCTIE\\507774\\test.pdf","D:\\Users\\New\\Downloads\\PRODUCTIE").equals("D:\\Users\\New\\Downloads\\PRODUCTIE\\507774\\test.pdf"),"historical project suffix maps to selected workspace");
        check(PathMap.workspacePath("C:\\Users\\Old\\Downloads\\PRODUCTIE-OTHER\\test.pdf","D:\\Users\\New\\Downloads\\PRODUCTIE").startsWith("C:"),"mapping observes folder boundary");
        check(new PathMap(Map.of(),home.toString(),home.toString()).workspace(home.toString()).path(deleted.toString()).equals(deleted.toString()),"existing explicit file paths win over translations");
        Path portableRoot=home.resolve("Downloads/PRODUCTIE");Files.createDirectories(portableRoot);
        String historical=System.getProperty("user.home")+"\\Downloads\\PRODUCTIE\\missing-historical-507774";
        PathMap portable=new PathMap(Map.of(),home.toString(),home.resolve("Downloads").toString()).workspace(portableRoot.toString());
        check(portable.path(historical).equals(portableRoot+"\\missing-historical-507774"),"missing old project path maps even when original Windows profile exists");
        Object metadata=Json.parse("{\"models\":[{\"key\":\"test-model\",\"max_context_length\":131072,\"loaded_instances\":[{\"id\":\"instance-a\",\"config\":{\"context_length\":32768}},{\"id\":\"instance-b\",\"config\":{\"context_length\":8192}}]}]}");
        var info=ModelInfo.parse(metadata,"test-model","fixture");check(info.maximum()==131072&&info.loaded()==8192,"metadata distinguishes theoretical max from conservative loaded limit");
        check(ModelInfo.parse(metadata,"instance-a","fixture").loaded()==32768,"metadata matches exact instance identifier");
        check(ModelInfo.parse(metadata,"other-model","fixture").maximum()==0,"no heuristic model limit guessing");
        check(ModelInfo.parse(Json.parse("{\"data\":[{\"id\":\"test-model\",\"max_context_length\":65536}]}"),"test-model","v0").loaded()==0,"v0 maximum is not a loaded context");
        // Real HTTP fixture for metadata GET: no inference or model-load request is allowed.
        try(ServerSocket socket=new ServerSocket(0,2,InetAddress.getByName("127.0.0.1"))) {
            AtomicReference<String> received=new AtomicReference<>();
            Thread server=new Thread(()->{try(Socket c=socket.accept()){
                c.setSoTimeout(3000);BufferedReader reader=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.US_ASCII));received.set(reader.readLine());String line;while((line=reader.readLine())!=null&&!line.isEmpty()){}
                byte[] data=Json.write(metadata).getBytes(StandardCharsets.UTF_8);var out=c.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Length: "+data.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(data);
            }catch(IOException e){throw new RuntimeException(e);}});server.start();
            var fetched=ModelInfo.fetch("http://127.0.0.1:"+socket.getLocalPort()+"/v1","fixture-key","instance-a");server.join(4000);
            check(fetched.loaded()==32768 && received.get().equals("GET /api/v1/models HTTP/1.1"),"metadata uses read-only native endpoint without chat request");
        }
        final LocalChat[] app={null};edt(()->{
            var ctor=LocalChat.class.getDeclaredConstructor();ctor.setAccessible(true);app[0]=ctor.newInstance();
            JComboBox<Provider> provider=field(app[0],"providerBox");provider.setSelectedItem(Provider.CUSTOM);
            JComboBox<String> model=field(app[0],"modelBox");model.setSelectedItem("default-fixture-model");
            ((JTextField)field(app[0],"urlField")).setText("http://127.0.0.1:1/v1");((JTextField)field(app[0],"workDirField")).setText(home.toString());
            ((JPasswordField)field(app[0],"keyField")).setText("fixture-key-not-real");
            call(app[0],"saveDefaults",new Class<?>[]{});command(app[0],"/new One");
            WorkspacePanel panel=field(app[0],"workspace");
            ((JComboBox<?>)field(app[0],"responseProfile")).setSelectedIndex(2);
            Path first=field(app[0],"conversationFile");command(app[0],"/new Two");
            check(((JComboBox<?>)field(app[0],"modelBox")).getSelectedItem().equals("default-fixture-model"),"new chat restores explicitly saved default model");
            check(((JComboBox<?>)field(app[0],"responseProfile")).getSelectedIndex()==1,"new chat has separate response profile");
            command(app[0],"/switch "+Conversations.shortId(first));
            check(((JComboBox<?>)field(app[0],"responseProfile")).getSelectedIndex()==2,"resumed chat restores response profile without API call");
            call(app[0],"archiveSession",new Class<?>[]{});check(Conversations.load(first).state().archived,"archive action persists state");
            for(int i=0;i<panel.sessions.size();i++)check(!panel.sessions.get(i).state().archived,"archived chats excluded from active view");
            panel.archived.doClick();check(panel.sessions.size()==1&&panel.sessions.get(0).file().equals(first),"archived view shows saved archive");
            call(app[0],"openSession",new Class<?>[]{Conversations.Saved.class},Conversations.load(first));call(app[0],"archiveSession",new Class<?>[]{});
            check(!Conversations.load(first).state().archived,"restore action retains transcript");
            app[0].dispose();var restarted=ctor.newInstance();check(((JComboBox<?>)field(restarted,"modelBox")).getSelectedItem().equals("default-fixture-model"),"saved default restored at application startup");
            check(new String(((JPasswordField)field(restarted,"keyField")).getPassword()).equals("fixture-key-not-real"),"API configuration persists locally");restarted.dispose();
        });
    }

    static final class MetadataServer implements AutoCloseable {
        final ServerSocket socket=new ServerSocket(0,10,InetAddress.getByName("127.0.0.1"));
        final List<String> requests=Collections.synchronizedList(new ArrayList<>());
        volatile long loaded=8192;volatile int status=200,delay;volatile Throwable error;
        MetadataServer()throws Exception {
            Thread thread=new Thread(()->{try{while(!socket.isClosed())try(Socket c=socket.accept()) {
                c.setSoTimeout(4000);var reader=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.US_ASCII));requests.add(reader.readLine());String line;while((line=reader.readLine())!=null&&!line.isEmpty()){}
                if(delay>0)Thread.sleep(delay);
                byte[] body=Json.write(Json.obj("models",List.of(Json.obj("key","auto-model","max_context_length",65536,"loaded_instances",List.of(Json.obj("id","auto-model","config",Json.obj("context_length",loaded))))))).getBytes(StandardCharsets.UTF_8);
                var out=c.getOutputStream();out.write(("HTTP/1.1 "+status+" Fixture\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);
            }}catch(SocketException e){if(!socket.isClosed())error=e;}catch(Throwable e){error=e;}},"fixture-model-metadata");thread.setDaemon(true);thread.start();
        }
        String url(){return "http://127.0.0.1:"+socket.getLocalPort()+"/v1";}
        public void close()throws Exception{socket.close();if(error!=null)throw new Exception(error);}
    }
    static void metadataIdle(LocalChat app)throws Exception {
        for(int i=0;i<250;i++){boolean[] active={false};edt(()->active[0]=field(app,"inspectingModel"));if(!active[0])return;Thread.sleep(20);}throw new AssertionError("Metadata never completed");
    }
    static void modernUi(Path home)throws Exception {
        System.setProperty("localchat.home",home.resolve("modern-ui").toString());Files.createDirectories(Path.of(System.getProperty("localchat.home")));
        try(MetadataServer server=new MetadataServer()) {
            LocalChat[] app={null};int[] angle={0};
            edt(()->{
                var ctor=LocalChat.class.getDeclaredConstructor();ctor.setAccessible(true);app[0]=ctor.newInstance();
                ((JComboBox<Provider>)field(app[0],"providerBox")).setSelectedItem(Provider.CUSTOM);
                ((JComboBox<String>)field(app[0],"modelBox")).setSelectedItem("auto-model");((JTextField)field(app[0],"urlField")).setText(server.url());
                ((JTextField)field(app[0],"workDirField")).setText(home.toString());
                call(app[0],"autoInspectModel",new Class<?>[]{});
                check(server.requests.isEmpty(),"automatic metadata is debounced");
                Field since=LocalChat.class.getDeclaredField("metadataProfileSince");since.setAccessible(true);since.setLong(app[0],System.currentTimeMillis()-1000);
                call(app[0],"autoInspectModel",new Class<?>[]{});
            });
            metadataIdle(app[0]);
            edt(()->{
                WorkspacePanel panel=field(app[0],"workspace");check(panel.limit.getValue().equals(8192),"automatic server metadata configures loaded context");
                check(((ContextRing)field(app[0],"contextRing")).maximum==8192,"automatic context reaches footer percent denominator");
                check(server.requests.size()==1 && server.requests.get(0).equals("GET /api/v1/models HTTP/1.1"),"automatic context uses GET, never inference or load");
                call(app[0],"autoInspectModel",new Class<?>[]{});check(server.requests.size()==1,"automatic metadata requests are rate-limited");
                JTextArea draft=field(app[0],"input");ContextRing ring=field(app[0],"contextRing");long before=ring.conversation;draft.setText("live context ".repeat(1000));check(ring.conversation>before,"draft context updates immediately without waiting for timer");draft.setText("");
                server.loaded=16384;call(app[0],"inspectModel",new Class<?>[]{});
            });
            metadataIdle(app[0]);
            edt(()->{
                check(((WorkspacePanel)field(app[0],"workspace")).limit.getValue().equals(16384) && server.requests.size()==2,"metadata can refresh again after first request completes");
                server.status=500;call(app[0],"inspectModel",new Class<?>[]{boolean.class},true);
            });
            metadataIdle(app[0]);
            edt(()->{
                check(server.requests.size()==5 && !(Boolean)field(app[0],"inspectingModel"),"native/catalog failure clears in-flight flag for future retries");
                server.status=200;server.loaded=32768;call(app[0],"inspectModel",new Class<?>[]{});
            });
            metadataIdle(app[0]);
            edt(()->{
                check(((WorkspacePanel)field(app[0],"workspace")).limit.getValue().equals(32768),"metadata recovers after server failure");
                server.delay=120;call(app[0],"inspectModel",new Class<?>[]{});
                ((JComboBox<String>)field(app[0],"modelBox")).setSelectedItem("another-model");call(app[0],"refreshDashboard",new Class<?>[]{});
            });
            metadataIdle(app[0]);
            edt(()->{
                String changedProfile=(String)call(app[0],"contextProfile",new Class<?>[]{});Properties changedConfig=field(app[0],"config");
                check(((WorkspacePanel)field(app[0],"workspace")).limit.getValue().equals(32768) && changedConfig.getProperty(changedProfile+".limitSource","").equals("fallback") && !changedConfig.containsKey(changedProfile+".metadata"),"late metadata never becomes another model's context; local working fallback is separate");
                server.delay=0;((JComboBox<String>)field(app[0],"modelBox")).setSelectedItem("auto-model");call(app[0],"refreshDashboard",new Class<?>[]{});
                ((JComboBox<String>)field(app[0],"responseProfile")).setSelectedIndex(2);((JCheckBox)field(app[0],"streamBox")).setSelected(false);((JCheckBox)field(app[0],"toolsBox")).setSelected(false);
                ((JComboBox<String>)field(app[0],"permissionBox")).setSelectedIndex(2);((JTextField)field(app[0],"systemField")).setText("saved full defaults");
                WorkspacePanel panel=field(app[0],"workspace");panel.autoCompact.doClick();panel.autoThreshold.setValue(55);
                call(app[0],"saveDefaults",new Class<?>[]{});command(app[0],"/new Short title");
                check(((JComboBox<?>)field(app[0],"responseProfile")).getSelectedIndex()==2,"new chat restores saved response profile");
                check(!((JCheckBox)field(app[0],"streamBox")).isSelected() && !((JCheckBox)field(app[0],"toolsBox")).isSelected(),"new chat restores stream and tool mode");
                check(panel.autoCompact.isSelected() && panel.autoThreshold.getValue().equals(55),"new chat restores explicit auto-compaction defaults");
                var row=panel.list.getSelectedValue();var label=(JLabel)panel.list.getCellRenderer().getListCellRendererComponent(panel.list,row,0,false,false);
                check(label.getText().equals("Short title") && !label.getText().contains(Conversations.shortId(row.file())),"sidebar shows title without internal identifier");
                call(app[0],"setBusy",new Class<?>[]{boolean.class},true);app[0].addNotify();app[0].validate();BusyIndicator spinner=field(app[0],"busyIndicator");angle[0]=spinner.angle;
            });
            Thread.sleep(170);
            edt(()->{
                BusyIndicator spinner=field(app[0],"busyIndicator");check(spinner.animation.isRunning() && spinner.angle!=angle[0],"busy indicator visibly rotates over time");
                call(app[0],"setBusy",new Class<?>[]{boolean.class},false);check(!spinner.active&&!spinner.animation.isRunning(),"activity stops after generation");
                call(app[0],"saveConversation",new Class<?>[]{});app[0].dispose();check(!spinner.animation.isRunning(),"disposing chat stops animation");
                var ctor=LocalChat.class.getDeclaredConstructor();ctor.setAccessible(true);var restored=ctor.newInstance();
                check(((JComboBox<?>)field(restored,"responseProfile")).getSelectedIndex()==2 && ((JComboBox<?>)field(restored,"permissionBox")).getSelectedIndex()==2,"restart restores saved profile and approval style");
                check(!((JCheckBox)field(restored,"streamBox")).isSelected() && !((JCheckBox)field(restored,"toolsBox")).isSelected(),"restart restores saved stream and tool selections");
                check(((JTextField)field(restored,"systemField")).getText().equals("saved full defaults") && ((JTextField)field(restored,"workDirField")).getText().equals(home.toString()),"restart restores saved system prompt and working folder");
                WorkspacePanel panel=field(restored,"workspace");check(panel.autoCompact.isSelected() && panel.autoThreshold.getValue().equals(55),"restart restores saved auto-compaction controls");
                restored.dispose();
            });
        }
    }

    static void streamingActivity(Path home)throws Exception {
        System.setProperty("localchat.home",home.resolve("live-stream").toString());Files.createDirectories(Path.of(System.getProperty("localchat.home")));
        try(ServerSocket socket=new ServerSocket(0,2,InetAddress.getByName("127.0.0.1"))) {
            CountDownLatch firstChunk=new CountDownLatch(1),finish=new CountDownLatch(1);AtomicReference<Throwable> error=new AtomicReference<>();
            Thread server=new Thread(()->{try(Socket c=socket.accept()){
                c.setSoTimeout(4000);var in=c.getInputStream();var header=new ByteArrayOutputStream();int b;while((b=in.read())>=0){header.write(b);if(header.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n"))break;}
                int length=0;for(String line:header.toString(StandardCharsets.US_ASCII).split("\r\n"))if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(15).strip());in.readNBytes(length);
                String first=event(Json.obj("choices",List.of(Json.obj("delta",Json.obj("content","live-token ".repeat(600))))));
                String last=event(Json.obj("choices",List.of(),"usage",Json.obj("prompt_tokens",10,"completion_tokens",600)))+"data: [DONE]\n\n";
                byte[] a=first.getBytes(StandardCharsets.UTF_8),z=last.getBytes(StandardCharsets.UTF_8);var out=c.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: "+(a.length+z.length)+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(a);out.flush();firstChunk.countDown();
                if(!finish.await(5,TimeUnit.SECONDS))throw new AssertionError("Stream release timed out");out.write(z);out.flush();
            }catch(Throwable t){error.set(t);firstChunk.countDown();}});server.setDaemon(true);server.start();
            LocalChat[] app={null};long[] before={0};
            try {
                edt(()->{var ctor=LocalChat.class.getDeclaredConstructor();ctor.setAccessible(true);app[0]=ctor.newInstance();
                    ((JComboBox<Provider>)field(app[0],"providerBox")).setSelectedItem(Provider.CUSTOM);((JComboBox<String>)field(app[0],"modelBox")).setSelectedItem("live-model");
                    ((JTextField)field(app[0],"urlField")).setText("http://127.0.0.1:"+socket.getLocalPort()+"/v1");((JCheckBox)field(app[0],"toolsBox")).setSelected(false);
                    command(app[0],"/context 8192");((JTextArea)field(app[0],"input")).setText("hello");before[0]=((ContextRing)field(app[0],"contextRing")).conversation;call(app[0],"send",new Class<?>[]{});
                });
                check(firstChunk.await(4,TimeUnit.SECONDS),"stream begins");boolean[] observed={false};
                for(int i=0;i<150 && !observed[0];i++){edt(()->{ContextRing ring=field(app[0],"contextRing");observed[0]=ring.conversation>before[0]+100;});if(!observed[0])Thread.sleep(10);}
                edt(()->{check(observed[0] && (Boolean)field(app[0],"busy"),"context updates during streaming before final usage arrives");check(((BusyIndicator)field(app[0],"busyIndicator")).active,"busy marker remains active while streaming");ContextRing ring=field(app[0],"contextRing");check(ring.getText().equals("~"+ContextRing.percentage(ring.system+ring.tools+ring.conversation,8192)+"%"),"fractional percentage updates before streamed response completes");});
            }finally{finish.countDown();}
            idle(app[0]);edt(()->{check(!((BusyIndicator)field(app[0],"busyIndicator")).active,"stream completion clears busy marker");app[0].dispose();});server.join(4000);if(error.get()!=null)throw new Exception(error.get());
        }
    }

    public static void main(String[] args)throws Exception{
        Path home=Path.of(System.getProperty("localchat.home")).resolve("features");Files.createDirectories(home);
        try{protocols(home);files(home);ui(home);fragmented(home,false);fragmented(home,true);for(int mode=0;mode<6;mode++)automatic(home,mode);management(home);modernUi(home);streamingActivity(home);System.out.println("PASS: "+checks+" feature checks (protocols, sessions, compact memory, reports, commands)");}
        finally{edt(()->{for(java.awt.Window w:java.awt.Window.getWindows())w.dispose();});}
        System.exit(0);
    }
}
