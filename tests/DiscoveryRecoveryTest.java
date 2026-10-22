package localchat;
import java.nio.file.*;import java.util.*;import java.util.concurrent.atomic.AtomicInteger;
public final class DiscoveryRecoveryTest {
 static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static FeatureTest.Response skill(String id){return InteractionRecoveryTest.reply("",InteractionRecoveryTest.call(id,"read_skill",Json.obj("name","demo")));}
 static Agent.Ui ui(AtomicInteger operations){return ToolProtocolTest.ui(operations);}
 public static void main(String[] args)throws Exception{
  Path root=Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("localchat.home"))),"run-");Files.createDirectories(root.resolve("skills/demo"));Files.writeString(root.resolve("skills/demo/SKILL.md"),"---\nname: demo\ndescription: Fixture\n---\nProcedure");Files.writeString(root.resolve("actual.csv"),"article,count\nA,2");
  var t=new Tools(new Tools.Context(root,root.resolve("skills"),root.resolve("rules.md"),root.resolve("settings.properties"),Map.of(),null,new Mcp(),PathMap.none(),true,EnumSet.allOf(Tools.Access.class),(a,b)->Tools.Decision.NO),Skills.load(root.resolve("skills")));
  try(var server=new FeatureTest.Server(skill("a"),skill("b"),skill("c"),InteractionRecoveryTest.reply("",InteractionRecoveryTest.call("actual","read_file",Json.obj("path","actual.csv"))),FeatureTest.json(FeatureTest.answer("Compared actual data; pending decision.",10,3)))){
   Agent a=new Agent(t);a.run(Provider.CUSTOM,server.url(),"","fixture","fixture","system",List.of(ChatClient.Message.user("Compare the actual import files")),false,ui(new AtomicInteger()));
   check(server.requests.size()==5,"one guided recovery can advance to actual requested data");check(Json.write(server.requests.get(3)).contains("repeated discovery"),"recovery guide included in actual wire payload");check(a.collect("","fixture").stream().anyMatch(m->m.role.equals("tool")&&m.content.contains("A,2")),"real new document was read");
  }
  try(var server=new FeatureTest.Server(skill("a"),skill("b"),InteractionRecoveryTest.reply("",InteractionRecoveryTest.call("c","read_skill",Json.obj("name","demo")),InteractionRecoveryTest.call("sibling","write_file",Json.obj("path","never.txt","content","NO"))),skill("d"),FeatureTest.json(FeatureTest.answer("Partial findings; need a stronger tool-capable model to proceed.",10,3)))){
   Agent a=new Agent(t);a.run(Provider.CUSTOM,server.url(),"","fixture","fixture","system",List.of(ChatClient.Message.user("Compare")),false,ui(new AtomicInteger()));
   check(!Files.exists(root.resolve("never.txt")),"no sibling mutation after repeated discovery");check(Json.get(server.requests.get(4),"tools")==null||Json.get(server.requests.get(4),"tools") instanceof List<?> list&&list.isEmpty(),"persistent loop ends without tools");check(a.collect("","fixture").stream().anyMatch(m->m.content.startsWith("Partial findings")),"fallback preserves visible partial findings");check(Json.write(server.requests.get(3)).contains("sibling"),"skipped siblings are paired before recovery request");
  }
  try(var server=new FeatureTest.Server(skill("a"),skill("b"),skill("c"))){Agent a=new Agent(t);a.run(Provider.CUSTOM,server.url(),"","fixture","fixture","system",List.of(ChatClient.Message.user("Compare")),false,new Agent.Ui(){public void text(String s){}public void step(){}public void toolStarted(ChatClient.ToolCall c){}public void toolFinished(ChatClient.ToolCall c,Tools.Result r){if(r.isError)a.cancel();}});check(server.requests.size()==3,"Stop prevents automatic loop recovery");}
  for(String lang:I18n.CODES){I18n.setLanguage(lang);check(I18n.contains("Citire repetata fara progres. Foloseste rezultatele existente; recuperarea este limitata."),"localized recovery "+lang);}
  System.out.println("PASS: "+checks+" discovery recovery checks");System.exit(0);
 }
}
