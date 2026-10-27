package localchat;
import javax.swing.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/** 1.27.0: reasoning_content parsing, stable request prefix, one language policy, wire-only volatile block and per-component measurement. */
public final class StablePrefixTest {
    static int checks;
    static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    static int count(String text,String part){int n=0,at=0;while((at=text.indexOf(part,at))>=0){n++;at+=part.length();}return n;}
    static String wire(Object request){return Json.write(request);}
    static String sse(Object... deltas){StringBuilder b=new StringBuilder();for(Object d:deltas)b.append(FeatureTest.event(d));return b.append("data: [DONE]\n\n").toString();}
    static Object delta(Object fields){return Json.obj("choices",List.of(Json.obj("delta",fields)));}

    static void streamAndPlain()throws Exception{
        String body=sse(delta(Json.obj("role","assistant","content","")),delta(Json.obj("reasoning_content","Pondering REASON-TRACE ")),delta(Json.obj("reasoning_content","step two","content",null)),delta(Json.obj("reasoning_content",null,"content","Answer ")),delta(Json.obj("content","text")),
            Json.obj("choices",List.of(),"usage",Json.obj("prompt_tokens",100,"completion_tokens",50,"completion_tokens_details",Json.obj("reasoning_tokens",30))));
        try(var server=new FeatureTest.Server(FeatureTest.sse(body))){
            StringBuilder text=new StringBuilder(),thinking=new StringBuilder();ChatClient client=new ChatClient();
            var reply=client.chat(Provider.CUSTOM,server.url(),"","fixture","system",List.of(ChatClient.Message.user("hello")),List.of(),true,new ChatClient.Listener(){public void onText(String d){text.append(d);}public void onReasoning(String d){thinking.append(d);}});
            check(reply.text.equals("Answer text")&&text.toString().equals("Answer text"),"stream answer excludes reasoning_content");
            check(reply.reasoning.equals("Pondering REASON-TRACE step two")&&thinking.toString().equals(reply.reasoning),"stream reasoning_content parsed incrementally, null deltas ignored");
            check(client.usage().reasoning()==30&&client.usage().input()==100&&client.usage().output()==50,"reasoning_tokens counted from usage; input/output unchanged");
        }
        try(var server=new FeatureTest.Server(FeatureTest.json(Json.write(Json.obj("choices",List.of(Json.obj("message",Json.obj("content","Plain answer","reasoning_content","Plain REASON-TRACE"))),"usage",Json.obj("prompt_tokens",10,"completion_tokens",20,"completion_tokens_details",Json.obj("reasoning_tokens",12))))),
                FeatureTest.json(Json.write(Json.obj("choices",List.of(Json.obj("message",Json.obj("content","Alt","reasoning","Alt-field"))),"usage",Json.obj("prompt_tokens",1,"completion_tokens",2)))))){
            ChatClient client=new ChatClient();var reply=FeatureTest.request(client,server,Provider.CUSTOM,false);
            check(reply.text.equals("Plain answer")&&reply.reasoning.equals("Plain REASON-TRACE")&&client.usage().reasoning()==12,"non-stream reasoning_content and reasoning_tokens parsed");
            reply=FeatureTest.request(client,server,Provider.CUSTOM,false);
            check(reply.text.equals("Alt")&&reply.reasoning.equals("Alt-field")&&client.usage().reasoning()==0,"reasoning alias parsed; absent reasoning_tokens stay zero");
        }
        check("hmm".equals(ChatClient.reasoningDelta(Json.obj("type","content_block_delta","delta",Json.obj("type","thinking_delta","thinking","hmm")),true))&&ChatClient.reasoningDelta(Json.obj("type","content_block_delta","delta",Json.obj("type","text_delta","text","x")),true)==null,"Anthropic thinking_delta parsed, text delta ignored");
        TokenUsage.Ledger ledger=new TokenUsage.Ledger();ledger.add(new TokenUsage(100,50,0,0,true,30));ledger.add(new TokenUsage(10,5,0,0,true,4));
        check(ledger.reasoning==34&&TokenUsage.Ledger.fromJson(ledger.toJson()).reasoning==34,"cumulative reasoning tokens persisted");
    }

    static void measurement()throws Exception{
        var tool=ChatClient.Message.toolResult("t","R".repeat(4000),false);var user=ChatClient.Message.user("question");
        long results=ContextMemory.toolResults(List.of(user,tool)),all=ContextMemory.messages(List.of(user,tool));
        check(results>=1000&&results<all&&ContextMemory.toolResults(List.of(user))==0,"tool results measured separately and included in messages");
        FeatureTest.edt(()->{ContextUsagePopup popup=new ContextUsagePopup();popup.update(1000,2000,3000,4000,100000,"");
            check(popup.segments.values.length==4&&popup.segments.values[3]==4000&&popup.segments.values[2]==3000,"popup exposes system, tools, messages and tool results");
            check(popup.values[3].getToolTipText().startsWith("~4000")&&popup.values[3].getText().startsWith("~"),"every component is labelled as an estimate");
            popup.update(1,2,3,100,"");check(popup.segments.values[3]==0,"five-argument update keeps results at zero");
            check(popup.getPreferredSize().height<420,"four-row popup stays compact");});
    }

