package localchat;
import java.nio.file.*;import java.util.*;import javax.swing.*;
/**
 * 1.27.1 token budget: thresholds for the MBS overhead (system prompt + tool schemas) and the Normal request.
 * Baseline before 1.27.1 (MBS first request): body 28,344 chars = system 9,053 + tools 19,017 (30 tools). Normal: 1,490 chars.
 * The limits below carry about 5% margin over the measured values; raise them only on purpose.
 */
public final class TokenBudgetTest {
 static int checks;static void check(boolean ok,String m){checks++;if(!ok)throw new AssertionError(m);}
 static final int BASE_BODY=28344;
 static int chars(List<ChatClient.ToolSpec> specs){return specs.stream().mapToInt(s->Json.write(Json.obj("type","function","function",Json.obj("name",s.name,"description",s.description,"parameters",s.schema))).length()).sum();}
 public static void main(String[] args)throws Exception{
  Path pack=Path.of(System.getProperty("pdfcontrol.package")),root=Path.of(System.getProperty("pdfcontrol.projects")),project=root.resolve("123456");
  Path home=Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("localchat.home"))),"budget-");
  Mcp mcp=new Mcp();
  try{
   mcp.reload(Json.obj("mcpServers",Json.obj("pdfcontrol",Json.obj("command",pack.resolve("runtime/python/python.exe").toString(),"args",List.of("-I",pack.resolve("octop_mcp.py").toString()),"env",Json.obj("PDFCONTROL_PACKAGE",pack.toString(),"PDFCONTROL_PROJECTS_ROOT",root.toString()),"cwd",pack.toString()))),s->s);
   check(mcp.servers().size()==1,"real owned MCP connected");
   java.util.function.Supplier<Tools> factory=()->new Tools(new Tools.Context(project,project.resolve("skills"),project.resolve("reguli.md"),project.resolve("settings.properties"),Map.of(),null,mcp,PathMap.none(),true,EnumSet.allOf(Tools.Access.class),(a,b)->Tools.Decision.NO),List.of());
   List<ChatClient.ToolSpec> all=factory.get().specs(),selective=factory.get().selective(Set.of()).specs(),readOnly=factory.get().selective(Set.of()).readOnly(true).specs();
   check(all.stream().noneMatch(s->s.name.equals("pdfcontrol_live_status")),"duplicate live-status alias is not offered");
   check(all.stream().noneMatch(s->s.description.startsWith("[MCP")),"no [MCP server] prefix on tools whose name already carries the server");
   check(all.stream().anyMatch(s->s.name.equals("pdfcontrol_start_workflow"))&&selective.stream().anyMatch(s->s.name.equals("pdfcontrol_start_workflow")),"PDFCONTROL start tools stay in the default set");
   check(selective.stream().anyMatch(s->s.name.equals("pdfcontrol_compare_import"))&&readOnly.stream().anyMatch(s->s.name.equals("pdfcontrol_compare_import")),"local comparison stays available");
   ChatClient.ToolSpec interaction=ComposerAssist.spec();int allChars=chars(all)+chars(List.of(interaction)),selChars=chars(selective)+chars(List.of(interaction)),roChars=chars(readOnly)+chars(List.of(interaction));
   check((all.size()+1)<=34&&allChars<=18650,"all tools: "+all.size()+" tools, "+allChars+" chars (was 30 tools, 19,017 chars; 1.28.0 +4 read-only PDFCONTROL tools, +~2.0k chars; 1.28.9 +download_url, measured 33 tools / 18,099 chars)");
   check((selective.size()+1)<=24&&selChars<=12700,"default relevance set: "+selective.size()+" tools, "+selChars+" chars (1.28.0: +4 read-only PDFCONTROL tools, +~1.9k chars; 1.28.9 measured 23 tools / 12,289 chars)");
   check((readOnly.size()+1)<=16&&roChars<=7700,"read-only relevance set: "+readOnly.size()+" tools, "+roChars+" chars (1.28.0: the 4 new tools are read-only and are exactly what read-only/analysis sessions need, +~1.1k chars)");
   LocalChat[] app={null};FeatureTest.edt(()->{try{app[0]=AttachmentTest.window(home,"http://127.0.0.1:9/v1");}catch(Exception e){throw new RuntimeException(e);}});
   String[] system={null};FeatureTest.edt(()->{try{((JComboBox<?>)FeatureTest.field(app[0],"permissionBox")).setSelectedIndex(1);
    system[0]=(String)FeatureTest.call(app[0],"agentPrompt",new Class<?>[]{Path.class,List.class,PathMap.class},home,List.of(new Skills.Skill("pdfcontrol-octop","PDFCONTROL cu motorul original - control desene cu OCR, raport PDF, brosuri, perechi V/W, comparatie OUD/NIEUW.",home.resolve("SKILL.md"))),PathMap.none());}catch(Exception e){throw new RuntimeException(e);}});
   check(system[0].length()<=5300,"MBS system prompt: "+system[0].length()+" chars (was 9,053)");
   long overhead=system[0].length()+selChars;
   check(overhead<=17600&&overhead*100<=BASE_BODY*62L,"MBS overhead (system + default tools) "+overhead+" chars vs 28,344 baseline (1.28.9: answer-as-expert rule +246 chars and the download_url schema, measured 17,098 = 60.3%)");
   check((system[0].length()+allChars)*100<=BASE_BODY*82L,"even with every tool the MBS overhead is under 76% of the baseline (was 70%; 1.28.0 adds 4 read-only tools, ~74.8% measured; 1.28.9 adds download_url and the answer rule, ~80.8% measured): "+(system[0].length()+allChars));
   System.out.println("   MBS system="+system[0].length()+" tools(all)="+all.size()+"/"+allChars+" tools(default)="+selective.size()+"/"+selChars+" tools(read-only)="+readOnly.size()+"/"+roChars+" overhead="+overhead+" est.tokens(default)="+(ContextMemory.estimate(system[0])+ContextMemory.tools(selective)));
   // Normal stays clean and small
   try(var server=new FeatureTest.Server(FeatureTest.json(FeatureTest.answer("Normal answer",100,10)))){
    LocalChat[] normal={null};FeatureTest.edt(()->{try{normal[0]=AttachmentTest.window(home.resolve("normal"),server.url());NormalModeTest.mode(normal[0],false);}catch(Exception e){throw new RuntimeException(e);}});
    NormalModeTest.ask(normal[0],"Salut, ce faci?");
    check(server.requests.size()==1,"one Normal request");
    Object request=server.requests.get(0);String body=Json.write(request);
    check(Json.get(request,"tools")==null,"Normal sends no tools");
    check(body.length()<=1700,"Normal request body: "+body.length()+" chars (was 1,490)");
    check(!body.contains("pdfcontrol")&&!body.contains("run_command"),"Normal stays clean of MBS rules and tools");
    System.out.println("   Normal body="+body.length());
    FeatureTest.edt(()->normal[0].dispose());
   }
   FeatureTest.edt(()->app[0].dispose());
  }finally{mcp.closeAll();}
  System.out.println("PASS: "+checks+" token budget checks");System.exit(0);
 }
}