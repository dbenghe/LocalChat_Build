package localchat;
import java.nio.file.*;import java.util.*;import javax.swing.*;
/** Automatic scheduling uses the selected model and real child execution, no external inference. */
public final class AutomaticDelegationTest {
 static int checks;static void check(boolean ok,String m){checks++;if(!ok)throw new AssertionError(m);}
 public static void main(String[] args)throws Exception{
  Path home=Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("localchat.home"))),"automatic-");
  String prompt="Verifica emailul nou si istoricul proiectului 507123. Compara listele de import cu catalogul fabricii si verifica rapoartele PDFCONTROL existente.";
  check(AutoDelegation.plan(prompt,home,"507123").size()==3,"independent sources, imports and drawing evidence produce three tasks");
  check(AutoDelegation.plan("Verifica "+"un singur document ".repeat(500),home,"507123").isEmpty(),"length alone never launches agents");
  check(AutoDelegation.plan(prompt+" Fara agenti.",home,"507123").isEmpty(),"explicit opt-out overrides automatic scheduling");
  check(AutoDelegation.plan("> "+prompt,home,"507123").isEmpty(),"quoted instructions cannot launch agents");
  check(AutoDelegation.plan("Ce stii despre email si liste import si PDFCONTROL?",home,"507123").isEmpty(),"capability questions do not start work");
  check(AutoDelegation.plan("Verifica listele de import si catalogul.",home,"507123").isEmpty(),"one local comparison remains with coordinator");
  check(AutoDelegation.plan("Verifica doar listele import; fara emailuri sau PDFCONTROL.",home,"507123").isEmpty(),"excluded evidence categories cannot create extra work");
  for(String localized:List.of("Verifica emailul si listele de import.","Check the email and import lists.","Controleer de email en importlijsten.","Revisa el email y las listas de importación.","Sprawdź wiadomość i listy importu.","E-posta ve katalog ürünlerini kontrol et."))check(AutoDelegation.plan(localized,home,"507123").size()>=2,"automatic evidence categories recognised in six languages");
  check(AutoDelegation.plan("E-posta ve katalog ürünlerini kontrol et, ajan kullanma.",home,"507123").isEmpty(),"Turkish explicit opt-out respected");
  Tools analysis=ToolProtocolTest.tools(home).analysisOnly();check(!analysis.specs().stream().anyMatch(t->Set.of("run_command","write_file","copy_path","save_rule").contains(t.name)),"automatic children cannot see mutation or shell tools");
  check(analysis.execute(new ChatClient.ToolCall("w","write_file",Json.obj("path",home.resolve("never.txt").toString(),"content","NO"))).isError&&!Files.exists(home.resolve("never.txt")),"invented write is blocked even when called outside schema");
  for(String model:List.of("arbitrary-provider-model-a","local-lmstudio-model-b")){
   try(var server=new FeatureTest.Server(FeatureTest.json(FeatureTest.answer("Source evidence inspected; no changes.",20,4)),FeatureTest.json(FeatureTest.answer("Import evidence inspected; no changes.",20,4)),FeatureTest.json(FeatureTest.answer("Coordinator final answer.",40,8)))){
    LocalChat[] app={null};FeatureTest.edt(()->{app[0]=AttachmentTest.window(home.resolve(model),server.url());((JComboBox<?>)FeatureTest.field(app[0],"modelBox")).setSelectedItem(model);});
    NormalModeTest.ask(app[0],"Verifica emailul nou si compara listele de import pentru proiectul 507123.");
    FeatureTest.edt(()->{
     check(server.requests.size()==3,"two child requests followed by coordinator synthesis without planner inference");
     check(server.requests.stream().allMatch(q->model.equals(Json.str(q,"model"))),"arbitrary selected model is used for all requests");
     check(!AgentIntegrationTest.tool(server.requests.get(0),"run_agents")&&!AgentIntegrationTest.tool(server.requests.get(0),"write_file"),"automatic child cannot recursively delegate or write");
     check(AttachmentTest.state(app[0]).usage.requests==3,"all actual API requests counted once");
     check(NormalModeTest.history(app[0]).stream().anyMatch(m->m.role.equals("tool")&&m.content.contains("completed_tool_count")&&m.content.contains("done")),"real agent outcomes are archived in parent chat");
     check(Json.write(server.requests.get(2)).contains("Source evidence inspected"),"coordinator receives child evidence before answering");
     check(((JTextPane)FeatureTest.field(app[0],"transcript")).getText().contains("Coordinator final answer"),"coordinator final stays visible");app[0].dispose();
    });
   }
  }
  System.out.println("PASS: "+checks+" automatic delegation checks; arbitrary selected models, isolated analysis, actual usage and archives");System.exit(0);
 }
}
