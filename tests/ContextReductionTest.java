package localchat;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import javax.swing.*;

/**
 * 1.27.2 context under control: deterministic reduction of old tool results (request view only), archive intact,
 * anchors invalidated on reduction, lower per-result cap with pagination, compaction on by default for new chats only.
 * Synthetic data only; no inference, no user documents.
 */
public final class ContextReductionTest {
    static int checks;static void check(boolean ok,String m){checks++;if(!ok)throw new AssertionError(m);}
    static ChatClient.Message result(String id,String text){return ChatClient.Message.toolResult(id,text,false);}
    static ChatClient.Message call(String id,String name,Map<String,Object> args){return ChatClient.Message.assistant("",List.of(new ChatClient.ToolCall(id,name,args)),"fixture");}
    static String report(int n){
        List<Object> artifacts=new ArrayList<>();for(int i=0;i<20;i++)artifacts.add(Json.obj("path","C:\\proj\\507123\\reports\\report-"+n+"-"+i+".pdf","pages",12+i,"sha256","ab12cd34ef56".repeat(5)));
        return Json.write(Json.obj("job_id","job-"+String.format("%04d",n),"status","completed","project","507123","delivery_verified",true,"report","C:\\proj\\507123\\reports\\report-"+n+".pdf","artifacts",artifacts,"text","lorem ipsum dolor ".repeat(1200)));
    }
    static String fileRead(int n){StringBuilder b=new StringBuilder("Fisier: C:\\proj\\507123\\drawings\\drawing-"+n+".txt (900 randuri)\n");for(int i=1;i<=300;i++)b.append(String.format("%5d| %s%n",i,"detaliu desen "+n+" linia "+i+" ".repeat(10)+"x".repeat(30)));return b.append(". mai sunt 600 randuri; continua cu offset=301\n").toString();}
    /** A long synthetic tool loop: one question, then many call/result pairs. */
    static List<ChatClient.Message> loop(int pairs){
        List<ChatClient.Message> out=new ArrayList<>();out.add(ChatClient.Message.user("Verify project 507123 and create the visible reports."));
        for(int i=0;i<pairs;i++){
            boolean json=i%2==0;String id="c"+i;
            out.add(call(id,json?"pdfcontrol_job_status":"read_file",json?Map.of("project","507123","job_id","job-"+String.format("%04d",i)):Map.of("path","C:\\proj\\507123\\drawings\\drawing-"+i+".txt")));
            out.add(result(id,json?report(i):fileRead(i)));
            if(i%5==4)out.add(call("s"+i,"list_dir",Map.of("path",".")));if(i%5==4)out.add(result("s"+i,"Folder: .\n(gol)\n"));
        }
        out.add(ChatClient.Message.assistant("Done.",List.of(),"fixture"));
        return out;
    }
    static int tools(List<ChatClient.Message> l){int n=0;for(var m:l)if(m.role.equals("tool"))n++;return n;}
    @SuppressWarnings("unchecked") static <T> T field(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return (T)f.get(o);}

    public static void main(String[] args)throws Exception{
        reducer();pagination();anchors();defaults();
        System.out.println("ContextReductionTest ok checks="+checks);
    }

