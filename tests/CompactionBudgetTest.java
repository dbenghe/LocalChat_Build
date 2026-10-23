package localchat;
import java.io.*;import java.util.*;
/** Synthetic delayed API, no external network. */
public final class CompactionBudgetTest {
 static int checks;static void check(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
 public static void main(String[] args)throws Exception{
  String memory="Project 123456: verified source retained; no new actions.";
  try(var server=new FeatureTest.Server(FeatureTest.json(FeatureTest.answer(memory,20,10)))){
   server.delayMillis=1000;server.cancellationExpected=true;ChatClient client=new ChatClient();client.budgetMillis(500);var hooks=new MemoryReducerTest.Hooks();long start=System.nanoTime();
   try{MemoryReducer.reduce(client,Provider.CUSTOM,server.url(),"","fixture","summarize",List.of(ChatClient.Message.user("original historical data")),256,8192,1.25,hooks);throw new AssertionError("unbounded compaction");}
   catch(IOException expected){check(expected.getMessage().contains(I18n.text("Compactarea a depasit timpul de asteptare; istoricul si memoria sunt pastrate. Poti folosi comparatia locala fara API sau relua cu alt model.")),"localized compaction timeout");check(hooks.attempts==1&&server.requests.size()==1,"read timeout never retries inference: "+hooks.attempts+" attempts, "+server.requests.size()+" received");check((System.nanoTime()-start)/1_000_000<2500,"timeout returns promptly");}
  }
  try(var server=new FeatureTest.Server(FeatureTest.json(FeatureTest.answer("normal response",20,10)))){server.delayMillis=140;ChatClient client=new ChatClient();check(FeatureTest.request(client,server,Provider.CUSTOM,false).text.equals("normal response"),"normal generation does not inherit compaction budget");}
  try(var server=new FeatureTest.Server(FeatureTest.json(FeatureTest.answer(memory,20,10)))){ChatClient client=new ChatClient();client.budgetMillis(1);Thread.sleep(10);var hooks=new MemoryReducerTest.Hooks();try{MemoryReducer.reduce(client,Provider.CUSTOM,server.url(),"","fixture","summarize",List.of(ChatClient.Message.user("evidence")),256,8192,1.25,hooks);throw new AssertionError("expired budget ignored");}catch(IOException expected){check(server.requests.isEmpty()&&hooks.attempts==0&&hooks.usage==0,"expired common budget prevents API calls and fake usage counts");}}
  System.out.println("PASS: "+checks+" compaction time budget checks");
 }
}
