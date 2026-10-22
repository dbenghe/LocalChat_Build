package localchat;
import java.nio.file.*;import java.util.*;import java.util.concurrent.atomic.AtomicInteger;
public final class InteractionRecoveryTest {
 static boolean streaming;static int checks;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static String call(String id,String name,Object args){return Json.write(Json.obj("id",id,"type","function","function",Json.obj("name",name,"arguments",Json.write(args))));}
 static FeatureTest.Response reply(String text,String... calls){
  var nativeCalls=Arrays.stream(calls).map(Json::parse).toList();
  if(!streaming)return FeatureTest.json(Json.write(Json.obj("choices",List.of(Json.obj("message",Json.obj("content",text,"tool_calls",nativeCalls))))));
  var deltas=new ArrayList<Object>();for(int i=0;i<nativeCalls.size();i++){var c=nativeCalls.get(i);deltas.add(Json.obj("index",i,"id",Json.str(c,"id"),"type","function","function",Json.get(c,"function")));}
  return FeatureTest.sse(FeatureTest.event(Json.obj("choices",List.of(Json.obj("delta",Json.obj("content",text,"tool_calls",deltas)))))+"data: [DONE]\n\n");
 }
 static Object bad(){return Json.obj("message","Findings","questions","[{\"id\":\"q\",\"options\":[\"Yes\",\"question\":\"Broken\"}]","suggestions",List.of());}
 static Agent.Ui ui(AtomicInteger operations){return new Agent.Ui(){public void text(String text){}public void step(){}public void toolStarted(ChatClient.ToolCall c){if(!c.name.equals(ComposerAssist.TOOL))operations.incrementAndGet();}public void toolFinished(ChatClient.ToolCall c,Tools.Result r){}};}
 static List<?> tools(Object request){Object t=Json.get(request,"tools");return t instanceof List<?> list?list:List.of();}
 public static void main(String[] args)throws Exception{
  Path root=Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("localchat.home"))),"run-");
  for(boolean stream:List.of(false,true)){
   streaming=stream; // Exercise both native SSE tool deltas and regular JSON replies.
   try(var server=new FeatureTest.Server(reply("Findings kept",call("bad",ComposerAssist.TOOL,bad()),call("sibling","write_file",Json.obj("path","never.txt","content","NO"))),reply("Clarification",call("good",ComposerAssist.TOOL,ComposerAssistTest.questions())))){
    Agent a=new Agent(ToolProtocolTest.tools(root)).interactions(true);AtomicInteger ops=new AtomicInteger();a.run(Provider.CUSTOM,server.url(),"","fixture","fixture","system",List.of(ChatClient.Message.user("check")),stream,ui(ops));
    var history=a.collect("","fixture");check(server.requests.size()==2,"invalid card repaired with one request");check(tools(server.requests.get(1)).size()==1&&Json.write(tools(server.requests.get(1))).contains(ComposerAssist.TOOL),"repair exposes only interaction tool");check(ops.get()==0&&!Files.exists(root.resolve("never.txt")),"sibling mutation never executed");
    check(history.stream().anyMatch(m->"good".equals(m.toolCallId)&&!m.isError&&m.content.contains("waiting_user")),"valid repaired card confirmed");check(Json.write(server.requests.get(1)).contains("sibling"),"skipped sibling result present before retry");check(history.stream().anyMatch(m->m.content.equals("Findings kept")),"original findings preserved");
   }
   try(var server=new FeatureTest.Server(reply("Partial findings",call("bad1",ComposerAssist.TOOL,bad())),reply("",call("bad2",ComposerAssist.TOOL,bad())),FeatureTest.json(FeatureTest.answer("Final findings; pending approval.",7,8)))){
    Agent a=new Agent(ToolProtocolTest.tools(root)).interactions(true);a.run(Provider.CUSTOM,server.url(),"","fixture","fixture","system",List.of(ChatClient.Message.user("check")),stream,ui(new AtomicInteger()));check(server.requests.size()==3&&tools(server.requests.get(2)).isEmpty(),"second invalid card falls back to text without tools");check(a.collect("","fixture").stream().anyMatch(m->m.content.equals("Final findings; pending approval.")),"visible final retained without fatal exception");
   }
  }
  streaming=false;
  try(var server=new FeatureTest.Server(reply("",call("bad",ComposerAssist.TOOL,bad())),reply("",call("unexpected","write_file",Json.obj("path","never.txt","content","NO"))),FeatureTest.json(FeatureTest.answer("Pending clarification",1,1)))){
   Agent a=new Agent(ToolProtocolTest.tools(root)).interactions(true);a.run(Provider.CUSTOM,server.url(),"","fixture","fixture","system",List.of(ChatClient.Message.user("check")),false,ui(new AtomicInteger()));check(!Files.exists(root.resolve("never.txt"))&&server.requests.size()==3,"hallucinated operational tool rejected during repair");
  }
  try(var server=new FeatureTest.Server(reply("",call("bad",ComposerAssist.TOOL,bad())))){
   Agent a=new Agent(ToolProtocolTest.tools(root)).interactions(true);a.run(Provider.CUSTOM,server.url(),"","fixture","fixture","system",List.of(ChatClient.Message.user("check")),false,new Agent.Ui(){public void text(String t){}public void step(){}public void toolStarted(ChatClient.ToolCall c){}public void toolFinished(ChatClient.ToolCall c,Tools.Result r){a.cancel();}});check(server.requests.size()==1,"Stop prevents repair request");
  }
  try(var server=new FeatureTest.Server(reply("",call("write","write_file",Json.obj("path","once.txt","content","confirmed"))),reply("Findings",call("bad",ComposerAssist.TOOL,bad())),FeatureTest.json(FeatureTest.answer("Final pending approval",1,1)))){
   Agent a=new Agent(ToolProtocolTest.tools(root)).interactions(true);AtomicInteger ops=new AtomicInteger();a.run(Provider.CUSTOM,server.url(),"","fixture","fixture","system",List.of(ChatClient.Message.user("check")),false,ui(ops));check(ops.get()==1&&Files.readString(root.resolve("once.txt")).equals("confirmed"),"confirmed write executed exactly once before recovery");check(Json.write(server.requests.get(2)).contains("confirmed"),"recovery retains prior confirmed tool results");
  }
  System.out.println("PASS: "+checks+" interaction recovery checks");System.exit(0);
 }
}
