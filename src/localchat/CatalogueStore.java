package localchat;
import java.io.*;import java.nio.charset.StandardCharsets;import java.nio.file.*;import java.util.*;import java.util.concurrent.TimeUnit;

/** Local catalogue maintenance. No inference, no conversation/settings writes. */
final class CatalogueStore {
 static final List<String> FACTORIES=List.of("soest","alphen","veenoord","lelystad");
 record Preview(String factory,Path source,String sourceHash,String targetHash,Map<String,Object> data){}
 final Path app,python;final boolean readOnly;
 CatalogueStore(Path app,Path python,boolean readOnly){this.app=app.toAbsolutePath().normalize();this.python=python;this.readOnly=readOnly;}
 Path snapshot(String factory){if(!FACTORIES.contains(factory))throw new IllegalArgumentException("Unknown factory");return app.resolve("pdfcontrol/data_lokaal/catalogus_"+factory+".json");}
 Map<String,Object> info(String factory)throws IOException{
  Path path=snapshot(factory);if(!Files.exists(path))return Json.obj("exists",false);
  Map<String,Object> data=object(Files.readString(path));if(!factory.equals(Json.str(data,"factory")))throw new IOException(I18n.text("Catalogul existent apartine altei fabrici."));
  Object raw=Json.get(data,"records");if(!(raw instanceof List<?> records))throw new IOException(I18n.text("Catalog invalid."));
  return Json.obj("exists",true,"records",records.size(),"verified_at",Json.str(data,"verified_at"),"source",Json.str(data,"source"),"snapshot",path.toString());
 }
 Preview preview(String factory,Path source)throws IOException{
  Map<String,Object> data=execute(factory,source,List.of("--details"));
  return new Preview(factory,source.toAbsolutePath().normalize(),Json.str(data,"source_sha256"),Json.str(data,"target_sha256"),Collections.unmodifiableMap(data));
 }
 Map<String,Object> apply(Preview preview,boolean confirmedFactory,boolean allowAbsent)throws IOException{
  if(readOnly)throw new IOException(I18n.text("Mod doar citire: modificarile si comenzile sunt blocate."));
  if(!confirmedFactory)throw new IOException(I18n.text("Confirma fabrica fisierului inainte de import."));
  if(number(preview.data(),"absent_codes")>0&&!allowAbsent)throw new IOException(I18n.text("Confirma lista completa pentru codurile absente."));
  List<String> options=new ArrayList<>(List.of("--apply","--expected-source-sha256",preview.sourceHash(),"--expected-target-sha256",preview.targetHash()));if(allowAbsent)options.add("--accept-absent");
  return execute(preview.factory(),preview.source(),options);
 }
 private Map<String,Object> execute(String factory,Path source,List<String> options)throws IOException{
  Path target=snapshot(factory);if(!Files.isRegularFile(source)||!source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xlsx"))throw new IOException(I18n.text("Alege un catalog XLSX."));
  Path script=app.resolve("pdfcontrol/catalogue_update.py");if(!Files.isRegularFile(python)||!Files.isRegularFile(script))throw new IOException(I18n.text("Utilitarul local de cataloage lipseste."));
  List<String> command=new ArrayList<>(List.of(python.toString(),"-I",script.toString(),"--source",source.toAbsolutePath().toString(),"--factory",factory,"--target",target.toString(),"--engine",app.resolve("pdfcontrol").toString()));command.addAll(options);
  ProcessBuilder builder=new ProcessBuilder(command).directory(app.toFile()).redirectErrorStream(true);builder.environment().put("PYTHONIOENCODING","utf-8");
  Process process=builder.start();ByteArrayOutputStream bytes=new ByteArrayOutputStream();
  Thread reader=new Thread(()->{try(InputStream input=process.getInputStream()){byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1)synchronized(bytes){if(bytes.size()<8_000_000)bytes.write(buffer,0,n);}}catch(IOException ignored){}},"catalogue-output");reader.setDaemon(true);reader.start();
  try{
   if(!process.waitFor(120,TimeUnit.SECONDS)){process.destroyForcibly();throw new IOException(I18n.text("Actualizarea a depasit timpul de asteptare. Verifica starea catalogului."));}
   reader.join(3000);String output;synchronized(bytes){output=bytes.toString(StandardCharsets.UTF_8).strip();}
   Map<String,Object> data=object(output);if(process.exitValue()!=0||Json.get(data,"error")!=null)throw new IOException(I18n.text(Objects.toString(Json.get(data,"error"),"Catalog invalid.")));
   return data;
  }catch(InterruptedException error){Thread.currentThread().interrupt();process.destroyForcibly();throw new IOException(I18n.text("Actualizarea a depasit timpul de asteptare. Verifica starea catalogului."),error);}
 }
 @SuppressWarnings("unchecked") private static Map<String,Object> object(String json)throws IOException{try{Object parsed=Json.parse(json);if(parsed instanceof Map<?,?>)return (Map<String,Object>)parsed;}catch(RuntimeException ignored){}throw new IOException(I18n.text("Catalog invalid."));}
 static long number(Object data,String key){Object value=Json.get(data,key);return value instanceof Number n?n.longValue():0;}
}
