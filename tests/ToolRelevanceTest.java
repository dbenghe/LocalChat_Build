package localchat;
import java.nio.file.*;import java.util.*;
/** 1.27.1: relevance-based tool sets, the explicit escape hatches, read-only and agent restrictions. */
public final class ToolRelevanceTest {
 static int checks;static void check(boolean ok,String m){checks++;if(!ok)throw new AssertionError(m);}
 static Set<String> names(Tools t){Set<String> out=new TreeSet<>();for(ChatClient.ToolSpec s:t.specs())out.add(s.name);return out;}
 static final List<String> MUTATORS=List.of("write_file","append_file","edit_file","make_dir","copy_path","move_path","extract_attachments","save_rule","run_command");
 static Set<String> unlocked(String latest){return ToolRelevance.unlocked(List.of(),latest);}
 static ChatClient.ToolCall call(String name,Map<String,Object> args){return new ChatClient.ToolCall("c-"+name,name,args);}
 public static void main(String[] args)throws Exception{
  Path home=Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("localchat.home"))),"relevance-");
  Set<String> all=names(ToolProtocolTest.tools(home));
  check(all.containsAll(MUTATORS)&&!all.contains(ToolRelevance.ENABLE),"legacy (tools.all) sends every tool and no enable tool");
  Set<String> base=names(ToolProtocolTest.tools(home).selective(Set.of()));
  check(base.stream().noneMatch(MUTATORS::contains),"default tool set hides file-changing and command tools");
  check(base.containsAll(List.of("list_dir","find_files","search_text","read_file","read_skill","open_in_explorer","localchat_enable_tools")),"default tool set keeps analysis tools and the unlock tool");
  check(Json.write(ToolProtocolTest.tools(home).selective(Set.of()).specs().stream().map(s->s.name).toList()).length()<Json.write(all.stream().toList()).length(),"selective list is shorter");
  // keywords in the six languages unlock the matching group only
  for(String text:List.of("Salveaza rezultatul intr-un fisier nou","Kopieer het bestand naar de server","Please edit the report and save it","Copia el archivo a la carpeta","Zapisz plik w folderze projektu","Dosyayi bu klasore kopyala","Tine minte ca lucrez cu fabrica Soest"))
   check(unlocked(text).equals(Set.of(ToolRelevance.FILES)),"files unlocked by: "+text);
  for(String text:List.of("Ruleaza comanda pip install","Run a python script","Voer dit script uit met PowerShell","Ejecuta el comando en la terminal","Uruchom polecenie w terminalu","Bu komutu calistir"))
   check(unlocked(text).contains(ToolRelevance.COMMAND),"command unlocked by: "+text);
  for(String text:List.of("Verifica proiectul 123456 si genereaza raportul PDFCONTROL","Controleer project 123456 en maak het rapport","Compara listele de import cu catalogul fabricii Soest","Ce desene au fost verificate?","Check the drawings of project 123456"))
   check(unlocked(text).isEmpty()||!unlocked(text).contains(ToolRelevance.COMMAND),"read/PDFCONTROL requests do not unlock the command tool: "+text);
  check(unlocked("Verifica proiectul 123456 si compara listele de import").isEmpty(),"verification and comparison need no unlock");
  // sticky from history: earlier unlock tool call or earlier mutation keeps the group
  List<ChatClient.Message> history=new ArrayList<>(List.of(ChatClient.Message.user("Verifica proiectul"),ChatClient.Message.assistant("",List.of(call(ToolRelevance.ENABLE,Json.obj("group","files"))),"m")));
  check(ToolRelevance.unlocked(history,"si acum?").equals(Set.of(ToolRelevance.FILES)),"an earlier unlock stays active in the conversation");
  history=List.of(ChatClient.Message.user("Verifica"),ChatClient.Message.assistant("",List.of(call("run_command",Json.obj("command","dir"))),"m"));
  check(ToolRelevance.unlocked(history,"continua").equals(Set.of(ToolRelevance.COMMAND)),"an earlier command call keeps the command group");
  check(ToolRelevance.unlocked(List.of(ChatClient.Message.user("Salveaza fisierul")),"Verifica altceva").contains(ToolRelevance.FILES),"an earlier user request keeps the group (only grows)");
  // the unlock tool
  Tools tools=ToolProtocolTest.tools(home).selective(Set.of());
  ChatClient.ToolSpec enable=tools.specs().stream().filter(s->s.name.equals(ToolRelevance.ENABLE)).findFirst().orElseThrow();
  check(Json.write(enable.schema).contains("files")&&Json.write(enable.schema).contains("command"),"unlock tool offers both locked groups");
  check(tools.execute(call(ToolRelevance.ENABLE,Json.obj("group","nothing"))).isError,"unknown group refused");
  var enabled=tools.execute(call(ToolRelevance.ENABLE,Json.obj("group","files")));
  check(!enabled.isError&&names(tools).contains("write_file")&&!names(tools).contains("run_command"),"unlock tool reveals only its group on the next request");
  check(Json.write(tools.specs().stream().filter(s->s.name.equals(ToolRelevance.ENABLE)).findFirst().orElseThrow().schema).contains("command")&&!Json.write(tools.specs().stream().filter(s->s.name.equals(ToolRelevance.ENABLE)).findFirst().orElseThrow().schema).contains("files"),"unlock tool then offers only what is still hidden");
  tools.execute(call(ToolRelevance.ENABLE,Json.obj("group","command")));check(!names(tools).contains(ToolRelevance.ENABLE)&&names(tools).contains("run_command"),"fully unlocked set has no unlock tool");
  // permissions still apply to unlocked tools
  Tools unlockedTools=new Tools(new Tools.Context(home,home.resolve("skills"),home.resolve("rules.md"),home.resolve("settings.properties"),Map.of(),null,new Mcp(),PathMap.none(),false,EnumSet.noneOf(Tools.Access.class),(a,b)->Tools.Decision.NO),List.of()).selective(Set.of(ToolRelevance.FILES,ToolRelevance.COMMAND));
  check(unlockedTools.execute(call("write_file",Json.obj("path",home.resolve("blocked.txt").toString(),"content","x"))).isError&&!Files.exists(home.resolve("blocked.txt")),"unlocked mutators still need user permission");
  // read-only mode: no mutators, no unlock tool, hidden tools cannot be called or unlocked
  Tools readOnly=ToolProtocolTest.tools(home).selective(Set.of(ToolRelevance.FILES,ToolRelevance.COMMAND)).readOnly(true);
  Set<String> ro=names(readOnly);check(ro.stream().noneMatch(MUTATORS::contains)&&!ro.contains(ToolRelevance.ENABLE)&&ro.contains("read_file"),"read-only hides every mutator even when unlocked, and the unlock tool");
  check(readOnly.execute(call(ToolRelevance.ENABLE,Json.obj("group","files"))).isError,"unlock tool cannot be used in read-only mode");
  check(readOnly.execute(call("write_file",Json.obj("path",home.resolve("ro.txt").toString(),"content","x"))).isError&&!Files.exists(home.resolve("ro.txt")),"read-only blocks a write called outside the schema");
  check(readOnly.execute(call("save_rule",Json.obj("rule","x"))).isError,"read-only still blocks save_rule");
  // automatic children: analysis only, no unlock tool, no mutators
  Tools child=ToolProtocolTest.tools(home).analysisOnly();Set<String> childNames=names(child);
  check(childNames.stream().noneMatch(MUTATORS::contains)&&!childNames.contains(ToolRelevance.ENABLE),"analysis-only children never see mutators or the unlock tool");
  Tools childSelective=ToolProtocolTest.tools(home).selective(Set.of()).analysisOnly();check(!names(childSelective).contains(ToolRelevance.ENABLE)&&childSelective.execute(call(ToolRelevance.ENABLE,Json.obj("group","files"))).isError,"analysis-only agents cannot unlock tools");
  System.out.println("PASS: "+checks+" relevance checks");System.exit(0);
 }
}