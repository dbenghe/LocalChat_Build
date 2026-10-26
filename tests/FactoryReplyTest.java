package localchat;
import java.nio.file.*;import java.util.*;import javax.swing.*;
/** Synthetic reproduction of a pending factory card belonging to another project. */
public final class FactoryReplyTest {
 static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static void invoke(LocalChat app,String name)throws Exception{FeatureTest.call(app,name,new Class<?>[]{});}
 static ComposerAssist card(LocalChat app)throws Exception{return FeatureTest.field(app,"composerAssist");}
 static JTextField custom(ComposerAssist card){return ComposerAssistTest.all(card).stream().filter(c->c instanceof JTextField).map(c->(JTextField)c).findFirst().orElseThrow();}
 public static void main(String[] args)throws Exception{
  Path home=Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("localchat.home"))),"reply-");
  Path root=Files.createDirectories(home.resolve("soest")),folder=Files.createDirectories(root.resolve("507136_Test"));
  Path wrong=Files.createDirectories(home.resolve("projects/506556")),right=Files.createDirectories(home.resolve("projects/507136"));
  check(ProjectLocations.project("Nu mai am email nou",wrong,"507136").equals("507136"),"confirmed project outranks inherited folder for short answers");
  check(ProjectLocations.project("Continua proiectul 506556",right,"507136").equals("506556"),"explicit current project still overrides confirmed scope");
  check(ProjectLocations.project("Continua",wrong,"").equals("506556"),"new conversation still derives project from chosen folder");
  check(ProjectLocations.projectFolder(wrong,"507136").equals(right),"inherited numbered folder aligns only to existing sibling");
  check(ProjectLocations.projectFolder(wrong,"507999").equals(wrong),"missing sibling is not invented");
  var match=new ProjectLocations.Match(new ProjectLocations.Root("soest",root.toString()),folder.toString());
  var old=new ProjectLocations.Result("506556",List.of(),List.of(match.root()),List.of());
  var valid=new ProjectLocations.Result("507136",List.of(match),List.of(match.root()),List.of());
  check(ProjectLocations.answer(valid,"MBS Soest").equals(match),"free text MBS prefix accepted");check(ProjectLocations.answer(valid,"1").equals(match),"free option number accepted");
  LocalChat[] app={null};
  try(var server=new FeatureTest.Server(FeatureTest.json(FeatureTest.answer("Current project continued",2,3)))){
   FeatureTest.edt(()->{app[0]=AttachmentTest.window(home.resolve("app"),server.url());ProjectLocationsTest.configure(app[0],Json.obj("projectRoots",List.of(Json.obj("factory","soest","path",root.toString()))));
    var state=AttachmentTest.state(app[0]);state.factoryProject="507136";state.factoryName="soest";state.factoryRoot=root.toString();state.factoryFolder=folder.toString();state.factoryPending=old.json();
    FeatureTest.<JTextField>field(app[0],"workDirField").setText(wrong.toString());AttachmentTest.input(app[0]).setText("Nu mai este niciun email nou.");
    card(app[0]).presentLocal(ProjectLocations.question(old),"factory:old");state.interactionState=card(app[0]).snapshot();invoke(app[0],"restoreComposerAssist");
    check(state.factoryPending.isBlank()&&!card(app[0]).waiting(),"stale saved card removed on restore without inference");check(state.factoryProject.equals("507136")&&state.factoryName.equals("soest"),"current confirmed scope retained");check(AttachmentTest.input(app[0]).getText().equals("Nu mai este niciun email nou.")&&server.requests.isEmpty(),"draft and no inference on restore");
    invoke(app[0],"alignProjectFolder");check(FeatureTest.<JTextField>field(app[0],"workDirField").getText().equals(right.toString()),"chat aligns workspace to its confirmed project");
    state.factoryPending=old.json();card(app[0]).presentLocal(ProjectLocations.question(old),"factory:old-again");custom(card(app[0])).setText("soest");card(app[0]).send.doClick();
    check(state.factoryPending.isBlank()&&state.factoryProject.equals("507136")&&server.requests.isEmpty(),"stale selection cannot authorize another project");
    state.factoryProject="";state.factoryName="";state.factoryPending=valid.json();invoke(app[0],"restoreComposerAssist");custom(card(app[0])).setText("nu mai am nimic nou");card(app[0]).send.doClick();
    check(card(app[0]).waiting()&&card(app[0]).value("factory").equals("nu mai am nimic nou"),"invalid factory free text stays visible and editable");check(server.requests.isEmpty(),"unresolved factory does not execute");
    custom(card(app[0])).setText("MBS Soest");card(app[0]).send.doClick();
   });FeatureTest.idle(app[0]);
   FeatureTest.edt(()->{check(server.requests.size()==1,"valid free answer continues exactly once");check(AttachmentTest.state(app[0]).factoryProject.equals("507136"),"continued correct project");check(Json.write(server.requests).contains("507136")&&!Json.write(server.requests).contains("506556"),"wrong pending project never reaches inference");app[0].dispose();});
  }
  try(var server=new FeatureTest.Server(FeatureTest.json(FeatureTest.answer("Free reply received",2,3)))){
   FeatureTest.edt(()->{app[0]=AttachmentTest.window(home.resolve("model-question"),server.url());ProjectLocationsTest.configure(app[0],Json.obj("projectRoots",List.of()));card(app[0]).presentLocal(new ComposerAssist.Request("Question",List.of(new ComposerAssist.Question("q","What changed?",List.of("Email","Drawing"))),List.of()),"model-question");custom(card(app[0])).setText("No new email for project 507136");card(app[0]).send.doClick();});FeatureTest.idle(app[0]);
   FeatureTest.edt(()->{check(server.requests.size()==1&&Json.write(server.requests).contains("No new email for project 507136"),"model card custom answer reaches request once");app[0].dispose();});
  }
  try(var server=new FeatureTest.Server(FeatureTest.json(FeatureTest.answer("Reused confirmed scope",1,1)))){
   FeatureTest.edt(()->{app[0]=AttachmentTest.window(home.resolve("remembered"),server.url());ProjectLocationsTest.configure(app[0],Json.obj("projectRoots",List.of(Json.obj("factory","soest","path",home.resolve("offline-root").toString()))));
    var state=AttachmentTest.state(app[0]);state.factoryProject="507136";state.factoryName="soest";state.factoryRoot=home.resolve("offline-root").toString();state.factoryFolder=state.factoryRoot+"/507136_Saved";state.memory.summary="Confirmed previous analysis";state.factoryPending=valid.json();AttachmentTest.input(app[0]).setText("Continua proiectul 507136 de la comparatia Soest si Veenoord");invoke(app[0],"restoreComposerAssist");
    check(state.factoryPending.isBlank()&&!card(app[0]).waiting(),"same-project redundant factory card removed after confirmation");invoke(app[0],"send");
   });FeatureTest.idle(app[0]);
   FeatureTest.edt(()->{check(server.requests.size()==1&&!card(app[0]).waiting(),"confirmed factory reused without availability scan or repeated question");check(AttachmentTest.state(app[0]).factoryName.equals("soest"),"comparison and memory retain factory");app[0].dispose();});
  }
  try(var server=new FeatureTest.Server()){
   FeatureTest.edt(()->{
    app[0]=AttachmentTest.window(home.resolve("switch-cards"),server.url());
    var first=new Conversations.State();first.toolsEnabled=true;first.contextMode="mbs";first.draft="Continua proiectul 506556";first.factoryPending=old.json();
    Path firstFile=home.resolve("switch-cards/conversatii/first.json");Conversations.save(firstFile,home.toString(),List.of(ChatClient.Message.user("Continua proiectul 506556")),first);
    var second=new Conversations.State();second.toolsEnabled=true;second.contextMode="mbs";second.draft="Nu mai am email nou pentru proiectul 507136";second.factoryProject="507136";second.factoryName="soest";second.factoryRoot=root.toString();second.factoryFolder=folder.toString();
    Path secondFile=home.resolve("switch-cards/conversatii/second.json");Conversations.save(secondFile,wrong.toString(),List.of(ChatClient.Message.user("Continua proiectul 507136")),second);
    FeatureTest.call(app[0],"openSession",new Class<?>[]{Conversations.Saved.class},Conversations.load(firstFile));
    check(card(app[0]).waiting()&&card(app[0]).snapshot().contains("factory:"),"first chat restores its pending choice");
    FeatureTest.call(app[0],"openSession",new Class<?>[]{Conversations.Saved.class},Conversations.load(secondFile));
    check(!card(app[0]).waiting()&&AttachmentTest.state(app[0]).factoryProject.equals("507136"),"selecting second chat never carries first chat card");
    check(AttachmentTest.input(app[0]).getText().equals(second.draft),"second chat keeps its own editor text");
    check(FeatureTest.<JTextField>field(app[0],"workDirField").getText().equals(right.toString()),"loading restores project folder instead of inherited other-chat folder");
    FeatureTest.call(app[0],"openSession",new Class<?>[]{Conversations.Saved.class},Conversations.load(firstFile));
    check(card(app[0]).waiting()&&ProjectLocations.parse(AttachmentTest.state(app[0]).factoryPending).project().equals("506556"),"returning to first restores only its own pending question");
    check(server.requests.isEmpty(),"switching chats does not call inference");app[0].dispose();
   });
  }
  System.out.println("PASS: "+checks+" factory reply checks");System.exit(0);
 }
}