    static void reducer(){
        List<ChatClient.Message> history=loop(40);
        List<String> before=new ArrayList<>();for(var m:history)before.add(Json.write(m.toJson()));
        var first=ToolResultReducer.apply(history);var second=ToolResultReducer.apply(history);
        check(first.messages().size()==history.size(),"message count is unchanged, tool-call pairs stay valid");
        check(first.count()>=30&&first.count()==second.count(),"old results are reduced, deterministically; count="+first.count());
        for(int i=0;i<history.size();i++)check(Json.write(second.messages().get(i).toJson()).equals(Json.write(first.messages().get(i).toJson())),"identical output on every call at index "+i);
        check(first.messages().get(0)==history.get(0)&&first.messages().get(0).content.equals("Verify project 507123 and create the visible reports."),"the exact question is kept as the same message");
        for(int i=0;i<history.size();i++)check(Json.write(history.get(i).toJson()).equals(before.get(i)),"the input (archive) is never mutated at index "+i);
        // The most recent results stay intact.
        int seen=0;for(int i=history.size()-1;i>=0;i--)if(history.get(i).role.equals("tool")&&++seen<=ToolResultReducer.KEEP_RECENT)check(first.messages().get(i)==history.get(i),"recent result intact at index "+i);
        // Pairs: every tool message still answers an existing call id.
        Set<String> ids=new HashSet<>();for(var m:first.messages())for(var c:m.toolCalls)ids.add(c.id);
        for(var m:first.messages())if(m.role.equals("tool"))check(ids.contains(m.toolCallId),"tool result keeps its call id "+m.toolCallId);
        // Stubs keep identifiers, paths, status and delivery_verified, and say how to re-read.
        var oldJson=first.messages().get(2);var oldFile=first.messages().get(4);
        check(oldJson.content.startsWith(ToolResultReducer.MARK)&&oldJson.content.contains("pdfcontrol_job_status")&&oldJson.content.contains("job-0000")&&oldJson.content.contains("delivery_verified=true")&&oldJson.content.contains("status=completed")&&oldJson.content.contains("report-0.pdf")&&oldJson.content.contains("project=507123"),"JSON stub keeps tool, job_id, status, delivery_verified and report path: "+oldJson.content);
        check(!oldJson.content.contains("lorem ipsum"),"bulk text is dropped from the stub");
        check(oldFile.content.contains("read_file")&&oldFile.content.contains("drawing-1.txt")&&oldFile.content.contains("continua cu offset=301")&&oldFile.content.contains("call the tool again"),"text stub keeps the path and the continuation hint: "+oldFile.content);
        for(int i=0;i<history.size();i++)if(first.messages().get(i)!=history.get(i))check(first.messages().get(i).content.length()<=ToolResultReducer.STUB_LIMIT+400,"stub stays small at index "+i);
        // Short results are never touched; the reduction is idempotent on its own output.
        check(first.messages().stream().filter(m->m.role.equals("tool")&&m.content.startsWith("Folder: .")).count()==8,"short results are kept verbatim");
        check(ToolResultReducer.apply(first.messages()).count()==0,"already reduced results are not reduced again");
        // Few results: nothing to reduce.
        check(ToolResultReducer.apply(loop(ToolResultReducer.KEEP_RECENT-2)).count()==0,"a short loop is untouched");
        // Numbers on the long synthetic loop.
        long rawTokens=ContextMemory.messages(history),reducedTokens=ContextMemory.messages(first.messages());
        System.out.println("long synthetic tool loop: pairs=40 tool results="+tools(history)+" reduced="+first.count()+" estimated tokens before="+rawTokens+" after="+reducedTokens+" ("+(100-reducedTokens*100/rawTokens)+"% less)");
        check(reducedTokens*100<rawTokens*35,"the request view is at least 65% smaller on a long loop");
    }

