package localchat;
import java.io.*;import java.util.*;
/** Synthetic delayed API, no external network. */
public final class CompactionBudgetTest {
 static int checks;static void check(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
 public static void main(String[] args)throws Exception{
  String memory="Project 123456: verified source retained; no new actions.";
  try(var server=new FeatureTest.Server(FeatureTest.json(FeatureTest.answer(memory,20,10)))){
   server.replies.add(FeatureTest.json(FeatureTest.answer(memory,20,10)));
   FeatureTest.request(new ChatClient(),server,Provider.CUSTOM,false); // Warm up transport before measuring a read timeout, not JVM startup.
   server.delayMillis=1000;server.cancellationExpected=true;ChatClient client=new ChatClient();client.budgetMillis(500);var hooks=new MemoryReducerTest.Hooks();long start=System.nanoTime();
   try{MemoryReducer.reduce(client,Provider.CUSTOM,server.url(),"","fixture","summarize",List.of(ChatClient.Message.user("original historical data")),256,8192,1.25,hooks);throw new AssertionError("unbounded compaction");}
   catch(IOException expected){check(expected.getMessage().contains(I18n.text("Compactarea a depasit timpul de asteptare; istoricul si memoria sunt pastrate. Poti folosi comparatia locala fara API sau relua cu alt model.")),"localized compaction timeout");check(hooks.attempts==1&&server.requests.size()==2,"read timeout never retries inference: "+hooks.attempts+" attempts, "+server.requests.size()+" received");check((System.nanoTime()-start)/1_000_000<2500,"timeout returns promptly");}
  }
  try(var server=new FeatureTest.Server(FeatureTest.json(FeatureTest.answer("normal response",20,10)))){server.delayMillis=140;ChatClient client=new ChatClient();check(FeatureTest.request(client,server,Provider.CUSTOM,false).text.equals("normal response"),"normal generation does not inherit compaction budget");}
  try(var server=new FeatureTest.Server(FeatureTest.json(FeatureTest.answer(memory,20,10)))){ChatClient client=new ChatClient();client.budgetMillis(1);Thread.sleep(10);var hooks=new MemoryReducerTest.Hooks();try{MemoryReducer.reduce(client,Provider.CUSTOM,server.url(),"","fixture","summarize",List.of(ChatClient.Message.user("evidence")),256,8192,1.25,hooks);throw new AssertionError("expired budget ignored");}catch(IOException expected){check(server.requests.isEmpty()&&hooks.attempts==0&&hooks.usage==0,"expired common budget prevents API calls and fake usage counts");}}

  check(MemoryReducer.planBudgetMillis(1)==300_000,"short plan keeps five minute bound");
  check(MemoryReducer.planBudgetMillis(4)==480_000,"four fragments do not share undersized five minute deadline");
  check(MemoryReducer.planBudgetMillis(Integer.MAX_VALUE)==900_000,"large plans remain bounded without integer overflow");
  String events=FeatureTest.event(Json.obj("choices",List.of(Json.obj("delta",Json.obj("content",memory)))))+FeatureTest.event(Json.obj("choices",List.of(),"usage",Json.obj("prompt_tokens",30,"completion_tokens",15)))+"data: [DONE]\n\n";
  try(var server=new FeatureTest.Server(FeatureTest.sse(events))){var hooks=new MemoryReducerTest.Hooks();String result=MemoryReducer.reduce(new ChatClient(),Provider.CUSTOM,server.url(),"","fixture","summarize",List.of(ChatClient.Message.user("historical evidence")),256,8192,1.25,hooks);check(result.equals(memory),"streamed summary collected completely");check(Boolean.TRUE.equals(Json.get(server.requests.get(0),"stream")),"compaction requests streaming transport");check(hooks.attempts==1&&hooks.usage==1,"stream usage counted exactly once");check(Json.get(server.requests.get(0),"tools")==null,"streaming summary cannot execute tools");}
  try(var server=new FeatureTest.Server(FeatureTest.sse(FeatureTest.event(Json.obj("choices",List.of(Json.obj("delta",Json.obj("content","partial unverified memory")))))))){var hooks=new MemoryReducerTest.Hooks();try{MemoryReducer.reduce(new ChatClient(),Provider.CUSTOM,server.url(),"","fixture","summarize",List.of(ChatClient.Message.user("evidence")),256,8192,1.25,hooks);throw new AssertionError("premature EOF accepted");}catch(IOException expected){check(hooks.attempts==1&&server.requests.size()==1,"premature EOF does not retry or accept partial memory");check(expected.getMessage().contains(I18n.text("Compactarea a esuat: fluxul API s-a inchis inaintea raspunsului complet; istoricul si memoria sunt pastrate.")),"premature EOF has localized diagnostic");}}
  String anthropic=FeatureTest.event(Json.obj("type","content_block_delta","index",0,"delta",Json.obj("type","text_delta","text",memory)))+FeatureTest.event(Json.obj("type","message_stop"));
  try(var server=new FeatureTest.Server(FeatureTest.sse(anthropic))){var hooks=new MemoryReducerTest.Hooks();check(MemoryReducer.reduce(new ChatClient(),Provider.ANTHROPIC,server.url(),"","fixture","summarize",List.of(ChatClient.Message.user("evidence")),256,8192,1.25,hooks).equals(memory),"Anthropic message_stop completes summary without OpenAI DONE marker");check(Boolean.TRUE.equals(Json.get(server.requests.get(0),"stream"))&&hooks.attempts==1,"Anthropic streaming is explicit and single-attempt");}
  // A visible partial summary must never become memory if the provider stalls before completion.
  try(var socket=new java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))){
   var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
   Thread worker=new Thread(()->{try(var conn=socket.accept()){
    conn.setSoTimeout(3000);var in=conn.getInputStream();var header=new ByteArrayOutputStream();int b;while((b=in.read())>=0){header.write(b);if(header.toString(java.nio.charset.StandardCharsets.US_ASCII).endsWith("\r\n\r\n"))break;}
    int length=0;for(String line:header.toString(java.nio.charset.StandardCharsets.US_ASCII).split("\r\n"))if(line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(15).strip());in.readNBytes(length);
    var out=conn.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n"+FeatureTest.event(Json.obj("choices",List.of(Json.obj("delta",Json.obj("content","partial unverified memory")))))).getBytes(java.nio.charset.StandardCharsets.UTF_8));out.flush();Thread.sleep(700);
   }catch(Throwable e){failure.set(e);}},"partial-summary-fixture");worker.setDaemon(true);worker.start();
   ChatClient client=new ChatClient();client.budgetMillis(250);var hooks=new MemoryReducerTest.Hooks();
   try{MemoryReducer.reduce(client,Provider.CUSTOM,"http://127.0.0.1:"+socket.getLocalPort(),"","fixture","summarize",List.of(ChatClient.Message.user("original evidence")),256,8192,1.25,hooks);throw new AssertionError("partial stream accepted");}catch(IOException expected){check(hooks.attempts==1,"stalled partial stream never retried");check(expected.getCause() instanceof java.net.SocketTimeoutException,"stream stall reports bounded timeout");}
   worker.join(2000);check(failure.get()==null,"partial fixture completed without error");
  }

  System.out.println("PASS: "+checks+" compaction time budget checks");
 }
}
