package localchat;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
/** Exact job evidence supplies a corrective hint, never silent redirection or new access. */
public final class PdfResultPathTest {
    static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static Tools tools(Path root,boolean auto,AtomicInteger asks){return new Tools(new Tools.Context(root,root.resolve("skills"),root.resolve("rules.md"),root.resolve("settings.properties"),Map.of(),null,new Mcp(),PathMap.none(),auto,EnumSet.noneOf(Tools.Access.class),(a,b)->{asks.incrementAndGet();return Tools.Decision.NO;}),List.of());}
    static Tools.Result read(Tools tool,Path path){return tool.execute(new ChatClient.ToolCall("read","read_file",Json.obj("path",path.toString())));}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("localchat-result-path-"),run=Files.createDirectories(root.resolve("123456/Controle/Octop-PDFCONTROL/"+"a".repeat(32))),correct=run.resolve("engine/checked/resultaten.json"),wrong=run.resolve("checked/resultaten.json"),job=run.resolve("job.json");Files.createDirectories(correct.getParent());Files.writeString(correct,"{\"verified\":true}");
        var record=Json.obj("job_id","a".repeat(32),"status","done","results",correct.toString());Files.writeString(job,Json.write(record));var asks=new AtomicInteger();var tool=tools(root,false,asks);
        try{for(String language:I18n.CODES){I18n.setLanguage(language);var hint=read(tool,wrong);check(hint.isError&&hint.text.contains(correct.toString())&&hint.text.contains("pdfcontrol_job_status"),"all languages return exact corrective path with error status");check(!hint.text.contains("verified"),"hint does not silently read or redirect file contents");check(!Files.exists(wrong),"no fabricated alias or directory created");}
            check(asks.get()==0,"workspace diagnostic needs no external access");check(read(tool,correct).text.contains("verified"),"explicit corrected read succeeds normally");
            for(String status:List.of("running","failed","cancelled")){record.put("status",status);Files.writeString(job,Json.write(record));check(!read(tool,wrong).text.contains(correct.toString()),"no completed evidence claimed for "+status);}
            record.put("status","done");record.put("results",root.resolve("another.json").toString());Files.writeString(job,Json.write(record));check(!read(tool,wrong).text.contains(correct.toString()),"wrong manifest path is not trusted");
            record.put("results",correct.toString());record.put("job_id","b".repeat(32));Files.writeString(job,Json.write(record));check(!read(tool,wrong).text.contains(correct.toString()),"other job evidence rejected");
            record.put("job_id","a".repeat(32));Files.writeString(job,Json.write(record));Path outside=Files.createDirectories(root.resolve("selected-workspace"));var restricted=tools(outside,false,asks);var denied=read(restricted,wrong);check(denied.isError&&!denied.text.contains(correct.toString())&&asks.get()==1,"outside-workspace denial never discloses alternative path");
            var explicit=tools(outside,false,asks).attachments(List.of(wrong.toString()));check(!read(explicit,wrong).text.contains(correct.toString()),"a single attached wrong file grants no sibling evidence access");
            Files.delete(job);check(!read(tool,wrong).text.contains(correct.toString()),"missing manifest has ordinary missing-file result");check(!read(tool,root.resolve("ordinary-missing.json")).text.contains("pdfcontrol_job_status"),"unrelated missing file is unaffected");
            System.out.println("PASS: "+checks+" PDF result-path diagnostics checks; six languages, exact job evidence and access boundaries");
        }finally{I18n.setLanguage("ro");try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
}
