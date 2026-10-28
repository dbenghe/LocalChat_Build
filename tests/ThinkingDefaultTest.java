package localchat;
import java.util.*;

/** 1.28.7: custom servers think off unless the user explicitly chose otherwise; explicit choices always win. */
public final class ThinkingDefaultTest {
    static int checks;
    static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
    public static void main(String[] args)throws Exception{
        String profile="custom|model";
        Properties fresh=new Properties();
        check(ThinkingControl.effective(fresh,profile,Provider.CUSTOM)==ThinkingControl.Mode.OFF,"custom profile without a choice thinks off");
        check(ThinkingControl.effective(fresh,profile,Provider.OPENAI)==ThinkingControl.Mode.AUTO&&ThinkingControl.effective(fresh,profile,Provider.ANTHROPIC)==ThinkingControl.Mode.AUTO,"built-in providers are unchanged");
        // Migration: a config saved by 1.28.6 holds thinking=auto with no explicit marker; it is the old default, so it becomes OFF once.
        Properties old=new Properties();old.setProperty(profile+".thinking","auto");
        check(ThinkingControl.effective(old,profile,Provider.CUSTOM)==ThinkingControl.Mode.OFF,"migration: legacy saved auto counts as not explicit");
        check(ThinkingControl.effective(old,profile,Provider.OPENAI)==ThinkingControl.Mode.AUTO,"migration leaves built-in providers on auto");
        Properties legacyOff=new Properties();legacyOff.setProperty(profile+".thinking","off");
        check(ThinkingControl.effective(legacyOff,profile,Provider.CUSTOM)==ThinkingControl.Mode.OFF,"legacy off stays off");
        // Explicit choices, recorded by the quick settings menu from now on.
        Properties explicit=new Properties();ThinkingControl.choose(explicit,profile,ThinkingControl.Mode.AUTO);
        check("auto".equals(explicit.getProperty(profile+".thinking"))&&"true".equals(explicit.getProperty(profile+".thinking.explicit")),"choosing Automatic persists the value and the explicit marker");
        check(ThinkingControl.effective(explicit,profile,Provider.CUSTOM)==ThinkingControl.Mode.AUTO,"explicit Automatic is respected for custom servers");
        ThinkingControl.choose(explicit,profile,ThinkingControl.Mode.OFF);
        check(ThinkingControl.effective(explicit,profile,Provider.CUSTOM)==ThinkingControl.Mode.OFF,"explicit Off is respected");
        ThinkingControl.choose(explicit,profile,ThinkingControl.Mode.AUTO);
        check(ThinkingControl.effective(explicit,profile,Provider.CUSTOM)==ThinkingControl.Mode.AUTO,"the latest explicit choice wins");
        check(ThinkingControl.effective(explicit,"other|profile",Provider.CUSTOM)==ThinkingControl.Mode.OFF,"an explicit choice is per profile");
        // Survives a settings reload.
        Properties reloaded=new Properties();java.io.StringWriter text=new java.io.StringWriter();explicit.store(text,"");reloaded.load(new java.io.StringReader(text.toString()));
        check(ThinkingControl.effective(reloaded,profile,Provider.CUSTOM)==ThinkingControl.Mode.AUTO,"explicit Automatic survives save and reload");
        // The effective mode reaches the request: custom + default sends none, explicit Automatic sends nothing.
        for(boolean wantsThinking:new boolean[]{false,true}){
            Properties config=new Properties();if(wantsThinking)ThinkingControl.choose(config,profile,ThinkingControl.Mode.AUTO);
            try(var server=new FeatureTest.Server(FeatureTest.json(FeatureTest.answer("ok",5,2)))){
                ChatClient client=new ChatClient();client.thinking(ThinkingControl.effective(config,profile,Provider.CUSTOM));
                client.chat(Provider.CUSTOM,server.url(),"","fixture-default-"+wantsThinking,"system",List.of(ChatClient.Message.user("hi")),List.of(),false,s->{});
                boolean sent=ThinkingControl.OFF_VALUE.equals(Json.get(server.requests.get(0),ThinkingControl.PARAMETER));
                check(sent==!wantsThinking,wantsThinking?"explicit Automatic sends no reasoning_effort":"default sends reasoning_effort=none");
            }
        }
        // Delegated children follow the effective mode.
        for(boolean wantsThinking:new boolean[]{false,true}){
            Properties config=new Properties();if(wantsThinking)ThinkingControl.choose(config,profile,ThinkingControl.Mode.AUTO);
            try(var server=new FeatureTest.Server(FeatureTest.json(FeatureTest.answer("Final C",5,2)))){
                var group=new AgentGroup(()->null,java.nio.file.Files.createTempDirectory(java.nio.file.Files.createDirectories(java.nio.file.Path.of(System.getProperty("localchat.home"))),"think-"),65536,1024,false,70).reasoning(ThinkingControl.effective(config,profile,Provider.CUSTOM),0);
                group.run(AgentGroupTest.request("plain analysis"),Provider.CUSTOM,server.url(),"","fixture-child-"+wantsThinking,"label","system",new AgentGroupTest.Ui());
                boolean sent=ThinkingControl.OFF_VALUE.equals(Json.get(server.requests.get(0),ThinkingControl.PARAMETER));
                check(sent==!wantsThinking,wantsThinking?"child of an explicit Automatic profile sends no reasoning_effort":"child agents inherit the default off");
            }
        }
        ThinkingControl.forget();
        System.out.println("PASS: "+checks+" thinking default checks");System.exit(0);
    }
}
