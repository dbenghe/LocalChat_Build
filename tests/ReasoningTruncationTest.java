package localchat;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.*;

/** 1.27.3: reasoning stream indicator, thinking control with 400 fallback, adaptive output reserve and truncation messages (fake SSE server only, no real inference). */
public final class ReasoningTruncationTest {
    static int checks;
    static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    static String ev(Object o){return FeatureTest.event(o);}
    static Object delta(Object d){return Json.obj("choices",List.of(Json.obj("delta",d)));}
    static Object finish(String reason){return Json.obj("choices",List.of(Json.obj("delta",Json.obj(),"finish_reason",reason)));}
    static final String DONE="data: [DONE]\n\n";
    static ChatClient.Reply request(ChatClient c,FeatureTest.Server s,Provider p,ChatClient.Listener l)throws Exception{return c.chat(p,s.url(),"","qwen-test","",List.of(ChatClient.Message.user("hi")),List.of(),true,l);}
    static final class Seen implements ChatClient.Listener{final StringBuilder text=new StringBuilder(),reasoning=new StringBuilder();public void onText(String d){text.append(d);}public void onReasoning(String d){reasoning.append(d);}}
    static Tools tools(Path home)throws Exception{Path project=Files.createDirectories(home.resolve("project"));return new Tools(new Tools.Context(project,home.resolve("skills"),home.resolve("rules.md"),home.resolve("settings.properties"),Map.of(),null,new Mcp(),PathMap.none(),true,EnumSet.noneOf(Tools.Access.class),(a,b)->Tools.Decision.NO),List.of());}

    static void reasoningStream(Path home)throws Exception{
        ThinkingControl.forget();
        String stream=ev(delta(Json.obj("reasoning_content","SECRET-THINK-A")))+ev(delta(Json.obj("reasoning_content","B")))+ev(delta(Json.obj("content","Visible")))+ev(finish("stop"))
            +ev(Json.obj("choices",List.of(),"usage",Json.obj("prompt_tokens",30,"completion_tokens",9,"completion_tokens_details",Json.obj("reasoning_tokens",7))))+DONE;
        try(var server=new FeatureTest.Server(FeatureTest.sse(stream))){ChatClient c=new ChatClient();Seen seen=new Seen();ChatClient.Reply r=request(c,server,Provider.CUSTOM,seen);
            check(seen.reasoning.toString().equals("SECRET-THINK-AB")&&seen.text.toString().equals("Visible"),"reasoning deltas reach the listener separately from the answer");
            check(r.text.equals("Visible")&&r.reasoning.equals("SECRET-THINK-AB"),"reply keeps answer and reasoning apart");
            check(c.usage().reasoning()==7&&c.usage().complete(),"reasoning_tokens counted from usage");
            check(Json.get(server.requests.get(0),"stream")!=null&&Boolean.TRUE.equals(Json.get(server.requests.get(0),"stream")),"streaming request sent");
            check(Json.get(server.requests.get(0),ThinkingControl.PARAMETER)==null,"automatic mode sends no thinking parameter");}
        // Agent: reasoning reaches Ui.reasoning but is never resent in the next request.
        String toolStep=ev(delta(Json.obj("reasoning_content","SECRET-THINK-TOOL")))+ev(delta(Json.obj("tool_calls",List.of(Json.obj("index",0,"id","c1","type","function","function",Json.obj("name","read_file","arguments",Json.write(Json.obj("path","missing-1273.txt"))))))))+ev(finish("tool_calls"))+DONE;
        String last=ev(delta(Json.obj("reasoning_content","more thinking")))+ev(delta(Json.obj("content","Final answer")))+ev(finish("stop"))+DONE;
        try(var server=new FeatureTest.Server(FeatureTest.sse(toolStep),FeatureTest.sse(last))){Agent agent=new Agent(tools(home.resolve("a1")));StringBuilder ui=new StringBuilder(),answer=new StringBuilder();
            agent.run(Provider.CUSTOM,server.url(),"","qwen-test","label","",List.of(ChatClient.Message.user("go")),true,new Agent.Ui(){public void text(String s){answer.append(s);}public void reasoning(String s){ui.append(s);}public void step(){}public void toolStarted(ChatClient.ToolCall c){}public void toolFinished(ChatClient.ToolCall c,Tools.Result r){}});
            check(ui.toString().contains("SECRET-THINK-TOOL")&&ui.toString().contains("more thinking"),"Ui receives reasoning deltas during streaming");
            check(answer.toString().contains("Final answer")&&!answer.toString().contains("SECRET"),"reasoning never shown as answer text");
            check(server.requests.size()==2&&!Json.write(server.requests.get(1)).contains("SECRET-THINK-TOOL"),"reasoning is not resent in the next request");
            check(agent.collect("","label").stream().noneMatch(m->String.valueOf(m.content).contains("SECRET-THINK")),"reasoning never stored in the archive");}
    }

