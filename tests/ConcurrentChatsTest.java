package localchat;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import javax.swing.*;
/** Offline multi-window execution and explicit concurrency metadata. No external API calls. */
public final class ConcurrentChatsTest {
 static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static void call(Object app,String name)throws Exception{FeatureTest.call(app,name,new Class<?>[]{});}
 static Properties read(Path path)throws Exception{Properties p=new Properties();try(InputStream in=Files.newInputStream(path)){p.load(in);}return p;}
 static void metadata()throws Exception{
  Object root=Json.parse("{\"models\":[{\"key\":\"test\",\"loaded_instances\":[{\"id\":\"one\",\"config\":{\"context_length\":4096,\"parallel\":4}},{\"id\":\"two\",\"config\":{\"context_length\":2048,\"parallel\":2}}]}]}");
  check(ModelInfo.parse(root,"one","local").parallel()==4,"exact instance concurrency");
  check(ModelInfo.parse(root,"two","local").parallel()==2,"second instance independent");
  check(ModelInfo.parse(root,"test","local").parallel()==2,"ambiguous instance conservative minimum");
  Object unknown=Json.parse("{\"models\":[{\"key\":\"test\",\"loaded_instances\":[{\"config\":{\"parallel\":4}},{\"config\":{}}]}]}");
  check(ModelInfo.parse(unknown,"test","local").parallel()==0,"one unknown instance means unknown");
  check(ModelInfo.parse(Json.obj("data",List.of(Json.obj("id","cloud","max_concurrent_requests",3))),"cloud","cloud").parallel()==3,"declared cloud metadata");
  check(ModelInfo.parse(Json.obj("data",List.of(Json.obj("id","name-120b"))),"name-120b","cloud").parallel()==0,"no inference from name");
  var saved=ModelInfo.Info.from(ModelInfo.parse(root,"one","local").json());check(saved.parallel()==4&&saved.loaded()==4096,"metadata survives persistence");
  var zero=ModelCatalog.entry(Json.obj("id","a","pricing",Json.obj("prompt","0","completion",0)),"https://example.test/v1");
  check(zero.cost().equals("zero"),"both prices explicit zero");check(ModelCatalog.Entry.from(zero.json()).cost().equals("zero"),"cost persisted");
  check(ModelCatalog.cost(Json.obj("pricing",Json.obj("prompt",0))).equals("unknown"),"partial price unknown");
  check(ModelCatalog.cost(Json.obj("pricing",Json.obj("prompt",-1,"completion",0))).equals("unknown"),"sentinel price not free");
  check(ModelCatalog.cost(Json.obj("pricing",Json.obj("prompt","0.01","completion",0))).equals("paid"),"positive price recognized");
  for(String language:I18n.CODES){I18n.setLanguage(language);check(!ModelCatalog.concurrency(0).isBlank(),"localized unknown concurrency");}
  I18n.setLanguage("ro");
  try(ProviderTest.Catalog server=new ProviderTest.Catalog()){
   server.nativeApi=true;server.body=root;
   var live=ModelInfo.fetch(server.url(),"fixture","one",Provider.CUSTOM);check(live.parallel()==4&&live.loaded()==4096,"LM native GET exposes actual loaded instance");
   var catalog=ModelCatalog.fetch(Provider.CUSTOM,server.url(),"fixture");check(catalog.size()==2&&catalog.get(0).id().equals("one")&&catalog.get(0).parallel()==4&&catalog.get(1).id().equals("two")&&catalog.get(1).parallel()==2,"LM catalog preserves actual loaded instance aliases and their separate concurrency");
   check(server.requests.size()==2&&server.requests.stream().allMatch(r->r.startsWith("GET /api/v1/models ")),"LM metadata uses GET only, no inference or model loading");
  }
 }
 static void settings(Path home)throws Exception{
  Files.createDirectories(home);Path file=home.resolve("setari.properties");
  Properties original=new Properties();original.setProperty("workdir","first");original.setProperty("secret","synthetic-key");
  Properties a=new Properties(),b=new Properties();a.putAll(original);b.putAll(original);Properties abase=new Properties(),bbase=new Properties();abase.putAll(original);bbase.putAll(original);SettingsStore.save(file,original);
  a.setProperty("workdir","latest");SettingsStore.merge(file,a,abase);
  b.setProperty("ui.theme","dark");SettingsStore.merge(file,b,bbase);
  check(read(file).getProperty("workdir").equals("latest"),"background save preserves newer working folder");check(read(file).getProperty("secret").equals("synthetic-key"),"unmodified credentials retained");
  var provider=SavedProviders.save(a,"Delete fixture",Provider.CUSTOM,"http://127.0.0.1:9/v1","","model");SettingsStore.merge(file,a,abase);
  Properties stale=read(file),staleBase=new Properties();staleBase.putAll(stale);Properties updated=read(file),updatedBase=new Properties();updatedBase.putAll(updated);
  SavedProviders.remove(updated,provider);SettingsStore.merge(file,updated,updatedBase);stale.setProperty(provider.prefix()+".model","stale");SettingsStore.merge(file,stale,staleBase);
  check(read(file).stringPropertyNames().stream().noneMatch(k->k.startsWith(provider.prefix()+".")),"stale window cannot recreate deleted provider");
 }
 static void alternatives(Path home)throws Exception{
  Properties p=new Properties();var one=SavedProviders.save(p,"First",Provider.CUSTOM,"http://127.0.0.1:1/v1","","current");var two=SavedProviders.save(p,"Second",Provider.CUSTOM,"http://127.0.0.1:2/v1","","tool");
  var facts=ModelCatalog.entry(Json.obj("id","tool","supports_tool_calling",true,"pricing",Json.obj("prompt",0,"completion",0)),"http://127.0.0.1:2/v1");
  p.setProperty(two.prefix()+".catalogUrl","http://127.0.0.1:2/v1");p.setProperty(two.prefix()+".catalog",Json.write(List.of(facts.json())));
  var choices=ProviderAlternatives.choices(p,home,one,"current",true);
  check(choices.size()==1&&choices.get(0).selection().equals(two),"MBS suggestions require tool evidence and different available selection");
  check(choices.get(0).label().contains(ModelCatalog.costLabel(facts)),"zero price provenance visible");
  check(ProviderAlternatives.choices(p,home,one,"current",false).size()==1,"Normal alternative usable");
 }
 static void windows(Path home)throws Exception{
  Files.createDirectories(home);Path chosen=Files.createDirectories(home.resolve("chosen")),stale=Files.createDirectories(home.resolve("stale"));
  Properties p=new Properties();p.setProperty("workdir",chosen.toString());p.setProperty("default.workdir",stale.toString());p.setProperty("default.provider","CUSTOM");p.setProperty("tools","false");SettingsStore.save(home.resolve("setari.properties"),p);
  LocalChat[] first={null},second={null};Conversations.State oldState=new Conversations.State();oldState.toolsEnabled=false;
  Path old=Conversations.newFile(home.resolve("conversatii"));Conversations.save(old,chosen.toString(),List.of(ChatClient.Message.user("consult only")),oldState);
  try(FeatureTest.Server slow=new FeatureTest.Server(FeatureTest.json(FeatureTest.answer("first answer",21,5)));FeatureTest.Server fast=new FeatureTest.Server(FeatureTest.json(FeatureTest.answer("second answer",32,6)))){
   slow.delayMillis=3500;
   FeatureTest.edt(()->{
    System.setProperty("localchat.home",home.toString());var ctor=LocalChat.class.getDeclaredConstructor();ctor.setAccessible(true);first[0]=ctor.newInstance();
    check(FeatureTest.<JTextField>field(first[0],"workDirField").getText().equals(chosen.toString()),"startup uses latest folder rather than stale default");
    call(first[0],"newChat");check(FeatureTest.<JTextField>field(first[0],"workDirField").getText().equals(chosen.toString()),"new chat retains chosen folder");
    ProviderTest.set(first[0],Provider.CUSTOM,slow.url(),"first-model","");FeatureTest.<JCheckBox>field(first[0],"toolsBox").setSelected(false);FeatureTest.<JCheckBox>field(first[0],"streamBox").setSelected(false);
    AttachmentTest.input(first[0]).setText("first private prompt");call(first[0],"send");
   });
   for(int i=0;i<100&&slow.requests.isEmpty();i++)Thread.sleep(20);
   FeatureTest.edt(()->{
    check(FeatureTest.<Boolean>field(first[0],"busy"),"first running");
    AttachmentTest.input(first[0]).setText("first unsent draft");
    FeatureTest.call(first[0],"openSession",new Class<?>[]{Conversations.Saved.class},Conversations.load(old));
    List<LocalChat> windows=FeatureTest.field(first[0],"WINDOWS");second[0]=windows.get(windows.size()-1);
    check(second[0]!=first[0]&&FeatureTest.<Path>field(second[0],"conversationFile").equals(old),"busy navigation opens separate window");
    check(AttachmentTest.input(first[0]).getText().equals("first unsent draft"),"consultation preserves running draft");
    WorkspacePanel sidebar=FeatureTest.field(second[0],"workspace");Path runningFile=FeatureTest.field(first[0],"conversationFile");boolean marked=false;for(int i=0;i<sidebar.sessions.size();i++)if(sidebar.sessions.get(i).file().equals(runningFile)){JLabel label=(JLabel)sidebar.list.getCellRenderer().getListCellRendererComponent(sidebar.list,sidebar.sessions.get(i),i,false,false);marked=label.getFont().isBold()&&label.getToolTipText().equals(I18n.text("Chat in lucru — continua independent de selectie"));}check(marked,"running sidebar marker independent of selection");
    check(slow.requests.size()==1&&fast.requests.isEmpty(),"browsing never generates inference");
    ProviderTest.set(second[0],Provider.CUSTOM,fast.url(),"second-model","");FeatureTest.<JCheckBox>field(second[0],"streamBox").setSelected(false);
    AttachmentTest.input(second[0]).setText("second private prompt");call(second[0],"send");
   });
   FeatureTest.idle(second[0]);
   FeatureTest.edt(()->{
    check(FeatureTest.<Boolean>field(first[0],"busy"),"second completes while first still running");
    List<ChatClient.Message> messages=FeatureTest.field(second[0],"history");check(messages.stream().anyMatch(m->m.content.equals("second answer"))&&messages.stream().noneMatch(m->m.content.contains("first private")),"second history isolated");
    check(AttachmentTest.state(second[0]).last.input==32,"second usage isolated");
    second[0].dispose();
    check(FeatureTest.<Boolean>field(first[0],"busy"),"closing other chat leaves first running");
   });
   FeatureTest.idle(first[0]);
   FeatureTest.edt(()->{
    List<ChatClient.Message> messages=FeatureTest.field(first[0],"history");check(messages.stream().anyMatch(m->m.content.equals("first answer"))&&messages.stream().noneMatch(m->m.content.contains("second private")),"first history isolated");
    check(AttachmentTest.input(first[0]).getText().equals("first unsent draft"),"first pending draft survives completion");
    check(AttachmentTest.state(first[0]).last.input==21,"first usage isolated");
    check(fast.requests.size()==1&&slow.requests.size()==1,"exactly one request per chat; no replay");
   });
  }finally{FeatureTest.edt(()->{if(second[0]!=null)second[0].dispose();if(first[0]!=null)first[0].dispose();});}
 }
 static void routing(Path home)throws Exception{
  InferenceRetry.capacityBackoffMillis=10;Files.createDirectories(home);LocalChat[] app={null};try(FeatureTest.Server server=new FeatureTest.Server(new FeatureTest.Response(429,"application/json","{\"error\":\"rate limit\"}"),new FeatureTest.Response(429,"application/json","{\"error\":\"rate limit\"}"),new FeatureTest.Response(429,"application/json","{\"error\":\"rate limit\"}"))){
   FeatureTest.edt(()->{
    app[0]=AttachmentTest.window(home,server.url());FeatureTest.<JCheckBox>field(app[0],"toolsBox").setSelected(false);
    Properties p=FeatureTest.field(app[0],"config");var saved=SavedProviders.save(p,"Alternative",Provider.CUSTOM,"http://127.0.0.1:9/v1","","alternate");
    call(app[0],"saveConfig");AttachmentTest.input(app[0]).setText("retry original");call(app[0],"send");
   });FeatureTest.idle(app[0]);FeatureTest.edt(()->{});FeatureTest.edt(()->{
    ComposerAssist card=FeatureTest.field(app[0],"composerAssist");check(card.waiting(),"429 offers explicit alternatives");
    List<ProviderAlternatives.Choice> choices=FeatureTest.field(app[0],"alternativeChoices");var option=choices.stream().filter(c->c.model().equals("alternate")).findFirst().orElseThrow();
    Map<String,String> answers=FeatureTest.field(card,"answers");answers.put("provider-alternative",option.label());FeatureTest.call(app[0],"submitInteraction",new Class<?>[]{String.class},"unused");
    check(FeatureTest.<JComboBox<?>>field(app[0],"modelBox").getSelectedItem().equals("alternate"),"explicit card selects alternate model");
    check(AttachmentTest.input(app[0]).getText().equals("retry original"),"choice preserves restored user draft");
    check(server.requests.size()==3&&!FeatureTest.<Boolean>field(app[0],"busy"),"429: three bounded attempts, choice never resends failed task automatically");
   });
  }finally{FeatureTest.edt(()->{if(app[0]!=null)app[0].dispose();});}
 }
 static void sharedMemory(Path home)throws Exception{
  Properties p=new Properties();LocalMemories a=new LocalMemories(home,p),b=new LocalMemories(home,p);
  Thread one=new Thread(()->{try{for(int i=0;i<12;i++)a.add("123456","project fact a "+i,"manual","a");}catch(Exception e){throw new RuntimeException(e);}});
  Thread two=new Thread(()->{try{for(int i=0;i<12;i++)b.add("234567","project fact b "+i,"manual","b");}catch(Exception e){throw new RuntimeException(e);}});
  one.start();two.start();one.join();two.join();check(a.all().size()==24,"parallel windows preserve all memory writes");
 }
 static void ownership(Path home)throws Exception{
  Files.createDirectories(home);LocalChat[] first={null},child={null};
  FeatureTest.edt(()->{try{
   first[0]=AttachmentTest.window(home,"http://127.0.0.1:9/v1");AttachmentTest.input(first[0]).setText("main draft");call(first[0],"ensureSession");call(first[0],"saveConversation");
   FeatureTest.<Conversations.State>field(first[0],"sessionState").factoryProject="123456";FeatureTest.call(first[0],"setBusy",new Class<?>[]{boolean.class},true);
   call(first[0],"newChat");List<LocalChat> windows=FeatureTest.field(first[0],"WINDOWS");child[0]=windows.get(windows.size()-1);
   check(child[0]!=first[0]&&!FeatureTest.<Boolean>field(child[0],"busy"),"busy new chat creates independent idle session");
   check(AttachmentTest.input(first[0]).getText().equals("main draft"),"new session preserves original pending text");
   FeatureTest.<JCheckBox>field(child[0],"toolsBox").setSelected(true);
   boolean blocked=(boolean)FeatureTest.call(child[0],"parallelBlocked",new Class<?>[]{String.class,Path.class},"Continua proiectul 123456",home);
   check(blocked,"same project cannot mutate in two MBS windows");
   check(!(boolean)FeatureTest.call(child[0],"parallelBlocked",new Class<?>[]{String.class,Path.class},"Continua proiectul 234567",home),"different confirmed projects can run independently");
   Path owned=FeatureTest.field(first[0],"conversationFile");int count=windows.size();
   FeatureTest.call(child[0],"openSession",new Class<?>[]{Conversations.Saved.class},Conversations.load(owned));
   check(windows.size()==count&&FeatureTest.<Path>field(child[0],"conversationFile")==null,"owned conversation focuses original without opening second writer");
   FeatureTest.call(first[0],"setBusy",new Class<?>[]{boolean.class},false);
  }finally{if(child[0]!=null)child[0].dispose();if(first[0]!=null)first[0].dispose();}});
 }
 public static void main(String[] args)throws Exception{Path home=Path.of(System.getProperty("localchat.home"));try{metadata();settings(home.resolve("settings"));alternatives(home.resolve("alternatives"));windows(home.resolve("windows"));routing(home.resolve("routing"));sharedMemory(home.resolve("memory"));ownership(home.resolve("ownership"));System.out.println("Concurrent chats: "+checks+" checks passed");}finally{FeatureTest.edt(()->{for(java.awt.Window window:java.awt.Window.getWindows())window.dispose();});}}
}
