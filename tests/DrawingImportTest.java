package localchat;
import java.nio.file.*;import java.util.*;import java.util.concurrent.atomic.*;import java.util.zip.*;import java.nio.charset.StandardCharsets;
/** Actual local PDF -> catalogue -> XLSX host calls and approvals, no model/account. */
public final class DrawingImportTest {
 static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static void part(ZipOutputStream z,String name,String text)throws Exception{z.putNextEntry(new ZipEntry(name));z.write(text.getBytes(StandardCharsets.UTF_8));z.closeEntry();}
 static Tools.Result call(Tools tools,Map<String,Object> args){return tools.execute(new ChatClient.ToolCall("drawing","pdfcontrol_drawing_import",args));}
 public static void main(String[] args)throws Exception{
  Path root=Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("localchat.home"))),"drawing-");Path engine=Files.createDirectories(root.resolve("pdfcontrol")),source=Path.of(System.getProperty("audit.package"));
  try(var files=Files.list(source)){for(Path file:files.filter(p->p.toString().endsWith(".py")).toList())Files.copy(file,engine.resolve(file.getFileName()));}
  Path cat=engine.resolve("data_lokaal/catalogus_soest.json");Files.createDirectories(cat.getParent());Files.writeString(cat,Json.write(Json.obj("factory","soest","records",List.of(Json.obj("code","MBS1","description","Fixture anchor M20","category","import","fields",Json.obj("eenheid","st"))))));
  Path pdf=root.resolve("123456_TR01_V.pdf"),template=root.resolve("template.xlsx"),output=root.resolve("Output/draft.xlsx");
  String code="import sys,pymupdf;d=pymupdf.open();p=d.new_page(width=800,height=600);p.insert_text((50,40),'Merk: TR01');[(p.insert_text((x,100),t)) for x,t in [(50,'Posnr'),(150,'Benaming'),(600,'Aantal')]];[(p.insert_text((x,125),t)) for x,t in [(50,'H01'),(150,'Fixture anchor M20'),(600,'3')]];p.insert_text((623,125),'st',fontname='hebo');d.save(sys.argv[1]);d.close()";
  Process fixture=new ProcessBuilder(System.getProperty("audit.python"),"-I","-c",code,pdf.toString()).redirectErrorStream(true).start();String log=new String(fixture.getInputStream().readAllBytes(),StandardCharsets.UTF_8);check(fixture.waitFor()==0,"PDF fixture created: "+log);
  try(var zip=new ZipOutputStream(Files.newOutputStream(template))){
   part(zip,"xl/workbook.xml","<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Import\" sheetId=\"1\" r:id=\"s\"/></sheets></workbook>");
   part(zip,"xl/_rels/workbook.xml.rels","<Relationships><Relationship Id=\"s\" Target=\"worksheets/sheet1.xml\"/></Relationships>");
   StringBuilder data=new StringBuilder("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData><row r=\"1\">");int n=0;for(String h:List.of("Merk","Instortvoorziening","Instortvoorziening Merk","Aantal","Eenheid","Lengte","Tekenbureau","Status tekening","Hoofdregel"))data.append("<c r=\"").append((char)('A'+n++)).append("1\" t=\"inlineStr\"><is><t>").append(h).append("</t></is></c>");part(zip,"xl/worksheets/sheet1.xml",data+"</row></sheetData></worksheet>");
  }
  var request=Json.obj("project","123456","factory","soest","drawings",List.of(pdf.toString()),"template",template.toString(),"output",output.toString(),"hoofdregel","70","ocr",false);
  AtomicInteger approvals=new AtomicInteger();Tools host=ImportAuditTest.tools(root,false,(a,b)->{approvals.incrementAndGet();return Tools.Decision.YES;});
  check(Tools.BUILTIN.contains("pdfcontrol_drawing_import"),"builtin drawing import registered");check(host.specs().stream().anyMatch(s->s.name.equals("pdfcontrol_drawing_import")),"tool offered to MBS models");
  var result=call(host,request);check(!result.isError,"real local tool: "+result.text);Object summary=Json.parse(result.text);check(Files.isRegularFile(output),"actual XLSX output exists");check(approvals.get()==1,"host write approval required");check(Boolean.FALSE.equals(Json.get(summary,"ready_for_isah")),"draft not a production approval");check(((Number)Json.get(summary,"articles")).intValue()==1,"real PDF table extracted");check(((Number)Json.get(summary,"api_requests")).intValue()==0,"worker has no model request");
  String report=Files.readString(Path.of(Json.str(summary,"report_json")));check(report.contains("Fixture anchor M20")&&report.contains("MBS1"),"factory mapping and source evidence stored");
  var reviewed=new LinkedHashMap<>(request);reviewed.put("output",root.resolve("Output/reviewed.xlsx").toString());reviewed.put("reviewed_rows",List.of(Json.obj("merk","TR01","fields",Json.obj("Aantal","2","Lengte","1000","Breedte","800","Dikte","150"),"evidence","Synthetic drawing section A and project quantity decision")));reviewed.put("geometry",List.of(Json.obj("merk","TR01","evidence","Synthetic rectangular prism confirmed by section A","components",List.of(Json.obj("shape","box","dimensions_mm",List.of("1000","800","150"))))));
  var reviewedResult=call(host,reviewed);check(!reviewedResult.isError,"reviewed bulk values and geometry through host: "+reviewedResult.text);String reviewedReport=Files.readString(Path.of(Json.str(Json.parse(reviewedResult.text),"report_json")));check(reviewedReport.contains("0.12")&&reviewedReport.contains("model_review_not_independent_validation"),"local volume and review evidence stored");
  var mapped=new LinkedHashMap<>(request);mapped.put("output",root.resolve("Output/mapped.xlsx").toString());mapped.put("column_map",Json.obj("Inhoud","Netto m3"));check(!call(host,mapped).isError,"explicit column mapping accepted by host");
  var retry=call(host,request);check(!retry.isError&&Boolean.TRUE.equals(Json.get(Json.parse(retry.text),"cached")),"completed identical work reused");
  check(call(ImportAuditTest.tools(root,true,(a,b)->Tools.Decision.YES).readOnly(true),request).isError,"read-only cannot generate output/report");
  var outside=new LinkedHashMap<>(request);outside.put("output",root.getParent().resolve("outside.xlsx").toString());check(call(host,outside).isError,"output cannot escape work folder");
  var unknown=new LinkedHashMap<>(request);unknown.put("factory","unknown");check(call(host,unknown).isError,"explicit valid factory required");
  var extra=new LinkedHashMap<>(request);extra.put("command","never");check(call(host,extra).isError,"untrusted executable field rejected");
  var extract=new LinkedHashMap<>(request);extract.remove("template");extract.remove("output");
  check(!call(host,extract).isError,"extraction-only mode has no XLSX requirement");
  var hidden=ImportAuditTest.tools(root,true,(a,b)->Tools.Decision.YES).selective(Set.of());check(hidden.specs().stream().noneMatch(s->s.name.equals("pdfcontrol_drawing_import")),"generation hidden until files group needed");check(ToolRelevance.FILES.equals(ToolRelevance.group("pdfcontrol_drawing_import")),"files group unlocks generation");
  for(String lang:I18n.CODES)check(I18n.keys(lang).contains("Extrage articolele din desene"),"translated action "+lang);
  System.out.println("DrawingImportTest: "+checks+" checks passed; real PDF/catalogue/XLSX, guards and cache");System.exit(0);
 }
}
