package localchat;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Deterministic JSONL peer; no real account, inference or production documents. */
public final class CodexConnectionTest {
 static int checks;
 static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static CodexConnection peer(String mode)throws Exception{return new CodexConnection(List.of(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-cp",System.getProperty("java.class.path"),CodexConnectionTest.class.getName(),"--fixture",mode));}
 public static void main(String[] args)throws Exception{
  if(args.length>0&&args[0].equals("--fixture")){fixture(args[1],args);return;}
  Map<String,String> env=new HashMap<>(Map.of("CODEX_APP_TOOLS_PIPE_PATH","pipe","CODEX_INTERNAL_ORIGINATOR_OVERRIDE","codex_vscode","CODEX_HOME","home","CODEX_SANDBOX_NETWORK_DISABLED","1","OPENAI_API_KEY","key","OPENAI_BASE_URL","url","PATH","path"));CodexConnection.sanitize(env);check(env.equals(Map.of("CODEX_HOME","home","CODEX_SANDBOX_NETWORK_DISABLED","1","PATH","path")),"private parent pipes and API fallback removed");
  try(var c=peer("normal")){c.requireChatGpt();var rows=c.catalog();check(rows.size()==1,"catalog count");check(rows.get(0).id().equals("test-model"),"model id");check(rows.get(0).vision(),"image modality");check(rows.get(0).maximum()==0,"no invented context");var reply=c.chat("test-model","plain system",List.of(ChatClient.Message.user("hello")),List.of(),v->{},u->{},()->5000);check(reply.text.equals("NORMAL_OK"),"normal text");check(reply.calls.isEmpty(),"normal no tools");}
  try(var c=peer("apikey")){try{c.requireChatGpt();throw new AssertionError("API fallback accepted");}catch(IOException expected){check(expected.getMessage().contains("ChatGPT"),"API-key mode refused");}}
  var tools=List.of(new ChatClient.ToolSpec("probe","harmless",Json.obj("type","object","properties",Json.obj("value",Json.obj("type","string")))));
  for(String mode:List.of("tools","foreign","queued"))try(var c=peer(mode)){
   List<ChatClient.Message> history=new ArrayList<>(List.of(ChatClient.Message.user("test")));List<TokenUsage> usage=new ArrayList<>();StringBuilder visible=new StringBuilder();
   var first=c.chat("test-model","system",history,tools,visible::append,usage::add,()->5000);check(first.calls.size()==1,"one call "+mode);check(first.calls.get(0).id.equals("call1"),"stable id "+mode);check(first.text.equals("BEFORE"),"partial text "+mode);
   history.add(ChatClient.Message.assistant(first.text,first.calls,"test"));history.add(ChatClient.Message.toolResult("call1","RESULT_ONE",false));
   var second=c.chat("test-model","system",history,tools,visible::append,usage::add,()->5000);
   if(mode.equals("queued")){check(second.calls.size()==1&&second.calls.get(0).id.equals("call2"),"queued sibling retained");history.add(ChatClient.Message.assistant(second.text,second.calls,"test"));history.add(ChatClient.Message.toolResult("call2","RESULT_TWO",true));second=c.chat("test-model","system",history,tools,visible::append,usage::add,()->5000);}
   check(second.text.equals("AFTER"),"resume same turn "+mode);check(second.calls.isEmpty(),"final reply "+mode);check(visible.toString().equals("BEFOREAFTER"),"no duplicate stream "+mode);check(usage.stream().filter(TokenUsage::complete).mapToLong(TokenUsage::input).sum()==40,"input counted once "+mode);check(usage.stream().filter(TokenUsage::complete).mapToLong(TokenUsage::output).sum()==8,"output counted once "+mode);
  }
  for(String mode:List.of("wrongmodel","wrongroute","failure","eof","duplicate"))try(var c=peer(mode)){
   List<ChatClient.Message> history=new ArrayList<>(List.of(ChatClient.Message.user("test")));try{c.chat("test-model","system",history,tools,v->{},u->{},()->2000);history.add(ChatClient.Message.toolResult("call1","RESULT_ONE",false));c.chat("test-model","system",history,tools,v->{},u->{},()->2000);throw new AssertionError("accepted "+mode);}catch(IOException expected){check(true,"bounded error "+mode);}
  }
  for(String waiting:List.of("wait","waitinit"))try(var c=peer(waiting)){var future=CompletableFuture.runAsync(()->{try{c.chat("test-model","system",List.of(ChatClient.Message.user("test")),List.of(),v->{},u->{},()->5000);throw new AssertionError("cancel accepted");}catch(IOException expected){}});Thread.sleep(350);c.close();future.get(3,TimeUnit.SECONDS);check(true,"cancel releases reader");}
  try(var c=peer("wait")){long start=System.nanoTime();try{c.chat("test-model","system",List.of(ChatClient.Message.user("test")),List.of(),v->{},u->{},()->100);throw new AssertionError("timeout ignored");}catch(java.net.SocketTimeoutException expected){check(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-start)<4,"bounded deadline");}}
  for(String presentation:List.of("fullmessage","streamedcompleted"))try(var c=peer(presentation)){StringBuilder stream=new StringBuilder();var reply=c.chat("test-model","system",List.of(ChatClient.Message.user("test")),List.of(),stream::append,u->{},()->5000);check(reply.text.equals("FULL"),"full message fallback "+presentation);check(stream.toString().equals("FULL"),"no full-message duplication "+presentation);}
  ui();
  java.util.concurrent.atomic.AtomicInteger created=new java.util.concurrent.atomic.AtomicInteger();
  ChatClient reused=new ChatClient(()->{created.incrementAndGet();try{return peer("normal");}catch(Exception e){throw new IOException(e);}});
  for(int i=0;i<3;i++)check(reused.chat(Provider.CODEX,CodexConnection.ROUTE,"ignored","test-model","system",List.of(ChatClient.Message.user("turn "+i)),List.of(),true,v->{}).text.equals("NORMAL_OK"),"fresh thread for next completed call");
  check(created.get()==3,"completed clients release native connections");
  java.util.concurrent.atomic.AtomicInteger changes=new java.util.concurrent.atomic.AtomicInteger();
  ChatClient restricted=new ChatClient(()->{try{return peer(changes.getAndIncrement()==0?"tools":"normal");}catch(Exception e){throw new IOException(e);}});
  var one=restricted.chat(Provider.CODEX,CodexConnection.ROUTE,"","test-model","system",List.of(ChatClient.Message.user("test")),tools,true,v->{});
  check(one.calls.size()==1,"permission scenario begins with tool");
  var finalReply=restricted.chat(Provider.CODEX,CodexConnection.ROUTE,"","test-model","system",List.of(ChatClient.Message.user("test"),ChatClient.Message.toolResult("call1","RESULT_ONE",false)),List.of(),true,v->{});
  check(finalReply.text.equals("NORMAL_OK")&&changes.get()==2,"removing tools invalidates native thread");
  var input=CodexConnection.input(List.of(ChatClient.Message.user("old"),ChatClient.Message.toolResult("historical","data",false),ChatClient.Message.user("now").withRequestControl("Dutch")));check(Json.write(input).contains("historical"),"historical tool evidence");check(Json.write(input).contains("Dutch"),"language control");
  for(String lang:I18n.CODES)for(String key:List.of("Foloseste sesiunea Codex","Conectarea Codex a esuat: {0}","Codex conectat. Autentificarea ramane gestionata de Codex."))check(I18n.keys(lang).contains(key),"translation "+lang);
  System.out.println("CodexConnectionTest: "+checks+" checks passed");
 }
 static void ui()throws Exception{
  java.util.concurrent.atomic.AtomicReference<Throwable> error=new java.util.concurrent.atomic.AtomicReference<>();
  javax.swing.SwingUtilities.invokeAndWait(()->{LocalChat app=null;try{var ctor=LocalChat.class.getDeclaredConstructor();ctor.setAccessible(true);app=ctor.newInstance();var show=LocalChat.class.getDeclaredMethod("showProvider",Object.class);show.setAccessible(true);show.invoke(app,Provider.CODEX);
   var login=LocalChat.class.getDeclaredField("chatGptLogin");login.setAccessible(true);var key=LocalChat.class.getDeclaredField("keyField");key.setAccessible(true);var url=LocalChat.class.getDeclaredField("urlField");url.setAccessible(true);var logout=LocalChat.class.getDeclaredField("chatGptLogout");logout.setAccessible(true);
   check(((javax.swing.JButton)login.get(app)).isVisible(),"native connect visible");check(!((javax.swing.JButton)logout.get(app)).isVisible(),"no global Codex logout");check(!((javax.swing.JPasswordField)key.get(app)).isVisible(),"native API key hidden");check(!((javax.swing.JTextField)url.get(app)).isEditable(),"native route not editable");
   for(String code:I18n.CODES){I18n.setLanguage(code);var translate=LocalChat.class.getDeclaredMethod("applyLanguage");translate.setAccessible(true);translate.invoke(app);check(((javax.swing.JButton)login.get(app)).getText().equals(I18n.text("Foloseste sesiunea Codex")),"native button language "+code);}
   show.invoke(app,Provider.CUSTOM);check(((javax.swing.JPasswordField)key.get(app)).isVisible(),"custom API unchanged");
  }catch(Throwable failure){error.set(failure);}finally{if(app!=null)app.dispose();}});if(error.get()!=null)throw new Exception(error.get());I18n.setLanguage("ro");
 }
 static void emit(Object v){System.out.println(Json.write(v));System.out.flush();}
 static void result(Object id,Object v){emit(Json.obj("id",id,"result",v));}
 static void event(String method,Object v){emit(Json.obj("method",method,"params",v));}
 static void delta(String v){event("item/agentMessage/delta",Json.obj("threadId","thread1","delta",v));}
 static void usage(int in,int out){event("thread/tokenUsage/updated",Json.obj("threadId","thread1","tokenUsage",Json.obj("total",Json.obj("inputTokens",in,"outputTokens",out))));}
 static void done(String status){event("turn/completed",Json.obj("threadId","thread1","turn",Json.obj("status",status,"error",status.equals("completed")?null:Json.obj("message","fixture failure"))));}
 static void call(long id,String name,String ns){emit(Json.obj("id",id,"method","item/tool/call","params",Json.obj("threadId","thread1","turnId","turn1","callId",name,"tool","probe","namespace",ns,"arguments",Json.obj("value","OK"))));}
 static void fixture(String mode,String[] args)throws Exception{
  String command=String.join(" ",args);if(!command.contains("agents.enabled=false")||!command.contains("shell_tool=false")||!command.contains("hooks=false")||!command.contains("features.code_mode_host=true")||command.contains("code_mode_host=false"))throw new AssertionError("unsafe process");
  try(var reader=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null){Object req=Json.parse(line),id=Json.get(req,"id"),params=Json.get(req,"params");String method=Json.str(req,"method");
   if(method==null){if(Objects.equals(id,900L)){if(Json.get(req,"error")!=null)continue;if(!"RESULT_ONE".equals(Json.str(req,"result","contentItems",0,"text")))throw new AssertionError("wrong result");if(mode.equals("duplicate")){call(902,"call1","localchat");continue;}if(mode.equals("queued"))continue;usage(40,8);delta("AFTER");done("completed");}if(Objects.equals(id,901L)){if(!Boolean.FALSE.equals(Json.get(req,"result","success")))throw new AssertionError("error flag lost");usage(40,8);delta("AFTER");done("completed");}continue;}
   switch(method){
    case "initialize"->{if(!mode.equals("waitinit"))result(id,Json.obj("userAgent","fixture"));}case "initialized"->{}
    case "config/read"->result(id,Json.obj("config",Json.obj("mcp_servers",Json.obj("external",Json.obj("enabled",true)))));
    case "account/read"->result(id,Json.obj("account",Json.obj("type",mode.equals("apikey")?"apiKey":"chatgpt")));
    case "model/list"->result(id,Json.obj("data",List.of(Json.obj("id","test-model","inputModalities",List.of("text","image"),"supportedReasoningEfforts",List.of("low"))),"nextCursor",null));
    case "thread/start"->{if(!Boolean.FALSE.equals(Json.get(params,"config","mcp_servers","external","enabled")))throw new AssertionError("inherited MCP enabled");if(!(mode.equals("guarded")?"workspace-write":"read-only").equals(Json.str(params,"sandbox"))||!"never".equals(Json.str(params,"approvalPolicy"))||!Boolean.TRUE.equals(Json.get(params,"ephemeral"))||!Boolean.FALSE.equals(Json.get(params,"allowProviderModelFallback")))throw new AssertionError("unsafe thread");if(mode.equals("guarded")){Object roots=Json.get(params,"runtimeWorkspaceRoots");if(!(roots instanceof List<?> list)||list.size()!=1||!roots.equals(Json.get(params,"config","sandbox_workspace_write","writable_roots"))||!Boolean.FALSE.equals(Json.get(params,"config","sandbox_workspace_write","network_access")))throw new AssertionError("guarded roots mismatch");}if(mode.equals("normal")&&!((List<?>)Json.get(params,"dynamicTools")).isEmpty())throw new AssertionError("Normal leaked tools");result(id,Json.obj("thread",Json.obj("id","thread1"),"model",mode.equals("wrongmodel")?"other":"test-model","modelProvider",mode.equals("wrongroute")?"other":"openai"));}
    case "turn/start"->{result(id,Json.obj("turn",Json.obj("id","turn1")));if(mode.equals("wait"))continue;if(mode.equals("fullmessage")||mode.equals("streamedcompleted")){if(mode.equals("streamedcompleted"))event("item/agentMessage/delta",Json.obj("threadId","thread1","itemId","full","delta","FULL"));event("item/completed",Json.obj("threadId","thread1","item",Json.obj("type","agentMessage","id","full","text","FULL")));done("completed");continue;}if(mode.equals("eof"))return;if(mode.equals("failure")){done("failed");continue;}if(mode.equals("guarded")){Object roots=Json.get(params,"input");delta("GUARDED_OK");done("completed");continue;}if(mode.equals("normal")){delta("NORMAL_OK");done("completed");continue;}usage(10,2);delta("BEFORE");if(mode.equals("foreign"))call(899,"foreign","other");call(900,"call1","localchat");if(mode.equals("queued"))call(901,"call2","localchat");}
    default->emit(Json.obj("id",id,"error",Json.obj("message","unexpected method")));
   }
  }}
 }
}
