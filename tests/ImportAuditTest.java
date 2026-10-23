package localchat;
import java.nio.file.*;import java.util.*;import java.util.concurrent.atomic.*;import javax.swing.*;
/** Real Python subprocess + synthetic CSV/JSON only; no model connection. */
public final class ImportAuditTest {
 static int checks;static void check(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
 static Tools tools(Path root,boolean automatic,Tools.Permission permission){return new Tools(new Tools.Context(root,root.resolve("skills"),root.resolve("rules.md"),root.resolve("settings.properties"),Map.of(),System.getProperty("audit.python"),new Mcp(),PathMap.none(),automatic,EnumSet.noneOf(Tools.Access.class),permission),List.of());}
 static Tools.Result call(Tools tools,Map<String,Object> args){return tools.execute(new ChatClient.ToolCall("audit","pdfcontrol_compare_import",args));}
 public static void main(String[] args)throws Exception{
  Path root=Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("localchat.home"))),"audit-");Path engine=Files.createDirectories(root.resolve("pdfcontrol"));Path source=Path.of(System.getProperty("audit.package"));
  for(String file:List.of("import_audit.py","mbs_artikelcatalogus.py"))Files.copy(source.resolve(file),engine.resolve(file));
  Path catalogue=engine.resolve("data_lokaal/catalogus_veenoord.json");Files.createDirectories(catalogue.getParent());Files.writeString(catalogue,Json.write(Json.obj("factory","veenoord","records",List.of(Json.obj("code","MBS1","description","Fixture anchor","category","import","fields",Json.obj("eenheid","st"))))));
  Path input=root.resolve("import.csv");String original="Merk;Instortvoorziening;Aantal;Eenheid;Lengte\nTR01;Fixture anchor;2;st;\n";Files.writeString(input,original);
  var request=Json.obj("project","123456","factory","veenoord","imports",List.of(input.toString()));
  Tools tool=tools(root,true,(a,b)->Tools.Decision.NO);check(Tools.BUILTIN.contains("pdfcontrol_compare_import"),"builtin audit registered");check(tool.specs().stream().anyMatch(s->s.name.equals("pdfcontrol_compare_import")),"audit available to selected MBS models");
  var first=call(tool,request);check(!first.isError,"real Python audit succeeds: "+first.text);Object parsed=Json.parse(first.text);check(Json.str(parsed,"factory").equals("veenoord"),"explicit factory preserved");check(((Number)Json.get(parsed,"api_requests")).intValue()==0,"no model inference");check(Files.isRegularFile(Path.of(Json.str(parsed,"report_html"))),"HTML report exists");check(Files.readString(input).equals(original),"import source preserved exactly");check(first.text.length()<6000,"tool result bounded instead of table dump");
  var second=call(tool,request);check(!second.isError&&Boolean.TRUE.equals(Json.get(Json.parse(second.text),"cached")),"second identical audit reuses local report");
  AtomicInteger asks=new AtomicInteger();Tools denied=tools(root,false,(a,b)->{asks.incrementAndGet();return Tools.Decision.NO;});check(call(denied,request).isError&&asks.get()==1,"report-write denial honored");
  check(call(tools(root,true,(a,b)->Tools.Decision.NO).readOnly(true),request).isError,"readOnly blocks report generation");
  var invalid=new LinkedHashMap<>(request);invalid.put("factory","unknown");check(call(tool,invalid).isError,"invalid factory refused before process");invalid=new LinkedHashMap<>(request);invalid.put("command","not allowed");check(call(tool,invalid).isError,"unknown program fields rejected");
  Path secret=root.resolve("settings.properties");Files.writeString(secret,"secret fixture");Path alias=root.resolve("secret.csv");Files.createLink(alias,secret);invalid=new LinkedHashMap<>(request);invalid.put("imports",List.of(alias.toString()));check(call(tool,invalid).isError,"protected hardlink input refused");
  Path outside=Files.createTempDirectory(root.getParent(),"outside-").resolve("list.csv");Files.writeString(outside,original);invalid=new LinkedHashMap<>(request);invalid.put("imports",List.of(outside.toString()));AtomicInteger readAsks=new AtomicInteger();check(call(tools(root,false,(a,b)->{readAsks.incrementAndGet();return Tools.Decision.NO;}),invalid).isError&&readAsks.get()==1,"external file read requires permission");
  AtomicReference<Tools> late=new AtomicReference<>();late.set(tools(root,false,(a,b)->{late.get().cancel();return Tools.Decision.YES;}));check(call(late.get(),request).isError,"late approval after Stop never starts worker");
  for(String lang:I18n.CODES)for(boolean dark:List.of(false,true)){
   I18n.setLanguage(lang);if(dark)com.formdev.flatlaf.FlatDarkLaf.setup();else com.formdev.flatlaf.FlatLightLaf.setup();
   SwingUtilities.invokeAndWait(()->{ImportAuditDialog dialog=new ImportAuditDialog(null,root,tools(root,true,(a,b)->Tools.Decision.NO));dialog.project.setText("123456");dialog.factory.setSelectedIndex(3);dialog.runAudit();check(dialog.status.getText().equals(I18n.text("Alege proiectul, fabrica si cel putin o lista de import.")),"direct UI validates before any request");check(dialog.start.getText().equals(I18n.text("Compara local listele de import")),"direct UI translated");if(lang.equals("ro")){try{dialog.setVisible(true);dialog.validate();var image=new java.awt.image.BufferedImage(dialog.getWidth(),dialog.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);var graphics=image.createGraphics();dialog.getRootPane().printAll(graphics);graphics.dispose();javax.imageio.ImageIO.write(image,"png",root.resolve(dark?"audit-dark.png":"audit-light.png").toFile());}catch(Exception error){throw new RuntimeException(error);}}dialog.dispose();});
  }
  I18n.setLanguage("ro");System.out.println("PASS: "+checks+" offline import audit integration checks");System.exit(0);
 }
}