    static void truncation(Path home)throws Exception{
        String reasonOnly=ev(delta(Json.obj("reasoning_content","x".repeat(4000))))+ev(finish("length"))+DONE;
        String partialCall=ev(delta(Json.obj("reasoning_content","y".repeat(400))))+ev(delta(Json.obj("tool_calls",List.of(Json.obj("index",0,"id","t1","type","function","function",Json.obj("name","write_file","arguments","{\"path\":\"must-not-exist-1273.txt\",\"content\":\"unsa"))))))+ev(finish("length"))+DONE;
        String noReasoning=ev(delta(Json.obj("content","partial text")))+ev(finish("length"))+DONE;
        Agent.Ui forbidden=new Agent.Ui(){public void text(String s){}public void step(){}public void toolStarted(ChatClient.ToolCall c){throw new AssertionError("Truncated call executed");}public void toolFinished(ChatClient.ToolCall c,Tools.Result r){throw new AssertionError("Truncated call executed");}};
        for(String language:I18n.CODES){I18n.setLanguage(language);
            try(var server=new FeatureTest.Server(FeatureTest.sse(reasonOnly))){Agent agent=new Agent(tools(home.resolve("t-"+language)));String message="";
                try{agent.run(Provider.CUSTOM,server.url(),"","qwen-test","label","",List.of(ChatClient.Message.user("go")),true,forbidden);throw new AssertionError("truncation accepted");}catch(IOException expected){message=expected.getMessage();}
                check(message.contains("~1000")&&!message.contains("{0}"),"reasoning-consumed message carries the spent tokens "+language);
                check(language.equals("ro")||!message.equals(Agent.truncationMessage(new ChatClient.Reply("",List.of(),"length","x".repeat(4000)),TokenUsage.UNKNOWN).replace("~1000","~1000X")),"message formatted "+language);
                check(!message.startsWith("Modelul a folosit")||language.equals("ro"),"reasoning-consumed message localized "+language);
                check(server.requests.size()==1,"truncation is never retried");}
        }
        I18n.setLanguage("en");
        try(var server=new FeatureTest.Server(FeatureTest.sse(partialCall))){Agent agent=new Agent(tools(home.resolve("t2")));String message="";
            try{agent.run(Provider.CUSTOM,server.url(),"","qwen-test","label","",List.of(ChatClient.Message.user("go")),true,forbidden);throw new AssertionError("truncated call accepted");}catch(IOException expected){message=expected.getMessage();}
            check(message.contains("thinking")&&!Files.exists(home.resolve("t2").resolve("project").resolve("must-not-exist-1273.txt")),"truncated tool call after reasoning never executes");}
        try(var server=new FeatureTest.Server(FeatureTest.sse(noReasoning))){Agent agent=new Agent(tools(home.resolve("t3")));String message="";
            try{agent.run(Provider.CUSTOM,server.url(),"","qwen-test","label","",List.of(ChatClient.Message.user("go")),true,forbidden);throw new AssertionError("truncation accepted");}catch(IOException expected){message=expected.getMessage();}
            check(message.toLowerCase(Locale.ROOT).contains("truncated")&&!message.contains("thinking"),"plain truncation keeps the generic message");}
        check(Agent.reasoningSpent(new ChatClient.Reply("",List.of(),"length","z".repeat(800)),TokenUsage.UNKNOWN)==200,"reasoning estimate ~4 chars per token without usage");
        check(Agent.reasoningSpent(new ChatClient.Reply("",List.of(),"length","z".repeat(8)),new TokenUsage(1,300,0,0,true,300))==300,"provider reasoning count wins when larger");
        I18n.setLanguage("ro");
    }

