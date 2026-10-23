package localchat;
import java.nio.file.*;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Synthetic screenshots and loopback HTTP only; no private images or provider inference. */
public final class VisionInputTest {
 static int checks;
 static void check(boolean yes,String name){checks++;if(!yes)throw new AssertionError(name);}
 static Object payload(String method,ChatClient.Message message)throws Exception {var m=ChatClient.class.getDeclaredMethod(method,String.class,String.class,List.class,List.class,boolean.class);m.setAccessible(true);return m.invoke(null,"vision-fixture","system",List.of(message),List.of(),false);}
 static void failure(Runnable run,String label){boolean failed=false;try{run.run();}catch(RuntimeException e){failed=true;}check(failed,label);}
 public static void main(String[] args)throws Exception {
  Path home=Files.createTempDirectory("vision-input-"),secret=home.resolve("settings.properties"),png=home.resolve("capture.png"),folder=Files.createDirectories(home.resolve("folder"));Files.writeString(secret,"private fixture");
  BufferedImage image=new BufferedImage(96,48,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();g.drawString("FAST error",2,20);g.dispose();ImageIO.write(image,"png",png.toFile());Files.copy(png,folder.resolve("not-selected.png"));
  List<ImageInput.Ref> refs=ImageInput.snapshot(List.of(png.toString(),folder.toString()),home.resolve("snapshots"),secret);
  check(refs.size()==1,"no directory enumeration");byte[] bytes=Files.readAllBytes(png);
  var question=ChatClient.Message.user("Interpret this error").withImages(refs).withDisplayLanguage("nl").withRequestControl("language control");
  check(question.images.size()==1,"language decorators preserve images");
  String archive=Json.write(question.toJson());check(!archive.contains("base64")&&!archive.contains(Base64.getEncoder().encodeToString(bytes)),"archive stores references only");
  var restored=ChatClient.Message.fromJson(Json.parse(archive));check(restored.images.equals(refs)&&restored.requestControl.isEmpty(),"archive restores references but no wire control");
  Object open=payload("openAiBody",question);Object block=Json.get(((List<?>)Json.get(open,"messages")).get(1),"content");String data=Json.str(((List<?>)block).get(1),"image_url","url");
  check(data.startsWith("data:image/png;base64,")&&Arrays.equals(bytes,Base64.getDecoder().decode(data.split(",",2)[1])),"OpenAI image pixels encoded exactly");
  Object ant=payload("anthropicBody",question);Object antBlocks=Json.get(((List<?>)Json.get(ant,"messages")).get(0),"content");Object source=Json.get(((List<?>)antBlocks).get(1),"source");check("image/png".equals(Json.str(source,"media_type"))&&Arrays.equals(bytes,Base64.getDecoder().decode(Json.str(source,"data"))),"Anthropic native image blocks");
  ContextGuard guard=new ContextGuard(32000,2048,true,70,1);guard.memory=new ContextGuard.Memory("Evidence summarized",1,restored.content,restored.images);check(guard.view(List.of(restored)).get(1).images.equals(refs),"in-turn compaction retains current image");
  ContextMemory memory=new ContextMemory();memory.summary="Evidence summarized";memory.through=1;memory.question=restored.content;check(memory.view(List.of(restored)).get(1).images.equals(refs),"saved compaction view restores original question image without reading bytes");
  memory.start=1;check(memory.view(List.of(restored)).isEmpty(),"mode epoch does not recover images from another mode");
  Files.writeString(png,"external source changed");check(refs.get(0).data().equals(Base64.getEncoder().encodeToString(bytes)),"snapshot survives changed original");
  check(ContextMemory.messages(List.of(question))>=4096,"estimated image context included");
  check(ChatClient.Message.fromJson(Json.obj("role","user","content","legacy reference")).images.isEmpty(),"legacy chats require explicit reattachment");
  Path fake=home.resolve("fake.png");Files.writeString(fake,"not an image");try{ImageInput.snapshot(List.of(fake.toString()),home.resolve("snapshots"),secret);throw new AssertionError("invalid image accepted");}catch(java.io.IOException expected){checks++;}
  try{ImageInput.snapshot(Collections.nCopies(5,folder.resolve("not-selected.png").toString()),home.resolve("snapshots"),secret);throw new AssertionError("too many images");}catch(java.io.IOException expected){checks++;}
  // Exercise actual composer -> persisted question -> HTTP request with tools disabled.
  try(var server=new FeatureTest.Server(FeatureTest.json(FeatureTest.answer("Screenshot interpreted",30,5)))) {
   LocalChat[] app={null};FeatureTest.edt(()->{app[0]=AttachmentTest.window(home.resolve("app"),server.url());FeatureTest.<JCheckBox>field(app[0],"toolsBox").setSelected(false);AttachmentTest.add(app[0],folder.resolve("not-selected.png"));AttachmentTest.input(app[0]).setText("What is the error?");FeatureTest.call(app[0],"send",new Class<?>[]{});});FeatureTest.idle(app[0]);
   check(server.requests.size()==1,"normal composer sends one request");String wire=Json.write(server.requests.get(0));check(wire.contains("data:image/png;base64,")&&!wire.contains("\"tools\":"),"Normal image payload has pixels and no MBS tools");
   FeatureTest.edt(()->{check(AttachmentTest.state(app[0]).draftAttachments.isEmpty(),"accepted attachments clear only own draft");app[0].dispose();});
  }
  // An image remains in the same conversation after a tool result.
  try(var server=new FeatureTest.Server(FeatureTest.json(FeatureTest.answer("after tool",50,5)))) {
   List<ChatClient.Message> conversation=List.of(restored,ChatClient.Message.assistant("",List.of(new ChatClient.ToolCall("read","read_file",Json.obj("path","fixture"))),"fixture"),ChatClient.Message.toolResult("read","fixture evidence",false));
   new ChatClient().chat(Provider.CUSTOM,server.url(),"","vision-fixture","",conversation,List.of(),false,delta->{});
   check(Json.write(server.requests.get(0)).contains("data:image/png;base64,"),"tool follow-up preserves image payload");
  }
  try(var server=new FeatureTest.Server()) {
   LocalChat[] app={null};FeatureTest.edt(()->{app[0]=AttachmentTest.window(home.resolve("no-vision"),server.url());Properties config=FeatureTest.field(app[0],"config");Object selection=FeatureTest.field(app[0],"shownSelection");String prefix=SavedProviders.prefix(selection);config.setProperty(prefix+".modelsUrl",server.url());config.setProperty(prefix+".catalogUrl",server.url());config.setProperty(prefix+".models",Json.write(List.of("attachment-fixture")));config.setProperty(prefix+".catalog",Json.write(List.of(ModelCatalog.entry(Json.obj("id","attachment-fixture","capabilities",Json.obj("vision",false)),server.url()).json())));FeatureTest.<JCheckBox>field(app[0],"toolsBox").setSelected(false);AttachmentTest.add(app[0],folder.resolve("not-selected.png"));AttachmentTest.input(app[0]).setText("Keep this draft");FeatureTest.call(app[0],"send",new Class<?>[]{});check(AttachmentTest.input(app[0]).getText().equals("Keep this draft")&&AttachmentTest.state(app[0]).draftAttachments.size()==1,"unsupported model preserves draft and image");app[0].dispose();});check(server.requests.isEmpty(),"explicitly unsupported model blocked before inference");
  }
  Path alias=home.resolve("secret.png");Files.createLink(alias,secret);try{ImageInput.snapshot(List.of(alias.toString()),home.resolve("snapshots"),secret);throw new AssertionError("settings hard link accepted");}catch(java.io.IOException expected){checks++;}
  Object unrelated=payload("openAiBody",ChatClient.Message.user("Different chat"));check(!Json.write(unrelated).contains("image_url"),"unrelated chat never receives image");
  Files.writeString(Path.of(refs.get(0).path()),"tampered");failure(()->refs.get(0).data(),"changed snapshot rejected");
  System.out.println("PASS: "+checks+" vision checks; composer, two protocols, archive, tool continuation, snapshots, no external API");System.exit(0);
 }
}
