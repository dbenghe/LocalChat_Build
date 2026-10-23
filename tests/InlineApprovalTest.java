package localchat;
import java.nio.file.*;import java.util.*;import javax.swing.*;
/** A real write waits in the discussion; approve, custom alternative, close and Stop are distinct. */
public final class InlineApprovalTest {
 static int checks;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 public static void main(String[] args)throws Exception{
  Path home=Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("localchat.home"))),"approval-");
  for(String mode:List.of("approve","custom","close","stop")){
   Path local=Files.createDirectories(home.resolve(mode)),target=local.resolve("decision.txt");
   try(var server=new FeatureTest.Server(FeatureTest.json(PdfAgentTest.request("write","write_file",Json.obj("path",target.toString(),"content","explicitly approved"))),FeatureTest.json(FeatureTest.answer("Operation outcome received.",20,4)))){
    LocalChat[] app={null};FeatureTest.edt(()->{app[0]=AttachmentTest.window(local,server.url());((JComboBox<?>)FeatureTest.field(app[0],"permissionBox")).setSelectedIndex(0);AttachmentTest.input(app[0]).setText("Scrie fisierul indicat.");FeatureTest.call(app[0],"send",new Class<?>[]{});});
    boolean[] pending={false};for(int i=0;i<400&&!pending[0];i++){FeatureTest.edt(()->pending[0]=FeatureTest.field(app[0],"pendingApproval")!=null);if(!pending[0])Thread.sleep(10);}
    check(pending[0]&&!Files.exists(target),"real write waits for explicit approval: "+mode);
    FeatureTest.edt(()->{
     ComposerAssist card=FeatureTest.field(app[0],"composerAssist");check(SwingUtilities.getAncestorOfClass(ConversationBody.class,card)!=null&&card.send.isEnabled()==false,"approval is in discussion without a default choice");
     AttachmentTest.input(app[0]).setText("My separate editable draft");
     if(mode.equals("stop")){FeatureTest.call(app[0],"stop",new Class<?>[]{});return;}
     if(mode.equals("close")){card.close.doClick();return;}
     if(mode.equals("custom")){ComposerAssistTest.all(card).stream().filter(c->c instanceof JTextField).map(c->(JTextField)c).findFirst().orElseThrow().setText("Doar explica, fara modificari.");}
     else{ComposerAssistTest.all(card).stream().filter(c->c instanceof JToggleButton b&&I18n.text("Permite").equals(b.getClientProperty("interaction.option"))).map(c->(JToggleButton)c).findFirst().orElseThrow().doClick();}
     card.send.doClick();
    });FeatureTest.idle(app[0]);
    FeatureTest.edt(()->{
     check(Files.exists(target)==mode.equals("approve"),"only explicit approval permits the write: "+mode);
     check(FeatureTest.field(app[0],"pendingApproval")==null,"worker is released after decision or Stop: "+mode);
     check(AttachmentTest.input(app[0]).getText().contains("My separate editable draft"),"approval preserves editable draft: "+mode);
     if(mode.equals("custom"))check(AttachmentTest.input(app[0]).getText().contains("Doar explica"),"custom alternative remains a draft and is never treated as approval");
     if(mode.equals("stop"))check(server.requests.size()==1,"Stop prevents another inference and any late write");app[0].dispose();
    });
   }
  }
  System.out.println("PASS: "+checks+" inline command approval checks; real writes, custom text, close and Stop");System.exit(0);
 }
}