    static void pagination()throws Exception{
        // cap with a continuation hint
        String big="y".repeat(100_000),capped=Tools.cap(big,Tools.MAX_OUTPUT);
        check(Tools.MAX_OUTPUT==16_000&&capped.length()<Tools.MAX_OUTPUT+200&&capped.contains("84000 caractere omise")&&capped.contains("offset/limit"),"cap keeps head and tail with an explicit hint, default 16000 instead of 60000");
        check(Tools.cap("short",Tools.MAX_OUTPUT).equals("short"),"short results are untouched");
        String page=" --- pagina ";String pdf="[5 pagini; afisate 1-5]\n"+"--- pagina 1 ---\n"+"a".repeat(7000)+"\n--- pagina 2 ---\n"+"b".repeat(7000)+"\n--- pagina 3 ---\n"+"c".repeat(7000)+"\n";
        String cut=Tools.pdfPage(pdf,1);check(cut.contains("aaaa")&&cut.contains("bbbb")&&!cut.contains("cccc")&&cut.contains("offset=3"),"PDF reads end at a page boundary with the next page offset");
        check(Tools.pdfPage("[1 pagini; afisate 1-1]\n--- pagina 1 ---\n"+"z".repeat(40_000),1).length()<Tools.MAX_OUTPUT+200,"one oversized page falls back to head and tail");
        Path dir=Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("localchat.home","."))),"reduction-");
        Path files=Files.createDirectories(dir.resolve("many"));for(int i=0;i<400;i++)Files.writeString(files.resolve(String.format("file-%04d.txt",i)),"line one\nneedle "+i+"\n");
        StringBuilder lines=new StringBuilder();for(int i=1;i<=500;i++)lines.append("row ").append(i).append(' ').append("w".repeat(150)).append('\n');Files.writeString(dir.resolve("long.txt"),lines.toString());
        Mcp mcp=new Mcp();Tools tools=new Tools(new Tools.Context(dir,dir.resolve("skills"),dir.resolve("rules.md"),dir.resolve("settings.properties"),Map.of(),null,mcp,PathMap.none(),true,EnumSet.allOf(Tools.Access.class),(a,b)->Tools.Decision.NO),List.of());
        // list_dir and find_files: follow the hint until everything was seen exactly once
        for(String tool:List.of("list_dir","find_files")){
            Set<String> seen=new LinkedHashSet<>();int offset=1,pages=0,names=0;
            while(true){
                Map<String,Object> a=tool.equals("list_dir")?Json.obj("path",files.toString(),"offset",offset):Json.obj("pattern","*.txt","path",files.toString(),"offset",offset);
                var r=tools.execute(new ChatClient.ToolCall("p",tool,a));check(!r.isError,tool+" offset "+offset+": "+r.text);check(r.text.length()<=Tools.MAX_OUTPUT,tool+" page within the cap");
                Matcher m=Pattern.compile("file-(\\d{4})\\.txt").matcher(r.text);while(m.find()){check(seen.add(m.group(1)),tool+" no duplicate entry "+m.group(1));names++;}
                Matcher next=Pattern.compile("continua cu offset=(\\d+)").matcher(r.text);pages++;if(!next.find())break;offset=Integer.parseInt(next.group(1));check(pages<10,tool+" terminates");
            }
            check(seen.size()==400&&pages==3,tool+" pages through all 400 entries in 3 pages: "+seen.size()+" pages="+pages);
        }
        // search_text
        Set<String> hitIds=new LinkedHashSet<>();int offset=1,pages=0;
        while(true){
            var r=tools.execute(new ChatClient.ToolCall("s","search_text",Json.obj("query","needle","path",files.toString(),"offset",offset)));check(!r.isError&&r.text.length()<=Tools.MAX_OUTPUT,"search page within the cap");
            Matcher m=Pattern.compile("file-(\\d{4})\\.txt:2: needle").matcher(r.text);int found=0;while(m.find()){check(hitIds.add(m.group(1)),"no duplicate hit "+m.group(1));found++;}
            check(found<=Tools.MAX_HITS,"at most MAX_HITS hits per page");
            Matcher next=Pattern.compile("continua cu offset=(\\d+)").matcher(r.text);pages++;if(!next.find())break;offset=Integer.parseInt(next.group(1));check(pages<10,"search terminates");
        }
        check(hitIds.size()==400&&pages==4,"search_text pages through all 400 hits in 4 pages: "+hitIds.size()+" pages="+pages);
        // read_file: lines are covered once, in order, each page within the cap
        int expected=1;pages=0;offset=1;
        while(true){
            var r=tools.execute(new ChatClient.ToolCall("f","read_file",Json.obj("path",dir.resolve("long.txt").toString(),"offset",offset)));check(!r.isError&&r.text.length()<=Tools.MAX_OUTPUT+400,"read page within the cap");
            Matcher m=Pattern.compile("(?m)^\\s*(\\d+)\\| row (\\d+) ").matcher(r.text);while(m.find()){check(Integer.parseInt(m.group(1))==expected&&Integer.parseInt(m.group(2))==expected,"line "+expected+" in order");expected++;}
            Matcher next=Pattern.compile("continua cu offset=(\\d+)").matcher(r.text);pages++;if(!next.find())break;offset=Integer.parseInt(next.group(1));check(offset==expected,"continuation offset points to the next unread line");check(pages<20,"read terminates");
        }
        check(expected==501&&pages>=4,"read_file continues until every line was read once; pages="+pages);
        // The tool schemas advertise the new parameter.
        for(String tool:List.of("list_dir","find_files","search_text"))check(tools.specs().stream().filter(s->s.name.equals(tool)).anyMatch(s->Json.write(s.schema).contains("offset")),tool+" schema has offset");
    }

    static void anchors(){
        List<ChatClient.ToolSpec> none=List.of();
        check(ContextGuard.signature("sys","ctx",none).equals(ContextGuard.signature("sys","ctx",none,0)),"without reduction the signature equals the 1.27.1 one (existing anchors stay valid)");
        check(!ContextGuard.signature("sys","ctx",none,0).equals(ContextGuard.signature("sys","ctx",none,3)),"a reduction invalidates the anchor signature");
        check(!ContextGuard.signature("sys","ctx",none,3).equals(ContextGuard.signature("sys","ctx",none,4)),"a further reduction invalidates it again");
        check(ContextGuard.signature("sys","ctx",none,3).equals(ContextGuard.signature("sys","ctx",none,3)),"the same reduction keeps it");
        // The reduction count follows the history: new results age out old ones and change the key.
        List<ChatClient.Message> history=loop(12);int a=ToolResultReducer.apply(history).count();
        List<ChatClient.Message> longer=new ArrayList<>(history);longer.addAll(loop(4).subList(1,loop(4).size()));int b=ToolResultReducer.apply(longer).count();
        check(a>0&&b>a,"more results change the reduction key; a="+a+" b="+b);
        List<ChatClient.Message> chatOnly=new ArrayList<>(history);chatOnly.add(ChatClient.Message.user("thanks"));chatOnly.add(ChatClient.Message.assistant("ok",List.of(),"fixture"));
        check(ToolResultReducer.apply(chatOnly).count()==a,"a chat-only turn keeps the key, so the anchor stays valid");
        // The guard records the key in its signature and in the checkpoint identity.
        ContextGuard guard=new ContextGuard(100_000,1_000,false,70,1.25);guard.reduction=5;
        try{guard.prepare(null,"","","","sys",history,none,0,new ContextGuardTest.Ui());}catch(Exception e){throw new AssertionError(e);}
        check(guard.signature.equals(ContextGuard.signature("sys","",none,5))&&!guard.signature.equals(ContextGuard.signature("sys","",none,0)),"prepare signs the request with the reduction key");
        // The guard sees the reduced base unchanged: compaction indexes stay valid because the length never changes.
        check(ToolResultReducer.apply(history).messages().size()==history.size(),"compaction indexes stay valid");
    }

    static void defaults()throws Exception{
        Map<String,Object> legacy=Json.obj("autoThreshold",70);
        check(!Conversations.State.fromJson(legacy,0).autoCompact,"an existing chat without a stored choice keeps compaction off");
        check(Conversations.State.fromJson(Json.obj("autoCompact",true),0).autoCompact&&!Conversations.State.fromJson(Json.obj("autoCompact",false),0).autoCompact,"explicit per-chat choices are preserved");
        for(String language:List.of("nl","ro","es","pl","en","tr")){
            String text=new String(ContextReductionTest.class.getResourceAsStream("/localchat/i18n/"+language+".json").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            for(String key:List.of("Reduce rezultatele vechi ale uneltelor in cerere (arhiva ramane intreaga)","Contextul estimat este la ~{0}% din limita."))check(text.contains(key),language+" has the new UI text: "+key);
        }
        // The window: new chats start with compaction on; the toggle for the reduction exists and old results are reduced without touching the archive.
        LocalChat[] app={null};
        SwingUtilities.invokeAndWait(()->{try{
            Constructor<LocalChat> c=LocalChat.class.getDeclaredConstructor();c.setAccessible(true);app[0]=c.newInstance();
            WorkspacePanel panel=field(app[0],"workspace");
            check(panel.autoCompact.isSelected()&&((Conversations.State)field(app[0],"sessionState")).autoCompact,"new chat: auto compaction on by default");
            List<ChatClient.Message> history=field(app[0],"history");history.addAll(loop(30));
            List<String> before=new ArrayList<>();for(var m:history)before.add(Json.write(m.toJson()));
            Method requestHistory=LocalChat.class.getDeclaredMethod("requestHistory",List.class);requestHistory.setAccessible(true);
            ToolResultReducer.Reduced reduced=(ToolResultReducer.Reduced)requestHistory.invoke(app[0],new ArrayList<>(history));
            check(reduced.count()>=20&&ContextMemory.messages(reduced.messages())<ContextMemory.messages(history)/2,"the request view is reduced in the application");
            for(int i=0;i<history.size();i++)check(Json.write(history.get(i).toJson()).equals(before.get(i)),"the archive (history) is untouched at index "+i);
            Properties config=field(app[0],"config");config.setProperty("context.reduceOld","false");
            check(((ToolResultReducer.Reduced)requestHistory.invoke(app[0],new ArrayList<>(history))).count()==0,"the reduction can be turned off");
            config.setProperty("context.reduceOld","true");
            app[0].dispose();
        }catch(Exception e){throw new RuntimeException(e);}});
    }
}