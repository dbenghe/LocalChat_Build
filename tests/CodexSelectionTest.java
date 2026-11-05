package localchat;
import java.nio.file.*;import java.time.*;import java.util.*;import javax.swing.*;
public final class CodexSelectionTest {
 static int checks;static void check(boolean ok,String reason){checks++;if(!ok)throw new AssertionError(reason);}
 public static void main(String[] args)throws Exception{try{
  Path home=Files.createDirectories(Path.of(System.getProperty("localchat.home")));Path file=home.resolve("models.json");
  Files.writeString(file,Json.write(Json.obj("fetched_at",Instant.now().toString(),"identity","fixture","models",List.of(Json.obj("slug","fixture","context_window",500000,"effective_context_window_percent",95)))));
  var info=CodexMetadata.read(file,"fixture");check(info.maximum()==500000,"native catalogue maximum");check(info.effective()==475000,"native percentage, not guessed from name");check(CodexMetadata.read(file,"other").maximum()==0,"exact model identity");
  Files.writeString(file,Json.write(Json.obj("fetched_at",Instant.now().minusSeconds(8*86400).toString(),"models",List.of(Json.obj("slug","fixture","context_window",500000)))));check(CodexMetadata.read(file,"fixture").maximum()==0,"expired metadata unknown");Files.writeString(file,"bad JSON");check(CodexMetadata.read(file,"fixture").maximum()==0,"bad metadata unknown");
  check(CodexMetadata.info("runtime-fixture").loaded()==0,"catalogue not presented as observed loaded window");CodexMetadata.observe("runtime-fixture",475000);check(CodexMetadata.info("runtime-fixture").loaded()==475000,"native runtime window observed");CodexMetadata.observe("runtime-fixture",0);check(CodexMetadata.info("runtime-fixture").loaded()==475000,"zero never destroys valid observation");
  var names=List.of("gpt-6.1-sol","gpt-6.1-terra","gpt-6-astra","gpt-6-sol","gpt-6-luna","gpt-5.6-sol","gpt-5.6-terra","gpt-5.6-luna");
  var entries=names.stream().map(id->new ModelCatalog.Entry(id,true,true,true,0,0,CodexConnection.SOURCE,"","chat",true)).toList();Properties config=new Properties();config.setProperty("codex.catalogUrl",CodexConnection.ROUTE);config.setProperty("codex.catalog",Json.write(entries.stream().map(ModelCatalog.Entry::json).toList()));var restored=ModelCatalog.saved(config,"codex",CodexConnection.ROUTE,names);
  var cache=new ModelCatalog.Cache(home,"native",Provider.CODEX,CodexConnection.ROUTE,"");
  for(var row:entries){check(ModelSuitability.assess(row,cache.get(row.id()),CodexConnection.ROUTE,true).allowed(),"old chat category allowed MBS "+row.id());check(ModelSuitability.assess(row,cache.get(row.id()),CodexConnection.ROUTE,false).allowed(),"old category allowed Normal "+row.id());}
  for(var row:restored){check(!ModelSuitability.specialized(row),"saved category repaired");check(row.source().startsWith("Codex native"),"source preserved, no fake HTTP endpoint");}
  final Throwable[] failed={null};SwingUtilities.invokeAndWait(()->{LocalChat app=null;try{
   var ctor=LocalChat.class.getDeclaredConstructor();ctor.setAccessible(true);app=ctor.newInstance();var show=LocalChat.class.getDeclaredMethod("showProvider",Object.class);show.setAccessible(true);show.invoke(app,Provider.CODEX);
   var cf=LocalChat.class.getDeclaredField("config");cf.setAccessible(true);Properties settings=(Properties)cf.get(app);settings.putAll(config);settings.setProperty("codex.modelsUrl",CodexConnection.ROUTE);settings.setProperty("codex.models",Json.write(names));
   var mf=LocalChat.class.getDeclaredField("modelBox");mf.setAccessible(true);@SuppressWarnings("unchecked")JComboBox<String> box=(JComboBox<String>)mf.get(app);box.setSelectedItem(names.get(0));
   var refresh=LocalChat.class.getDeclaredMethod("refreshDashboard");refresh.setAccessible(true);refresh.invoke(app);check(box.getItemCount()==8,"actual MBS selector keeps all eight models");
   for(String lang:I18n.CODES)for(boolean mbs:new boolean[]{true,false}){
    I18n.setLanguage(lang);List<String> chosen=new ArrayList<>();var dialog=new ModelCatalogDialog(app,"Codex",restored,cache,Provider.CODEX,CodexConnection.ROUTE,"",false,e->chosen.add(e.id()),v->{},()->{});dialog.configureProfile(mbs,true,v->{});check(dialog.visible.size()==8,"filtered catalogue all eight "+lang+mbs);
    for(int row=0;row<8;row++){dialog.table.setRowSelectionInterval(row,row);check(dialog.choose.isEnabled(),"choose enabled "+lang+mbs+row);check(dialog.selectedTest.isEnabled()&&dialog.toolsTest.isEnabled(),"manual tests enabled "+lang+mbs+row);}
    dialog.choose.doClick();check(chosen.size()==1,"actual choose action delivered "+lang+mbs);dialog.dispose();
   }
  }catch(Throwable error){failed[0]=error;}finally{if(app!=null)app.dispose();}});if(failed[0]!=null)throw new Exception(failed[0]);
  System.out.println("CodexSelectionTest: "+checks+" checks passed; persisted catalogue, MBS/Normal selection and context provenance");
 }finally{SwingUtilities.invokeAndWait(()->{for(var w:java.awt.Window.getWindows())w.dispose();});}System.exit(0);}
}
