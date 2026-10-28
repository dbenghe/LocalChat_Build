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
            check(tools.specs().stream().noneMatch(s->s.name.equals("pdfcontrol_live_status")||s.description.startsWith("[MCP")),"1.27.1: no duplicate live-status alias and no [MCP] prefix in the tool list");
            check(!tools.execute(new ChatClient.ToolCall("live","pdfcontrol_live_status",Json.obj())).isError,"1.27.1: archived pdfcontrol_live_status calls still run through pdfcontrol_status");
            Map<String,Object> valid=Json.obj("project","123456","files",List.of("loose/123456_WA224_V.pdf","loose/123456_WA133_W.pdf"));
            long before=jobs(project);String[] finished=new String[1];
            try(var model=new FeatureTest.Server(FeatureTest.json(request("one","pdfcontrol_start_pairs",valid)),FeatureTest.json(request("two","pdfcontrol_start_pairs",valid)),FeatureTest.json(FeatureTest.answer("Finished",100,10)))){
                Agent agent=new Agent(tools);agent.run(Provider.CUSTOM,model.url(),"","synthetic-model","test","system",List.of(ChatClient.Message.user("Check selected drawings")),false,ui());
                var archive=agent.collect("","test");var results=archive.stream().filter(m->m.role.equals("tool")).toList();
                check(results.size()==2,"both calls have evidence");Object first=Json.parse(results.get(0).content),second=Json.parse(results.get(1).content);
                check("done".equals(Json.str(first,"status"))&&Boolean.TRUE.equals(Json.get(first,"delivery_verified")),"model receives verified terminal delivery, not a pending receipt");
                check(Json.str(first,"job_id").equals(Json.str(second,"job_id")),"duplicate request reuses exact completed job");
                check(jobs(project)==before+1,"only one engine job was started");finished[0]=Json.str(first,"job_id");
            }
            // 1.28.0: compact read-only readers over the real job output, through the real MCP bridge.
            Map<String,Object> idArgs=Json.obj("project","123456","job_id",finished[0]);
            try(var model=new FeatureTest.Server(FeatureTest.json(request("s","pdfcontrol_job_summary",idArgs)),FeatureTest.json(request("f","pdfcontrol_job_findings",Json.obj("project","123456","job_id",finished[0],"offset",0,"max_chars",1500))),FeatureTest.json(FeatureTest.answer("Read",100,10)))){
                Agent agent=new Agent(tools.readOnly(true).analysisOnly());agent.run(Provider.CUSTOM,model.url(),"","synthetic-model","test","system",List.of(ChatClient.Message.user("Summarise the last check")),false,ui());
                var read=agent.collect("","test").stream().filter(m->m.role.equals("tool")).toList();check(read.size()==2&&read.stream().noneMatch(m->m.isError),"1.28.0: summary and findings work in read-only analysis mode");
                Object summary=Json.parse(read.get(0).content),findings=Json.parse(read.get(1).content);
                check("done".equals(Json.str(summary,"status"))&&Boolean.TRUE.equals(Json.get(summary,"delivery_verified"))&&Json.str(summary,"report")!=null,"1.28.0: summary carries state, delivery_verified and the report path of the real job");
                check(read.get(0).content.length()<3500&&read.get(1).content.length()<2500&&Json.get(findings,"total")!=null&&Json.get(findings,"items") instanceof List,"1.28.0: outputs are compact and paginated");
                check(jobs(project)==before+1,"1.28.0: readers never start or change jobs");
            }
            Set<String> readers=Set.of("pdfcontrol_job_summary","pdfcontrol_job_findings","pdfcontrol_list_projects","pdfcontrol_catalogue_status","pdfcontrol_status");
            for(Tools restricted:List.of(tools.readOnly(true),tools.analysisOnly())){var names=new HashSet<String>();for(var spec:restricted.specs())names.add(spec.name);check(names.containsAll(readers)&&names.stream().noneMatch(n->n.startsWith("pdfcontrol_start_")),"1.28.0: read-only and analysis profiles expose the readers and no PDFCONTROL starter");}
            var status=tools.execute(new ChatClient.ToolCall("st","pdfcontrol_status",Json.obj()));String statusText=status.text;
            check(!status.isError&&!statusText.contains("server_pid")&&!statusText.contains("trace_file")&&!statusText.contains("adapter_file")&&!statusText.contains(pack.toString().replace("\\","\\\\"))&&!statusText.contains(pack.toString()),"1.28.0: model-facing status has no PIDs, trace or package paths");
            check(!tools.readOnly(true).execute(new ChatClient.ToolCall("ro","pdfcontrol_job_summary",idArgs)).isError&&tools.readOnly(true).execute(new ChatClient.ToolCall("ro2","pdfcontrol_start_check",valid)).isError,"1.28.0: readers run read-only while starters stay blocked");
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