    static void fallback()throws Exception{
        String ok=ev(delta(Json.obj("content","fine")))+ev(finish("stop"))+DONE;
        FeatureTest.Response unknown=new FeatureTest.Response(400,"application/json","{\"error\":{\"message\":\"Unrecognized request argument supplied: reasoning_effort\"}}");
        ThinkingControl.forget();AtomicInteger changes=new AtomicInteger();ThinkingControl.onChange(changes::incrementAndGet);
        try(var server=new FeatureTest.Server(unknown,FeatureTest.sse(ok),FeatureTest.sse(ok),FeatureTest.sse(ok))){
            ChatClient c=new ChatClient();c.thinking(ThinkingControl.Mode.OFF);
            check(request(c,server,Provider.CUSTOM,new Seen()).text.equals("fine"),"400 on the thinking parameter falls back automatically");
            check(server.requests.size()==2&&"none".equals(Json.get(server.requests.get(0),ThinkingControl.PARAMETER)),"first request carried reasoning_effort=none");
            check(Json.get(server.requests.get(1),ThinkingControl.PARAMETER)==null,"retry removes only the thinking parameter");
            check(Json.get(server.requests.get(0),"messages").equals(Json.get(server.requests.get(1),"messages"))&&Json.get(server.requests.get(1),"stream_options")!=null,"fallback preserves messages and stream options");
            check(ThinkingControl.rejected(server.url(),"qwen-test")&&changes.get()==1,"rejection remembered per url and model, listener notified once");
            ChatClient again=new ChatClient();again.thinking(ThinkingControl.Mode.OFF);request(again,server,Provider.CUSTOM,new Seen());
            check(server.requests.size()==3&&Json.get(server.requests.get(2),ThinkingControl.PARAMETER)==null,"later requests do not resend a rejected parameter");
            ChatClient other=new ChatClient();other.thinking(ThinkingControl.Mode.OFF);other.chat(Provider.CUSTOM,server.url(),"","other-model","",List.of(ChatClient.Message.user("hi")),List.of(),true,new Seen());
            check("none".equals(Json.get(server.requests.get(3),ThinkingControl.PARAMETER)),"another model is not affected by the remembered rejection");}
        String saved=ThinkingControl.serialize();ThinkingControl.forget();check(!ThinkingControl.rejected("http://127.0.0.1:1/v1","qwen-test"),"forget clears memory");
        ThinkingControl.load(saved);check(saved.contains("qwen-test")&&ThinkingControl.serialize().equals(saved),"remembered rejection survives save and load");
        ThinkingControl.forget();
        // A 400 that persists without the parameter is not blamed on the parameter.
        FeatureTest.Response bad=new FeatureTest.Response(400,"application/json","{\"error\":{\"message\":\"context length exceeded\"}}");
        try(var server=new FeatureTest.Server(bad,bad)){ChatClient c=new ChatClient();c.thinking(ThinkingControl.Mode.OFF);boolean failed=false;try{request(c,server,Provider.CUSTOM,new Seen());}catch(IOException expected){failed=true;}
            check(failed&&server.requests.size()==2&&!ThinkingControl.rejected(server.url(),"qwen-test"),"unrelated 400 is not remembered as a rejected thinking parameter");}
        // Never sent: automatic mode, other providers, or after remembered rejection.
        try(var server=new FeatureTest.Server(FeatureTest.sse(ok),FeatureTest.sse(ok),FeatureTest.sse(ok))){
            ChatClient auto=new ChatClient();request(auto,server,Provider.CUSTOM,new Seen());
            ChatClient openai=new ChatClient();openai.thinking(ThinkingControl.Mode.OFF);request(openai,server,Provider.OPENAI,new Seen());
            ChatClient claude=new ChatClient();claude.thinking(ThinkingControl.Mode.OFF);try{request(claude,server,Provider.ANTHROPIC,new Seen());}catch(IOException ignored){}
            check(server.requests.stream().allMatch(x->Json.get(x,ThinkingControl.PARAMETER)==null),"parameter only for custom OpenAI-compatible servers in explicit off mode");}
        ThinkingControl.onChange(()->{});ThinkingControl.forget();
    }

