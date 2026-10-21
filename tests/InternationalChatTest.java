package localchat;
import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/** Real UI + loopback streaming: independent languages, original archive and theme contrast. */
public final class InternationalChatTest {
    static int checks;
    static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    static JComboBox<?> reply(LocalChat a)throws Exception{return FeatureTest.field(a,"responseLanguageBox");}
    static JCheckBox translate(LocalChat a)throws Exception{return FeatureTest.field(a,"translateMessageBox");}
    static String system(LocalChat a)throws Exception{return (String)FeatureTest.call(a,"plainPrompt",new Class<?>[]{});}
    static double channel(int v){double s=v/255.;return s<=.04045?s/12.92:Math.pow((s+.055)/1.055,2.4);}
    static double lum(Color c){return .2126*channel(c.getRed())+.7152*channel(c.getGreen())+.0722*channel(c.getBlue());}
    static double contrast(Color a,Color b){return (Math.max(lum(a),lum(b))+.05)/(Math.min(lum(a),lum(b))+.05);}
    static void quoted(LocalChat app,String quote)throws Exception{
        FeatureTest.call(app,"formatMarkdown",new Class<?>[]{});JTextPane pane=FeatureTest.field(app,"transcript");StyledDocument d=pane.getStyledDocument();String text=d.getText(0,d.getLength());int at=text.indexOf(quote);check(at>=0,"translated quote is visible with original characters");var attrs=d.getCharacterElement(at).getAttributes();check(StyleConstants.isItalic(attrs),"quote italic");Color fg=StyleConstants.getForeground(attrs);check(fg.equals(InternationalChat.quoteColor(pane.getBackground())),"quote uses theme color");check(contrast(fg,pane.getBackground())>=4.5,"quote contrast at least 4.5:1");
    }
    static void snapshot(LocalChat app,Path file)throws Exception{app.setSize(1320,950);app.addNotify();app.validate();ContextUsagePopupTest.layout(app.getRootPane());var im=new java.awt.image.BufferedImage(app.getWidth(),app.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);var g=im.createGraphics();app.getRootPane().printAll(g);g.dispose();javax.imageio.ImageIO.write(im,"png",file.toFile());}
    static void budgets()throws Exception{
        for(int p=0;p<3;p++)check(ResponseBudget.maximum(p,0)==new int[]{1024,4096,8192}[p],"legacy profile budgets unchanged");
        check(ResponseBudget.maximum(3,0)==16000,"automatic unknown output limit uses labelled working cap");check(ResponseBudget.maximum(3,64000)==64000,"automatic can use reported output greater than 8192");check(ResponseBudget.maximum(2,2000)==2000,"fixed profiles respect known API output cap");check(ResponseBudget.available(64000,32768,30000)==1784,"remaining context and safety margin cap output");check(ResponseBudget.available(16000,0,999999)==16000,"unknown context retains bounded working cap");
        var info=ModelInfo.parse(Json.obj("data",List.of(Json.obj("id","fixture","context_length",200000,"max_output_tokens",64000,"top_provider",Json.obj("max_completion_tokens",32000)))),"fixture","fixture");
        check(info.maximum()==200000&&info.outputLimit()==32000,"output and context caps distinct; route lower cap wins");check(ModelInfo.Info.from(info.json()).outputLimit()==32000,"output metadata cache round trip");
        check(ModelInfo.parse(Json.obj("data",List.of(Json.obj("id","fixture","max_tokens",99999,"context_length",200000))),"fixture","fixture").outputLimit()==0,"ambiguous max_tokens and context are never guessed as output");check(Conversations.State.fromJson(Json.obj("responseProfile",2),0).responseProfile==2&&Conversations.State.fromJson(Json.obj(),0).responseProfile==3,"old fixed selection preserved; missing profile defaults automatic");
        var body=ChatClient.class.getDeclaredMethod("openAiBody",String.class,String.class,List.class,List.class,boolean.class);body.setAccessible(true);Object payload=body.invoke(null,"fixture","system",List.of(ChatClient.Message.assistant("şantiye",List.of(),"fixture").withDisplayLanguage("tr")),List.of(),false);check(!Json.write(payload).contains("displayLanguage"),"local display metadata absent from provider payload");
    }
    static void ui(Path home)throws Exception{
        try(var server=new FeatureTest.Server()){
            FeatureTest.edt(()->{
                LocalChat app=AttachmentTest.window(home,server.url());((JComboBox<?>)FeatureTest.field(app,"languageBox")).setSelectedIndex(1);reply(app).setSelectedIndex(1);
                check(((JComboBox<?>)FeatureTest.field(app,"responseProfile")).getSelectedIndex()==3,"new installation defaults automatic response cap");
                Properties config=FeatureTest.field(app,"config");config.setProperty("memory.enabled","true");((LocalMemories)FeatureTest.call(app,"memories",new Class<?>[]{})).add("","Tine minte ca raspundem in limba romana.","manual","");
                for(int lang=0;lang<6;lang++)for(int theme=0;theme<2;theme++){
                    ((JComboBox<?>)FeatureTest.field(app,"languageBox")).setSelectedIndex(lang);((JComboBox<?>)FeatureTest.field(app,"themeBox")).setSelectedIndex(theme);
                    check(reply(app).getSelectedIndex()==1,"interface language/theme does not change Dutch response selection");check(translate(app).getText().equals(I18n.text("Tradu mesajul meu")),"translation control localized");check(reply(app).getAccessibleContext().getAccessibleName().equals(I18n.text("Limba raspunsurilor")),"language control localized");
                    String p=system(app);check(p.contains("ACTIVE LOCALCHAT RESPONSE LANGUAGE: Dutch")&&p.contains("older language memories"),"selected response language has explicit priority over saved Romanian");
                    JTextPane pane=FeatureTest.field(app,"transcript");check(contrast(InternationalChat.quoteColor(pane.getBackground()),pane.getBackground())>=4.5,"both theme colors readable for all interface languages");
                }
                for(int lang=0;lang<6;lang++){reply(app).setSelectedIndex(lang+1);check(system(app).contains("ACTIVE LOCALCHAT RESPONSE LANGUAGE: "+InternationalChat.name(I18n.CODES[lang])),"all six response languages available");}
                translate(app).setSelected(false);check(system(app).contains("Do not prepend a translation"),"translation can be disabled");translate(app).setSelected(true);
                reply(app).setSelectedIndex(0);check(!translate(app).isEnabled()&&system(app).contains("mode: automatic")&&!system(app).contains("ACTIVE LOCALCHAT RESPONSE LANGUAGE"),"automatic mode keeps memory and current explicit requests without forced translation");
                reply(app).setSelectedIndex(1);String agent=(String)FeatureTest.call(app,"agentPrompt",new Class<?>[]{Path.class,List.class,PathMap.class},home,List.of(),PathMap.none());check(agent.contains("ACTIVE LOCALCHAT RESPONSE LANGUAGE: Dutch")&&agent.contains("translated user message"),"tool and plain chat receive the response policy");
                FeatureTest.call(app,"setBusy",new Class<?>[]{boolean.class},true);check(!reply(app).isEnabled()&&!translate(app).isEnabled(),"language choices locked during a turn");FeatureTest.call(app,"setBusy",new Class<?>[]{boolean.class},false);check(reply(app).isEnabled()&&translate(app).isEnabled(),"choices restored after turn");
                var mode=(JPopupMenu)FeatureTest.call(app,"buildModeMenu",new Class<?>[]{});((JMenuItem)mode.getComponent(1)).doClick();check(!((JCheckBox)FeatureTest.field(app,"toolsBox")).isSelected()&&((JButton)FeatureTest.field(app,"bottomMode")).getText().equals(I18n.text("Normal")),"Normal selector disables tools and updates mode label");check(system(app).contains("general conversation")&&system(app).contains("unrelated questions"),"Normal prompt separates general topics from project workflows");((JMenuItem)mode.getComponent(0)).doClick();check(((JCheckBox)FeatureTest.field(app,"toolsBox")).isSelected()&&((JButton)FeatureTest.field(app,"bottomMode")).getText().equals("MBS"),"MBS selector restores tool availability");
                check(server.requests.isEmpty(),"language, theme, automatic profile and mode selection make no inference");app.dispose();
            });
        }
    }
    static void conversation(Path home)throws Exception{
        String user="Cand sunt gata desenele? C:/Șantier/505417.pdf",answer="> \"Çizimler ne zaman hazır?\"\n\nYanıt: şantiye ve ışık.";
        String stream="";for(String piece:new String[]{"> ","\"Çizimler ne zaman ","hazır?\"\n","\nYanıt: şantiye ve ışık."})stream+=FeatureTest.event(Json.obj("choices",List.of(Json.obj("delta",Json.obj("content",piece)))));stream+="data: [DONE]\n\n";
        try(var server=new FeatureTest.Server(FeatureTest.sse(stream))){LocalChat[] a={null};Path[] first={null};
            FeatureTest.edt(()->{a[0]=AttachmentTest.window(home,server.url());((JComboBox<?>)FeatureTest.field(a[0],"languageBox")).setSelectedIndex(1);((JComboBox<?>)FeatureTest.field(a[0],"themeBox")).setSelectedIndex(1);reply(a[0]).setSelectedIndex(6);translate(a[0]).setSelected(true);((JCheckBox)FeatureTest.field(a[0],"toolsBox")).setSelected(false);((JCheckBox)FeatureTest.field(a[0],"streamBox")).setSelected(true);AttachmentTest.input(a[0]).setText(user);FeatureTest.call(a[0],"send",new Class<?>[]{});});FeatureTest.idle(a[0]);
            FeatureTest.edt(()->{LocalChat app=a[0];check(server.requests.size()==1,"translation and answer share exactly one inference");Object request=server.requests.get(0);check(TokenUsage.number(request,"max_tokens",0)==16000&&Json.get(request,"tools")==null,"Normal mode sends automatic output cap without tool definitions");check(Json.str(request,"messages",1,"content").equals(user),"original user message reaches API unchanged");check(Json.str(request,"messages",0,"content").contains("Turkish"),"Romanian input requests Turkish output");List<ChatClient.Message> messages=FeatureTest.field(app,"history");check(messages.get(0).content.equals(user),"original user stored unchanged");check(messages.get(1).content.equals(answer)&&messages.get(1).displayLanguage.equals("tr"),"response and display language stored unchanged");
                quoted(app,"Çizimler ne zaman hazır?");snapshot(app,home.resolve("international-dark.png"));
                ((JComboBox<?>)FeatureTest.field(app,"themeBox")).setSelectedIndex(0);quoted(app,"Çizimler ne zaman hazır?");snapshot(app,home.resolve("international-light.png"));
                FeatureTest.call(app,"saveConversation",new Class<?>[]{});first[0]=FeatureTest.field(app,"conversationFile");Conversations.Saved stored=Conversations.load(first[0]);check(stored.state().responseLanguage.equals("tr")&&stored.state().translateMessage,"per-chat language and translation persisted");check(stored.messages().get(1).displayLanguage.equals("tr")&&stored.messages().get(0).content.equals(user),"original archive and metadata survive reload");
                FeatureTest.command(app,"/new English conversation");reply(app).setSelectedIndex(5);translate(app).setSelected(false);((JMenuItem)((JPopupMenu)FeatureTest.call(app,"buildModeMenu",new Class<?>[]{})).getComponent(0)).doClick();FeatureTest.call(app,"saveDefaults",new Class<?>[]{});
                FeatureTest.call(app,"openSession",new Class<?>[]{Conversations.Saved.class},stored);check(reply(app).getSelectedIndex()==6&&translate(app).isSelected()&&!((JCheckBox)FeatureTest.field(app,"toolsBox")).isSelected(),"resuming chat restores its own Turkish, translation and Normal mode choices");quoted(app,"Çizimler ne zaman hazır?");
                reply(app).setSelectedIndex(2);FeatureTest.call(app,"refreshTranscriptDisplay",new Class<?>[]{});quoted(app,"Çizimler ne zaman hazır?");check(((JTextPane)FeatureTest.field(app,"transcript")).getText().contains("şantiye ve ışık"),"old Turkish reply is not damaged by Romanian interface or later response selection");
                check(server.requests.size()==1,"loading, changing response language and theme never translate history or infer");FeatureTest.call(app,"saveConfig",new Class<?>[]{});app.dispose();a[0]=AttachmentTest.window(home,server.url());check(reply(a[0]).getSelectedIndex()==5&&!translate(a[0]).isSelected(),"explicit default response choices survive application restart");a[0].dispose();
            });
        }
    }
    public static void main(String[] args)throws Exception{Path home=Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("localchat.home"))),"run-");System.out.println("QA home: "+home);try{budgets();ui(Files.createDirectories(home.resolve("ui")));conversation(Files.createDirectories(home.resolve("conversation")));check(InternationalChat.valid("invalid").equals("auto"),"invalid language safe automatic fallback");check(Conversations.State.fromJson(Json.obj(),0).responseLanguage==null,"legacy chats keep fallback selection");System.out.println("PASS: "+checks+" international chat checks; six languages, both themes, streaming, original archive and session isolation");}finally{FeatureTest.edt(()->{for(var w:Window.getWindows())w.dispose();});}System.exit(0);}
}
