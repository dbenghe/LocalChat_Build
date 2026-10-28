package localchat;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;

/** Synthetic model server, actual local read_file, no external inference or user documents. */
public final class ContextGuardTest {
    static int checks;static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    static class Ui implements Agent.Ui{
        int summaries,usage;double scale;
        public void text(String s){}public void step(){}public void toolStarted(ChatClient.ToolCall c){}public void toolFinished(ChatClient.ToolCall c,Tools.Result r){}
        public void compactRequest(String s,List<ChatClient.Message> m,int f,int t){summaries++;}
        public void usage(TokenUsage u){usage++;}public void calibration(double value){scale=value;}
    }
    static final class Server implements AutoCloseable{
        final ServerSocket socket=new ServerSocket(0,20,InetAddress.getByName("127.0.0.1"));
        final List<Object> requests=Collections.synchronizedList(new ArrayList<>());final AtomicInteger normal=new AtomicInteger(),summaries=new AtomicInteger();
        final Path file;volatile boolean fail;volatile long delay;volatile Throwable error;
        Server(Path file)throws Exception{this.file=file;Thread thread=new Thread(()->{try{while(!socket.isClosed())try(Socket c=socket.accept()){
            c.setSoTimeout(5000);var in=c.getInputStream();var header=new ByteArrayOutputStream();int b;while((b=in.read())>=0){header.write(b);if(header.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n"))break;}
            int length=0;for(String line:header.toString(StandardCharsets.US_ASCII).split("\r\n"))if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(15).strip());
            Object body=Json.parse(new String(in.readNBytes(length),StandardCharsets.UTF_8));requests.add(body);
            boolean summary=Objects.toString(Json.str(body,"messages",0,"content"),"").contains("Summarize historical conversation and completed tool results");
            String reply;if(summary){summaries.incrementAndGet();if(delay>0)Thread.sleep(delay);reply=FeatureTest.answer("Verified project 507123; selected drawing report read. Original evidence remains in source file. No writes performed; user request pending.",100,25);}
            else if(normal.incrementAndGet()==1&&file!=null)reply=Json.write(Json.obj("choices",List.of(Json.obj("message",Json.obj("content","Reading evidence","tool_calls",List.of(Json.obj("id","large_report","type","function","function",Json.obj("name","read_file","arguments",Json.write(Json.obj("path",file.toString(),"limit",3000)))))))),"usage",Json.obj("prompt_tokens",6500,"completion_tokens",40)));
            else reply=FeatureTest.answer("Verified answer",1000,10);
            byte[] data=(summary&&fail?"{\"error\":\"fixture summary failure\"}":reply).getBytes(StandardCharsets.UTF_8);var out=c.getOutputStream();out.write(("HTTP/1.1 "+(summary&&fail?500:200)+" Fixture\r\nContent-Type: application/json\r\nContent-Length: "+data.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(data);out.flush();
        }catch(IOException cancelled){}}catch(Throwable t){if(!socket.isClosed())error=t;}});thread.setDaemon(true);thread.start();}
        String url(){return "http://127.0.0.1:"+socket.getLocalPort()+"/v1";}
        public void close()throws Exception{socket.close();if(error!=null)throw new Exception(error);}
    }
    static List<ChatClient.Message> material(){return List.of(ChatClient.Message.user("Exact question: project 507123, C:/Șantier/report.pdf; create PDF only."),ChatClient.Message.assistant("read",List.of(new ChatClient.ToolCall("a","read_file",Map.of("path","report.pdf"))),"fixture"),ChatClient.Message.toolResult("a","drawing evidence ".repeat(2600),false));}
    static void policy()throws Exception{
        List<ChatClient.Message> original=material();String before=Json.write(original.stream().map(ChatClient.Message::toJson).toList());
        try(Server server=new Server(null)){
            Ui ui=new Ui();ContextGuard guard=new ContextGuard(8192,1024,true,70,1.25);var active=guard.prepare(Provider.CUSTOM,server.url(),"","fixture","system",original,List.of(),1,ui);
            check(ui.summaries>0&&ui.usage==ui.summaries,"every compaction request has separately counted usage");
            check(active.size()==2&&active.get(1).content.equals(original.get(0).content),"latest question preserved byte-for-byte after tool compaction");
            check(guard.predicted(ContextGuard.raw("system",active,List.of()))+guard.reserve()+guard.margin()<8192,"resumed request leaves output budget and safety space");
            check(Json.write(original.stream().map(ChatClient.Message::toJson).toList()).equals(before),"compaction leaves original archive and complete tool result untouched");
            check(server.requests.stream().allMatch(x->Json.get(x,"tools")==null),"summaries cannot execute operational tools");
            check(active.stream().noneMatch(x->x.role.equals("tool")||!x.toolCalls.isEmpty()),"compacted view has no orphan tool result or pending call");
            List<ChatClient.Message> newQuestion=List.of(ChatClient.Message.user("historical data "+"material ".repeat(2000)),ChatClient.Message.user("New exact question before sending"));
            ContextGuard headroom=new ContextGuard(8192,4096,true,95,1.25);long beforeHeadroom=headroom.predicted(ContextGuard.raw("system",newQuestion,List.of()));
            check(beforeHeadroom<8192*95/100 && beforeHeadroom+headroom.reserve()+headroom.margin()>8192,"fixture leaves input room but insufficient reserved response space");
            var pending=headroom.prepare(Provider.CUSTOM,server.url(),"","fixture","system",newQuestion,List.of(),0,ui);
            check(headroom.memory!=null&&pending.get(1).content.equals("New exact question before sending"),"new question compacts older history before first model request when output space is insufficient");
            ContextGuard anchor=new ContextGuard(131072,8192,true,70,1.25);anchor.seed(114518,1);
            var grown=List.of(ChatClient.Message.user("same original request"),ChatClient.Message.toolResult("read","X".repeat(60000),false),ChatClient.Message.toolResult("read2","Y".repeat(60000),false));
            check(anchor.estimate("system",grown,grown,List.of())>131072,"known 114518 input plus two 60K-character tool results cannot be underestimated as a small request");
            ContextGuard calibrated=new ContextGuard(8192,1024,true,70,1.25);calibrated.requestRaw=100;calibrated.observe(new TokenUsage(300,2,0,0,true),ui);check(calibrated.scale>=3.3&&ui.scale>=3.3,"API input usage corrects underestimated token scale");
            check(!ContextGuard.signature("system A",List.of()).equals(ContextGuard.signature("system B",List.of())),"changed system prompt invalidates an API input anchor");
            check(!ContextGuard.signature("system",List.of()).equals(ContextGuard.signature("system",List.of(new ChatClient.ToolSpec("read","description",Map.of())))),"changed tool definitions invalidate an API input anchor");
            ContextGuard other=new ContextGuard(8192,1024,true,70,1.25);check(other.scale==1.25,"calibration isolated between selected model budgets");
            int requests=server.requests.size();ContextGuard unknown=new ContextGuard(0,1024,true,70,1.25);check(unknown.prepare(Provider.CUSTOM,server.url(),"","fixture","system",original,List.of(),1,ui).size()==3&&server.requests.size()==requests,"unknown limit never invents a window or runs compaction");
            ContextGuard disabled=new ContextGuard(8192,1024,false,70,1.25);try{disabled.prepare(Provider.CUSTOM,server.url(),"","fixture","system",original,List.of(),1,ui);throw new AssertionError("oversized request allowed");}catch(IOException expected){check(server.requests.size()==requests,"disabled auto mode blocks overflowing request without inference");}
            ContextGuard huge=new ContextGuard(8192,1024,true,70,1.25);try{huge.prepare(Provider.CUSTOM,server.url(),"","fixture","system",List.of(ChatClient.Message.user("huge ".repeat(10000))),List.of(),0,ui);throw new AssertionError("huge question allowed");}catch(IOException expected){check(server.requests.size()==requests,"oversized question is preserved and not sent for impossible compaction");}
            ContextGuard stopped=new ContextGuard(8192,1024,true,70,1.25);stopped.cancel();try{stopped.prepare(Provider.CUSTOM,server.url(),"","fixture","system",original,List.of(),1,ui);throw new AssertionError("cancel ignored");}catch(InterruptedIOException expected){check(server.requests.size()==requests,"cancellation prevents compaction and automatic send");}
        }
        try(Server server=new Server(null)){server.fail=true;ContextGuard guard=new ContextGuard(8192,1024,true,70,1.25);try{guard.prepare(Provider.CUSTOM,server.url(),"","fixture","system",original,List.of(),1,new Ui());throw new AssertionError("failure ignored");}catch(IOException expected){check(guard.memory==null&&server.normal.get()==0,"failed compaction does not apply memory or resume original request");}}
    }
    static void integration(Path home,boolean stop)throws Exception{
        Path local=Files.createDirectories(home.resolve(stop?"stop":"resume")),report=local.resolve("large-report.txt");String evidence="PDF evidence 507123 dimensions and checked quantities\n".repeat(900);Files.writeString(report,evidence);
        try(Server server=new Server(report)){
            if(stop)server.delay=500;LocalChat[] app={null};String question="Create visible PDF reports for project 507123 using the checked drawings.";
            FeatureTest.edt(()->{app[0]=AttachmentTest.window(local,server.url());WorkspacePanel workspace=FeatureTest.field(app[0],"workspace");workspace.limit.setValue(12000);if(!workspace.autoCompact.isSelected())workspace.autoCompact.doClick();((JTextArea)FeatureTest.field(app[0],"input")).setText(question);FeatureTest.call(app[0],"send",new Class<?>[]{});});
            if(stop){for(int n=0;n<300&&server.summaries.get()==0;n++)Thread.sleep(10);check(server.summaries.get()>0,"tool loop reaches automatic compaction");FeatureTest.edt(()->FeatureTest.command(app[0],"/stop"));Thread.sleep(600);}
            FeatureTest.idle(app[0]);FeatureTest.edt(()->{
                List<ChatClient.Message> archive=FeatureTest.field(app[0],"history");Conversations.State state=FeatureTest.field(app[0],"sessionState");
                check(archive.stream().anyMatch(m->m.role.equals("tool")&&m.content.length()>12000&&m.content.length()<=Tools.MAX_OUTPUT+400),"full tool evidence (up to the 1.27.2 per-result cap) remains in original chat archive");
                check(Files.readString(report).equals(evidence),"context management never re-executes writes or alters source file");
                if(stop){check(server.normal.get()==1&&state.memory.through==0,"Stop during summary prevents applying incomplete memory or resuming");}
                else{
                    check(server.normal.get()==2&&server.summaries.get()>0,"large tool result triggers summaries before one resumed model call");
                    check(state.memory.through>0&&state.memory.question.equals(question),"tool-loop compact memory persisted with exact user request");
                    Path file=FeatureTest.field(app[0],"conversationFile");var saved=Conversations.load(file);check(saved.messages().size()==archive.size()&&saved.state().memory.question.equals(question),"full archive and compact view survive save/reload");
                    check(saved.state().contextAnchorInput>=0 && saved.state().contextAnchorCount<archive.size(),"last reported API input anchor persists separately from the original archive");
                    check(state.usage.requests==server.requests.size(),"summary usage counted exactly once alongside normal model calls");
                    Properties config=FeatureTest.field(app[0],"config");String profile=(String)FeatureTest.call(app[0],"contextProfile",new Class<?>[]{});check(Double.parseDouble(config.getProperty(profile+".tokenScale"))>1.25,"API calibration saved for selected model profile");
                    check(state.memory.view(archive).stream().noneMatch(m->m.role.equals("tool")),"resumed view replaces completed tool pairs without orphaned messages");
                    int beforeReload=server.requests.size();LocalChat restored=AttachmentTest.window(local,server.url());
                    check((Double)FeatureTest.call(restored,"contextScale",new Class<?>[]{})>1.25,"model calibration survives application reload");
                    check(server.requests.size()==beforeReload,"restoring calibration makes no API requests");restored.dispose();
                }
                app[0].dispose();
            });
        }
    }
    public static void main(String[] args)throws Exception{Path home=Path.of(System.getProperty("localchat.home"));Files.createDirectories(home);try{policy();integration(home,false);integration(home,true);System.out.println("PASS: "+checks+" context guard checks; synthetic API, real local tools, preserved archive and cancellation");}finally{FeatureTest.edt(()->{for(var window:java.awt.Window.getWindows())window.dispose();});}System.exit(0);}
}
