package localchat;
import java.nio.file.*;
import java.util.*;
public final class PdfAgentTest {
    static int checks;static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    static String request(String id,String name,Map<String,Object> args){return Json.write(Json.obj("choices",List.of(Json.obj("message",Json.obj("content",null,"tool_calls",List.of(Json.obj("id",id,"type","function","function",Json.obj("name",name,"arguments",Json.write(args))))))),"usage",Json.obj("prompt_tokens",100,"completion_tokens",10)));}
    static Agent.Ui ui(){return new Agent.Ui(){public void text(String s){}public void step(){}public void toolStarted(ChatClient.ToolCall c){}public void toolFinished(ChatClient.ToolCall c,Tools.Result r){}};}
    static long jobs(Path project)throws Exception{try(var paths=Files.list(project.resolve("Controle/Octop-PDFCONTROL"))){return paths.filter(p->Files.isRegularFile(p.resolve("job.json"))).count();}}
    public static void main(String[] args)throws Exception{
        Path pack=Path.of(System.getProperty("pdfcontrol.package")),root=Path.of(System.getProperty("pdfcontrol.projects")),project=root.resolve("123456");Mcp mcp=new Mcp();
        try{
            mcp.reload(Json.obj("mcpServers",Json.obj("pdfcontrol",Json.obj("command",pack.resolve("runtime/python/python.exe").toString(),"args",List.of("-I",pack.resolve("octop_mcp.py").toString()),"env",Json.obj("PDFCONTROL_PACKAGE",pack.toString(),"PDFCONTROL_PROJECTS_ROOT",root.toString()),"cwd",pack.toString()))),s->s);
            check(mcp.servers().size()==1,"real owned MCP connected");
            Tools tools=new Tools(new Tools.Context(project,project.resolve("skills"),project.resolve("reguli.md"),project.resolve("settings.properties"),Map.of(),null,mcp,PathMap.none(),true,EnumSet.allOf(Tools.Access.class),(a,b)->Tools.Decision.NO),List.of());
            Map<String,Object> valid=Json.obj("project","123456","files",List.of("loose/123456_WA224_V.pdf","loose/123456_WA133_W.pdf"));
            long before=jobs(project);
            try(var model=new FeatureTest.Server(FeatureTest.json(request("one","pdfcontrol_start_pairs",valid)),FeatureTest.json(request("two","pdfcontrol_start_pairs",valid)),FeatureTest.json(FeatureTest.answer("Finished",100,10)))){
                Agent agent=new Agent(tools);agent.run(Provider.CUSTOM,model.url(),"","synthetic-model","test","system",List.of(ChatClient.Message.user("Check selected drawings")),false,ui());
                var archive=agent.collect("","test");var results=archive.stream().filter(m->m.role.equals("tool")).toList();
                check(results.size()==2,"both calls have evidence");Object first=Json.parse(results.get(0).content),second=Json.parse(results.get(1).content);
                check("done".equals(Json.str(first,"status"))&&Boolean.TRUE.equals(Json.get(first,"delivery_verified")),"model receives verified terminal delivery, not a pending receipt");
                check(Json.str(first,"job_id").equals(Json.str(second,"job_id")),"duplicate request reuses exact completed job");
                check(jobs(project)==before+1,"only one engine job was started");
            }
            Map<String,Object> invalid=Json.obj("project","123456","files",List.of("does-not-exist.pdf"));
            try(var model=new FeatureTest.Server(FeatureTest.json(request("a","pdfcontrol_start_pairs",invalid)),FeatureTest.json(request("b","pdfcontrol_start_pairs",invalid)),FeatureTest.json(request("c","pdfcontrol_start_pairs",invalid)))){
                Agent agent=new Agent(tools);try{agent.run(Provider.CUSTOM,model.url(),"","synthetic-model","test","system",List.of(ChatClient.Message.user("Check drawings")),false,ui());throw new AssertionError("repeated failure did not stop");}catch(java.io.IOException expected){check(expected.getMessage().contains("PDFCONTROL"),"repeated failure reports actual blocked workflow");}
                check(agent.collect("","test").stream().filter(m->m.role.equals("tool")&&m.isError).count()==3,"all failed calls remain paired in archive");
                check(jobs(project)==before+1,"invalid requests never create substitute jobs");
            }
            System.out.println("PASS: "+checks+" PDF agent checks; synthetic model, real owned engine, verified delivery, deduplication and error-loop stop");
        }finally{mcp.closeAll();}System.exit(0);
    }
}