    static void turns(Path home,boolean mbs)throws Exception{
        String reasoning="TRACE-"+(mbs?"MBS":"NORMAL")+"-PRIVATE";
        String s1=sse(delta(Json.obj("reasoning_content",reasoning)),delta(Json.obj("content","First answer")),Json.obj("choices",List.of(),"usage",Json.obj("prompt_tokens",900,"completion_tokens",60,"completion_tokens_details",Json.obj("reasoning_tokens",25))));
        String s2=sse(delta(Json.obj("content","Second answer")));
        try(var server=new FeatureTest.Server(FeatureTest.sse(s1),FeatureTest.sse(s2),FeatureTest.sse(s2))){
            LocalChat[] a={null};
            FeatureTest.edt(()->{a[0]=AttachmentTest.window(home,server.url());((JCheckBox)FeatureTest.field(a[0],"streamBox")).setSelected(true);((JCheckBox)FeatureTest.field(a[0],"toolsBox")).setSelected(mbs);
                Properties config=FeatureTest.field(a[0],"config");config.setProperty("memory.enabled","true");
                var store=(LocalMemories)FeatureTest.call(a[0],"memories",new Class<?>[]{});store.add("","VOLATILE-MEMORY-MARK global preference","manual","");
                ((JComboBox<?>)FeatureTest.field(a[0],"responseLanguageBox")).setSelectedIndex(1);((JCheckBox)FeatureTest.field(a[0],"translateMessageBox")).setSelected(true);
                ((javax.swing.text.JTextComponent)FeatureTest.field(a[0],"systemField")).setText("STABLE-USER-INSTRUCTION");});
            for(String q:List.of("Prima intrebare 123456","A doua intrebare 123456")){FeatureTest.edt(()->{AttachmentTest.input(a[0]).setText(q);FeatureTest.call(a[0],"send",new Class<?>[]{});});FeatureTest.idle(a[0]);}
            FeatureTest.edt(()->{
                check(server.requests.size()==2,"one inference per turn");Object r1=server.requests.get(0),r2=server.requests.get(1);String w1=wire(r1),w2=wire(r2);
                String sys1=Json.str(r1,"messages",0,"content"),sys2=Json.str(r2,"messages",0,"content");
                check("system".equals(Json.str(r1,"messages",0,"role"))&&sys1.equals(sys2),(mbs?"MBS":"Normal")+" system prompt is byte-identical between turns");
                check(count(w2,"ACTIVE LOCALCHAT RESPONSE LANGUAGE")==1&&count(w2,"Start the final user-facing answer")==1&&count(w2,"Do not prepend")+count(w2,"Begin each final answer")==1,"language policy appears exactly once per request (no head/tail/control triplication)");
                check(!w1.contains("displayLanguage")&&!w2.contains("displayLanguage"),"displayLanguage never reaches the payload");
                check(!w2.contains(reasoning)&&!w2.contains("reasoning_content"),"reasoning is never resent in history");
                check(Json.str(r2,"messages",1,"content").equals("Prima intrebare 123456")&&Json.str(r1,"messages",1,"content").equals("Prima intrebare 123456")&&"assistant".equals(Json.str(r2,"messages",2,"role"))&&Json.str(r2,"messages",2,"content").equals("First answer"),"earlier history is a byte-stable prefix: no per-turn control message between turns");
                List<ChatClient.Message> history=FeatureTest.field(a[0],"history");
                check(history.get(1).content.equals("First answer")&&history.stream().allMatch(m->m.requestControl.isEmpty()&&!Json.write(m.toJson()).contains(reasoning)),"archive keeps the answer only: no reasoning and no wire-only block");
                check(AttachmentTest.state(a[0]).usage.reasoning==25,"session ledger counts reasoning_tokens");
                String usage=(String)FeatureTest.call(a[0],"usageText",new Class<?>[]{TokenUsage.Ledger.class},AttachmentTest.state(a[0]).usage);check(usage.contains("25"),"usage line shows reasoning tokens");
                int n2=((List<?>)Json.get(r2,"messages")).size();
                if(mbs){
                    check(!sys2.contains("VOLATILE-MEMORY-MARK")&&sys2.contains("STABLE-USER-INSTRUCTION"),"volatile memory entries are not in the system prompt");
                    String last=Json.str(r2,"messages",n2-1,"content");
                    check("user".equals(Json.str(r2,"messages",n2-1,"role"))&&last.startsWith("LocalChat session data for the preceding user request")&&last.contains("VOLATILE-MEMORY-MARK")&&count(w2,"LocalChat session data")==1,"one volatile block, last in the request, only once");
                    check(Json.get(r2,"tools")!=null,"MBS sends tool definitions");
                    Path file=FeatureTest.field(a[0],"conversationFile");
                    try{FeatureTest.call(a[0],"saveConversation",new Class<?>[]{});file=FeatureTest.field(a[0],"conversationFile");check(!Files.readString(file).contains("LocalChat session data")&&!Files.readString(file).contains(reasoning),"saved conversation never contains the volatile block or reasoning");}catch(Exception e){throw new RuntimeException(e);}
                }else{
                    check(Json.get(r2,"tools")==null&&!w2.contains("LocalChat session data")&&!w2.contains("VOLATILE-MEMORY-MARK")&&!w2.contains("PDFCONTROL")&&n2==4,"Normal stays clean: no tools, memories, MBS rules or volatile block");
                }
                a[0].dispose();});
        }
    }

    public static void main(String[] args)throws Exception{
        Path home=Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("localchat.home"))),"run-");System.out.println("QA home: "+home);
        try{streamAndPlain();measurement();turns(home.resolve("mbs"),true);turns(home.resolve("normal"),false);System.out.println("PASS: "+checks+" stable-prefix checks; reasoning_content, one language policy, volatile block, measurement");}
        finally{FeatureTest.edt(()->{for(var w:java.awt.Window.getWindows())w.dispose();});}
        System.exit(0);
    }
}