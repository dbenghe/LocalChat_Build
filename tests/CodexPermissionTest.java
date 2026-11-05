package localchat;
import java.nio.file.*;import java.util.*;import java.util.concurrent.atomic.*;
public final class CodexPermissionTest {
 static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static Tools tools(Path root,boolean auto,AtomicInteger approvals){return new Tools(new Tools.Context(root,root.resolve("skills"),root.resolve("rules.md"),root.resolve("settings.properties"),Map.of(),null,new Mcp(),PathMap.none(),auto,EnumSet.noneOf(Tools.Access.class),(a,b)->{approvals.incrementAndGet();return Tools.Decision.YES;}),List.of());}
 static CodexConnection.NativeAccess access(Agent agent)throws Exception{var client=Agent.class.getDeclaredField("client");client.setAccessible(true);var state=ChatClient.class.getDeclaredField("nativeAccess");state.setAccessible(true);return (CodexConnection.NativeAccess)state.get(client.get(agent));}
 public static void main(String[] args)throws Exception{Path home=Files.createDirectories(Path.of(System.getProperty("localchat.home")));AtomicInteger approvals=new AtomicInteger();Tools ask=tools(home,false,approvals),auto=tools(home,true,approvals),read=tools(home,false,approvals).readOnly(true),child=tools(home,false,approvals).analysisOnly();
  check(ask.nativeAccess().writable(),"ask permits guarded tool requests");check(auto.nativeAccess().writable(),"auto permits guarded writes");check(!read.nativeAccess().writable(),"read-only never upgraded");check(!child.nativeAccess().writable(),"analysis never upgraded");check(!new CodexConnection.NativeAccess(null,true).writable(),"no workspace never grants write");
  check(access(new Agent(ask)).equals(ask.nativeAccess()),"main permission inherited");check(!access(new Agent(child)).writable(),"child restriction inherited");check(!access(new Agent(null)).writable(),"Normal no native write rights");
  var blocked=read.execute(new ChatClient.ToolCall("blocked","write_file",Json.obj("path","blocked.txt","content","no")));check(blocked.isError&&!Files.exists(home.resolve("blocked.txt")),"read-only blocks even explicit write request");check(approvals.get()==0,"readonly denial before approval");
  check(ask.permissionControl().contains("GUARDED_WRITE"),"actual guarded mode supplied to API");check(child.permissionControl().contains("READ_ONLY"),"actual worker mode supplied to API");
  ChatClient nativeClient=new ChatClient(()->{try{return CodexConnectionTest.peer("guarded");}catch(Exception e){throw new java.io.IOException(e);}});nativeClient.nativeAccess(ask.nativeAccess());try{var reply=nativeClient.chat(Provider.CODEX,CodexConnection.ROUTE,"","test-model","system",List.of(ChatClient.Message.user("authorised synthetic request")),List.of(),true,v->{});check(reply.text.equals("GUARDED_OK"),"native workspace-write and exact roots accepted");}finally{nativeClient.closeNative();}
  for(Provider provider:List.of(Provider.CUSTOM,Provider.OPENAI)){
   Path root=Files.createDirectories(home.resolve(provider.id()));Files.writeString(root.resolve("input.txt"),"API_WRITE_OK");AtomicInteger asked=new AtomicInteger();Tools actual=tools(root,false,asked);
   try(var server=new FeatureTest.Server(FeatureTest.json(PdfAgentTest.request("read","read_file",Json.obj("path","input.txt"))),FeatureTest.json(PdfAgentTest.request("write","write_file",Json.obj("path","Output/proof.txt","content","API_WRITE_OK"))),FeatureTest.json(PdfAgentTest.request("verify","read_file",Json.obj("path","Output/proof.txt"))),FeatureTest.json(FeatureTest.answer("API_WRITE_OK",20,3)))){
    Agent agent=new Agent(actual);agent.run(provider,server.url(),"synthetic-key","synthetic-model","proof","Work only in "+root,List.of(ChatClient.Message.user("Read input, write output and verify.")),false,PdfAgentTest.ui());
    check(Files.readString(root.resolve("Output/proof.txt")).equals("API_WRITE_OK"),"actual write via Agent "+provider);check(asked.get()==1,"guarded write approval "+provider);check(server.requests.size()==4,"read/write/verify/final "+provider);
    for(Object packet:server.requests){check(Json.get(packet,"sandbox")==null&&Json.get(packet,"runtimeWorkspaceRoots")==null,"Codex sandbox absent from direct API "+provider);check(Json.str(packet,"messages",0,"content").contains("GUARDED_WRITE"),"direct API receives current permission "+provider);}
   }
  }
  System.out.println("CodexPermissionTest: "+checks+" checks passed; native permissions, Normal/analysis isolation, actual direct-API Agent read/write");
 }
}