    static InferenceRetry.Hooks hooks(){return new InferenceRetry.Hooks(){public void before(String s,List<ChatClient.Message> r){}public void usage(TokenUsage u){}public boolean cancelled(){return false;}};}
    static void retry()throws Exception{
        FeatureTest.Response gateway=new FeatureTest.Response(502,"text/plain","bad gateway"),timeout=new FeatureTest.Response(524,"text/plain","a timeout occurred");
        String ok=ev(delta(Json.obj("content","fine")))+ev(finish("stop"))+DONE;
        try(var server=new FeatureTest.Server(gateway,gateway,gateway)){boolean failed=false;try{InferenceRetry.chat(new ChatClient(),Provider.CUSTOM,server.url(),"","m","",List.of(ChatClient.Message.user("x")),List.of(),true,new Seen(),hooks());}catch(ChatClient.HttpFailure e){failed=e.status==502;}
            check(failed&&server.requests.size()==3,"502 is retried at most 3 attempts in total");}
        // 1.28.7: a proxy timeout (524/522/523) comes before any reply or tool, so it is retried with a short backoff (tests shorten it).
        long backoff=InferenceRetry.proxyBackoffMillis;InferenceRetry.proxyBackoffMillis=10;
        try{
            try(var server=new FeatureTest.Server(timeout,timeout,timeout)){boolean failed=false;String message="";try{InferenceRetry.chat(new ChatClient(),Provider.CUSTOM,server.url(),"","m","",List.of(ChatClient.Message.user("x")),List.of(),true,new Seen(),hooks());}catch(ChatClient.HttpFailure e){failed=e.status==524;message=e.getMessage();}
                check(failed&&server.requests.size()==3,"524 is retried at most 3 attempts in total, then fails");
                check(message.equals("HTTP 524 (proxy timeout ~100 s)"),"524 shows one readable line instead of the proxy page");}
            try(var server=new FeatureTest.Server(timeout,new FeatureTest.Response(522,"text/html","<html>cloudflare</html>"),FeatureTest.sse(ok))){check(InferenceRetry.chat(new ChatClient(),Provider.CUSTOM,server.url(),"","m","",List.of(ChatClient.Message.user("x")),List.of(),true,new Seen(),hooks()).text.equals("fine")&&server.requests.size()==3,"524 then 522 then success");}
            try(var server=new FeatureTest.Server(timeout,FeatureTest.sse(ok))){check(InferenceRetry.chat(new ChatClient(),Provider.CUSTOM,server.url(),"","m","",List.of(ChatClient.Message.user("x")),List.of(),true,new Seen(),hooks()).text.equals("fine")&&server.requests.size()==2,"524 then success");}
        }finally{InferenceRetry.proxyBackoffMillis=backoff;}
        try(var server=new FeatureTest.Server(new FeatureTest.Response(503,"text/plain","busy"),FeatureTest.sse(ok))){check(InferenceRetry.chat(new ChatClient(),Provider.CUSTOM,server.url(),"","m","",List.of(ChatClient.Message.user("x")),List.of(),true,new Seen(),hooks()).text.equals("fine")&&server.requests.size()==2,"503 then success");}
    }

    static void budgets()throws Exception{
        check(ResponseBudget.reasoningReserve(4096,0,0)==0,"no observation, no reserve");
        check(ResponseBudget.reasoningReserve(4096,1000,0)==1250,"reserve ~1.25 x observed reasoning");
        check(ResponseBudget.reasoningReserve(4096,100000,0)==ResponseBudget.REASONING_CAP,"reserve capped");
        check(ResponseBudget.reasoningReserve(4096,2000,4500)==404,"reserve never exceeds the API output limit");
        check(ResponseBudget.reasoningReserve(4096,2000,4096)==0,"no reserve when the budget already equals the API limit");
        check(ResponseBudget.observe(0,1000)==1000&&ResponseBudget.observe(1000,3000)==3000&&ResponseBudget.observe(1000,500)==750&&ResponseBudget.observe(1000,0)==500&&ResponseBudget.observe(0,0)==0,"observation follows growth fast and decays slowly");
        ContextGuard guard=new ContextGuard(0,1024,true,70,1.25);guard.reasoningReserve=500;check(guard.outputLimit()==1524,"reserve added to the request output limit");
        guard.reasoningReserve=0;check(guard.outputLimit()==1024,"no reserve leaves the profile unchanged");
        ContextGuard tight=new ContextGuard(2000,1024,true,70,1.25);tight.reasoningReserve=8000;check(tight.outputLimit()<=2000&&tight.outputLimit()>=1,"reserve never exceeds the context window");
        check(ThinkingControl.Mode.parse("off")==ThinkingControl.Mode.OFF&&ThinkingControl.Mode.parse("garbage")==ThinkingControl.Mode.AUTO&&ThinkingControl.Mode.parse(null)==ThinkingControl.Mode.AUTO,"mode parsing defaults to automatic");
        check(ThinkingControl.sends(Provider.CUSTOM,ThinkingControl.Mode.OFF,"http://h/v1","m")&&!ThinkingControl.sends(Provider.CUSTOM,ThinkingControl.Mode.AUTO,"http://h/v1","m")&&!ThinkingControl.sends(Provider.OPENAI,ThinkingControl.Mode.OFF,"http://h/v1","m"),"parameter eligibility");
    }

    public static void main(String[] args)throws Exception{
        Path home=Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("localchat.home"))),"run-");System.out.println("QA home: "+home);
        try{reasoningStream(home);truncation(home);fallback();retry();budgets();System.out.println("PASS: "+checks+" reasoning/truncation checks; reasoning stream, adaptive reserve, verified thinking parameter with 400 fallback, localized truncation, retry limited to 502/503/504/522/523/524");}
        finally{FeatureTest.edt(()->{for(var w:java.awt.Window.getWindows())w.dispose();});}
        System.exit(0);
    }
}