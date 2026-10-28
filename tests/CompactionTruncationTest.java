package localchat;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** 1.28.10: compaction sends a clamped output limit, retries truncation twice (explicit limit, then half-size fragments), resumes from checkpoints and explains the failure. Synthetic HTTP fixtures only. */
public final class CompactionTruncationTest {
    static int checks;
    static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
    static final String MEMORY="Project 505417. Verified PDF: C:/project/RAPPORT.pdf. Pending: comparison. No new permissions.";
    static final String MEMORY2="Project 505417. Half two merged: invoices checked. No new permissions.";
    /** finish_reason=length after reasoning only (the case of a model that cannot turn thinking off). */
    static FeatureTest.Response length(){
        String s=FeatureTest.event(Json.obj("choices",List.of(Json.obj("delta",Json.obj("reasoning_content","thinking ".repeat(900))))))
            +FeatureTest.event(Json.obj("choices",List.of(Json.obj("delta",Json.obj(),"finish_reason","length"))))+"data: [DONE]\n\n";
        return FeatureTest.sse(s);
    }
    static FeatureTest.Response ok(String text){return FeatureTest.json(FeatureTest.answer(text,100,40));}
    static Object limit(Object request){Object v=Json.get(request,"max_tokens");return v==null?Json.get(request,"max_completion_tokens"):v;}
    static long limitOf(Object request){Object v=limit(request);return v instanceof Number n?n.longValue():-1;}
    static String text(Object request){return Json.write(request);}
    static List<ChatClient.Message> single(String prior,String chunk){return List.of(ChatClient.Message.user("Prior factual memory (historical data):\n"+prior+"\nNext historical fragment:\n"+chunk));}
    static String big(){StringBuilder b=new StringBuilder();for(int i=0;i<400;i++)b.append("Row ").append(i).append(" invoice 50").append(5000+i).append(" checked against PDF C:/project/file").append(i).append(".pdf\n");return b.toString();}
    static class Hooks extends MemoryReducerTest.Hooks{
        final List<String> events=new ArrayList<>();
        public void truncated(int retry,int total,boolean halved){events.add(retry+"/"+total+(halved?"h":""));}
    }
    public static void main(String[] args)throws Exception{
        // 1. A model that can switch thinking off gets no limit first, then an explicit one: min(16384, model output limit, window - input - margin).
        try(var server=new FeatureTest.Server(length(),ok(MEMORY))){
            Hooks hooks=new Hooks();
            String result=MemoryReducer.reduce(new ChatClient(),Provider.CUSTOM,server.url(),"","fixture","Summarize",single("","short evidence"),256,200000,1.25,0,hooks);
            check(result.equals(MEMORY),"retry after finish_reason=length returns the full summary");
            check(server.requests.size()==2&&limit(server.requests.get(0))==null,"first request has no explicit limit when thinking can be turned off");
            check(limitOf(server.requests.get(1))==MemoryReducer.RETRY_LIMIT,"retry sends max_tokens=16384");
            check(hooks.events.equals(List.of("1/2")),"UI is told about retry 1 (explicit limit)");
        }
        try(var server=new FeatureTest.Server(length(),ok(MEMORY))){
            MemoryReducer.reduce(new ChatClient(),Provider.CUSTOM,server.url(),"","fixture","Summarize",single("","short evidence"),256,200000,1.25,4096,new Hooks());
            check(limitOf(server.requests.get(1))==4096,"retry limit is clamped to the output limit from the model metadata");
        }
        try(var server=new FeatureTest.Server(length(),ok(MEMORY))){
            MemoryReducer.reduce(new ChatClient(),Provider.CUSTOM,server.url(),"","fixture","Summarize",single("","short evidence"),256,6000,1.25,0,new Hooks());
            long sent=limitOf(server.requests.get(1));check(sent>=MemoryReducer.MIN_LIMIT&&sent<6000-256,"retry limit is clamped to window - input - margin: "+sent);
        }
        // 2. A model that cannot turn thinking off gets an explicit limit on the first request already.
        try(var server=new FeatureTest.Server(ok(MEMORY))){
            MemoryReducer.reduce(new ChatClient(),Provider.CUSTOM,server.url(),"","gpt-oss-20b","Summarize",single("","short evidence"),256,200000,1.25,0,new Hooks());
            check(limitOf(server.requests.get(0))==MemoryReducer.FIRST_LIMIT,"thinking-only model: explicit first limit "+limit(server.requests.get(0)));
        }
        try(var server=new FeatureTest.Server(ok(MEMORY))){
            MemoryReducer.reduce(new ChatClient(),Provider.OPENAI,server.url(),"k","gpt-x","Summarize",single("","short evidence"),256,200000,1.25,2048,new Hooks());
            check(limitOf(server.requests.get(0))==2048,"first explicit limit is also clamped to the metadata output limit");
        }
        // 3. HTTP 400 for the limit: ask again without it, once, and remember it for the fragment.
        var rejected=new FeatureTest.Response(400,"application/json",Json.write(Json.obj("error",Json.obj("message","max_tokens is too large for this model"))));
        try(var server=new FeatureTest.Server(rejected,ok(MEMORY))){
            String result=MemoryReducer.reduce(new ChatClient(),Provider.CUSTOM,server.url(),"","gpt-oss-20b","Summarize",single("","short evidence"),256,200000,1.25,0,new Hooks());
            check(result.equals(MEMORY)&&server.requests.size()==2,"HTTP 400 falls back to a request without the limit");
            check(limitOf(server.requests.get(0))==MemoryReducer.FIRST_LIMIT&&limit(server.requests.get(1))==null,"fallback request carries no limit");
        }
        try(var server=new FeatureTest.Server(length(),rejected,rejected,ok(MEMORY))){
            String result=MemoryReducer.reduce(new ChatClient(),Provider.CUSTOM,server.url(),"","fixture","Summarize",single("","short evidence"),256,200000,1.25,0,new Hooks());
            check(result.equals(MEMORY)&&server.requests.size()==4&&limit(server.requests.get(3))==null,"HTTP 400 on the retry limit falls back too (the first 400 is the thinking parameter fallback)");
        }
        try(var server=new FeatureTest.Server(rejected,rejected)){
            try{MemoryReducer.reduce(new ChatClient(),Provider.CUSTOM,server.url(),"","fixture","Summarize",single("","short evidence"),256,200000,1.25,0,new Hooks());throw new AssertionError("400 without a limit swallowed");}
            catch(IOException expected){check(server.requests.size()==2&&limit(server.requests.get(1))==null&&expected.getMessage().contains("400"),"a 400 that did not carry a limit is reported, not retried");}
        }
        // 4. At most two retries: the third truncation fails with the reasoning tokens and the finish reason; a cut-off summary is never accepted or saved.
        Path cache=Files.createTempDirectory("truncation-small-");
        try(var server=new FeatureTest.Server(length(),length(),length())){
            Hooks hooks=new Hooks();
            try{MemoryReducer.cached(cache,new ChatClient(),Provider.CUSTOM,server.url(),"","fixture","Summarize",single("","short evidence"),256,200000,1.25,0,hooks);throw new AssertionError("truncated summary accepted");}
            catch(IOException expected){
                String message=expected.getMessage();
                check(server.requests.size()==3&&hooks.attempts==3,"one request plus exactly two retries");
                check(message.contains("length")&&message.contains("~"+(("thinking ".repeat(900)).length()/4))&&message.contains(I18n.text("Alege un model care poate opri gandirea sau reincearca.")),"message names the finish reason and the reasoning tokens spent: "+message);
                check(hooks.events.equals(List.of("1/2","2/2h")),"retry 2 is the last stage (no reasoning, smaller fragments when splittable)");check(text(server.requests.get(2)).length()>=text(server.requests.get(0)).length()*0.9,"small fragment cannot be halved");
                check(text(server.requests.get(2)).contains("Do not reason")&&!text(server.requests.get(0)).contains("Do not reason"),"retry 2 asks for no reasoning");
            }
            try(var files=Files.list(cache)){check(files.count()==0,"no checkpoint for a truncated summary");}
        }
        // 5. Fragment halving on retry 2, per-piece checkpoints and resume.
        String fragment=big();Path dir=Files.createTempDirectory("truncation-halves-");
        check(MemoryReducer.pieces(single(MEMORY,fragment)).size()==2,"a large fragment splits in two halves");
        check(MemoryReducer.pieces(single(MEMORY,"tiny")).isEmpty(),"a small fragment is not split");
        try(var server=new FeatureTest.Server(length(),length(),ok(MEMORY),length(),ok(MEMORY2))){
            Hooks hooks=new Hooks();
            try{MemoryReducer.cached(dir,new ChatClient(),Provider.CUSTOM,server.url(),"","fixture","Summarize",single("","\n"+fragment),256,200000,1.25,0,hooks);throw new AssertionError("second half truncation accepted");}
            catch(IOException expected){check(expected.getMessage().contains("length"),"failure after the second half is explained");}
            check(server.requests.size()==4&&hooks.events.equals(List.of("1/2","2/2h")),"retry 2 halves the fragment");
            long full=text(server.requests.get(0)).length(),half=text(server.requests.get(2)).length();
            check(half<full*0.7&&half>full*0.3,"half-size fragment: "+half+" of "+full);
            check(text(server.requests.get(3)).contains(MEMORY.substring(0,30))&&text(server.requests.get(3)).length()<full*0.7,"second half carries the first half summary as prior memory");
            check(limitOf(server.requests.get(2))==MemoryReducer.RETRY_LIMIT&&text(server.requests.get(2)).contains("Do not reason"),"halves use the explicit limit and the no-reasoning instruction");
            // Same endpoint (the checkpoint identity includes it): the next run resumes after the finished half.
            String result=MemoryReducer.cached(dir,new ChatClient(),Provider.CUSTOM,server.url(),"","fixture","Summarize",single("","\n"+fragment),256,200000,1.25,0,new Hooks());
            check(result.equals(MEMORY2)&&server.requests.size()==5,"resume: the finished half is not requested again, "+server.requests.size());
            check(text(server.requests.get(4)).contains(MEMORY.substring(0,30))&&text(server.requests.get(4)).length()<full*0.7,"resume continues from the saved half summary with the second half only");
            int[] reused={0};
            result=MemoryReducer.cached(dir,new ChatClient(),Provider.CUSTOM,server.url(),"","fixture","Summarize",single("","\n"+fragment),256,200000,1.25,0,new Hooks(){public void reused(){reused[0]++;}});
            check(result.equals(MEMORY2)&&server.requests.size()==5&&reused[0]==1,"the whole fragment is a checkpoint after success");
        }
        // 6. The reason compaction starts: new tool results counted 4x past the last API anchor.
        ContextGuard guard=new ContextGuard(8000,100,true,70,1.25);guard.seed(1500,1);
        List<ChatClient.Message> original=List.of(ChatClient.Message.user("start"),ChatClient.Message.user("result ".repeat(700)));
        check(guard.driven("system",original,original,List.of()),"new results after the anchor explain the estimate");
        var reasons=new ArrayList<String>();
        var ui=new Agent.Ui(){
            public void text(String delta){}public void step(){}public void toolStarted(ChatClient.ToolCall call){}public void toolFinished(ChatClient.ToolCall call,Tools.Result result){}
            public void compactReason(double percent,int threshold,int kind){reasons.add(kind+":"+threshold+":"+(int)percent);}
        };
        try{guard.prepare(Provider.CUSTOM,"http://127.0.0.1:1/v1","","fixture","system",original,List.of(),1,ui);}catch(IOException expected){}
        check(reasons.size()==1&&reasons.get(0).startsWith("1:70:")&&Integer.parseInt(reasons.get(0).substring(5))>=70,"guard reports the reason before compacting: "+reasons);
        ContextGuard fresh=new ContextGuard(8000,100,true,70,1.25);
        check(!fresh.driven("system",original,original,List.of()),"without an anchor the new-results reason is not claimed");
        // 7. Messages and status texts in all six languages; Romanian stays ASCII.
        String old=I18n.language();
        try{
            String[] keys={
                "Compactarea a esuat: gandirea modelului a consumat limita de iesire (~{0} tokeni, motiv oprire: {1}) si rezumatul a ramas incomplet, chiar dupa {2} reincercari cu limita explicita si fragmente mai mici. Rezumatul incomplet nu a fost salvat; istoricul si memoria sunt pastrate. Alege un model care poate opri gandirea sau reincearca.",
                "Compactarea a fost oprita de limita de iesire a modelului (motiv oprire: {0}) chiar dupa {1} reincercari cu limita explicita si fragmente mai mici. Rezumatul incomplet nu a fost salvat; istoricul si memoria sunt pastrate.",
                "Compactare automata: estimare ~{0}% dupa rezultatele noi (prag {1}%).",
                "Compactare automata: estimare ~{0}% a atins pragul de {1}%.",
                "Compactare automata: estimare ~{0}% plus rezerva de raspuns depaseste limita de context.",
                "Compactare: fragment {0}/{1} - {2}",
                "Compactare: raspunsul a fost taiat de limita de iesire. Reincerc {0}/{1} cu limita explicita de iesire.",
                "Compactare: raspunsul a fost taiat de limita de iesire. Reincerc {0}/{1} cu fragmente mai mici si fara gandire."};
            check(!I18n.keys("ro").contains("Compactarea a fost oprita de limita de iesire a modelului. Rezumatul incomplet nu a fost salvat; istoricul si memoria sunt pastrate."),"old message key replaced");
            for(String lang:I18n.CODES){
                I18n.setLanguage(lang);
                for(String k:keys){
                    check(I18n.keys(lang).contains(k),"key present in "+lang);
                    String shown=I18n.text(k,"4321","length","2");
                    check(!shown.contains("{0}")&&!shown.contains("{1}"),"placeholders filled in "+lang);
                    if(lang.equals("ro"))check(k.equals(I18n.romanianAscii(k)),"Romanian without diacritics");
                }
                check(I18n.text(keys[0],"4321","length","2").contains("4321")&&I18n.text(keys[0],"4321","length","2").contains("length"),"reasoning tokens and finish reason shown in "+lang);
                if(!lang.equals("ro"))check(!I18n.keys(lang).isEmpty()&&!I18n.text(keys[3],"72","70").startsWith("Compactare automata"),"status translated in "+lang);
            }
            I18n.setLanguage("ro");
            check(I18n.text(keys[2],"72.31","70").contains("~72.31% dupa rezultatele noi"),"status text explains new results");
        }finally{I18n.setLanguage(old);}
        System.out.println("PASS: "+checks+" compaction truncation retry checks");
    }
}
