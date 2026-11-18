package localchat;
import java.nio.file.*;
import java.util.*;

/** Execute the real Agent/HTTP/file host, including unsupported final, real failure and Stop. */
public final class HostResultRecoveryTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static FeatureTest.Response answer(String value){return FeatureTest.json(FeatureTest.answer(value,10,5));}
    static final String CLAIM="Conectorul LocalChat nu a mai returnat rezultate; este necesara repornirea aplicatiei.";
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("host-result-recovery-");InteractionRecoveryTest.streaming=false;
        Files.writeString(root.resolve("source.txt"),"ACTUAL_SOURCE");
        for(String mode:List.of("recover","pending","real-error","bounded","stop","normal")){
            Path home=Files.createDirectory(root.resolve(mode));Files.writeString(home.resolve("source.txt"),"ACTUAL_SOURCE");
            var replies=new ArrayList<FeatureTest.Response>();
            replies.add(AgentStepLimitTest.call(1,"read_file",Json.obj("path",mode.equals("real-error")?"missing.txt":"source.txt")));
            replies.add(answer(mode.equals("normal")?"Checked, no missing technical dimensions can be confirmed.":mode.equals("pending")?"Cautarea detaliata in catalog este in asteptare, fara rezultat returnat.":CLAIM));
            if(mode.equals("recover")||mode.equals("pending")){replies.add(AgentStepLimitTest.call(2,"write_file",Json.obj("path","proof.txt","content","VERIFIED_OUTPUT")));replies.add(answer("Done; output verified."));}
            if(mode.equals("bounded"))replies.add(answer(CLAIM));
            try(var server=new FeatureTest.Server(replies.toArray(FeatureTest.Response[]::new))){
                Agent agent=new Agent(AgentStepLimitTest.tools(home));
                var ui=new AgentStepLimitTest.Ui(){public void discoveryRecovery(boolean last){continuations++;if(mode.equals("stop"))agent.cancel();}};
                AgentStepLimitTest.run(agent,server,ui,false);
                check(server.requests.size()==((mode.equals("recover")||mode.equals("pending"))?4:mode.equals("bounded")?3:2),"bounded request count: "+mode);
                check(ui.continuations==((mode.equals("normal")||mode.equals("real-error"))?0:1),"exactly one correction or none: "+mode);
                if(mode.equals("bounded"))check(agent.recoveryIncomplete(),"repeated unsupported final is partial, never reported as completed");
                if(mode.equals("recover")||mode.equals("pending")){
                    check(Files.readString(home.resolve("proof.txt")).equals("VERIFIED_OUTPUT"),"actual authorized work resumes after unsupported failure claim");
                    check(Json.write(server.requests.get(2)).contains("No missing-result"),"wire correction cites successful host delivery");
                    check(ui.operations==2,"read and write each execute once, no replay");
                    check(agent.collect("","test").stream().anyMatch(m->m.content.equals("Done; output verified.")),"final answer preserved in transcript");
                }
            }
        }
        for(String text:List.of("Nu pot confirma dimensiunea lipsa.","The file really failed CRC verification.","Raport salvat; verificarea este partiala."))check(!HostResultRecovery.needed(text,List.of(ChatClient.Message.toolResult("x","read succeeded",false))),"technical uncertainty remains unchanged");
        check(!HostResultRecovery.needed(CLAIM,List.of(ChatClient.Message.toolResult("x","User denied",true))),"denial is never overridden");
        System.out.println("PASS: "+checks+" host-result recovery checks");System.exit(0);
    }
}
