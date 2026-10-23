package localchat;
import java.awt.*;import java.nio.file.*;import java.util.*;import java.util.List;import javax.swing.*;
/** Modern inline cards: optional links, real decisions and editable composer, no checkbox forms inferred from prose. */
public final class InteractionCardsTest {
 static int checks;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 static List<Component> all(Component root){return ComposerAssistTest.all(root);}
 public static void main(String[] args)throws Exception{
  Path home=Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("localchat.home"))),"inline-");
  FeatureTest.edt(()->{
   var input=new ComposerAssist.GhostInput();input.setText("My existing draft");int[] sends={0};var assist=new ComposerAssist(input,s->sends[0]++);
   assist.present(ComposerAssistTest.suggestions(),"suggestion");
   check(!assist.waiting()&&!assist.send.isEnabled(),"optional suggestions never require a form submission");
   check(all(assist).stream().noneMatch(c->c instanceof JCheckBox||c instanceof JRadioButton||c instanceof JTextField),"optional suggestions have no checkboxes, radios or competing text field");
   var links=all(assist).stream().filter(c->c instanceof JButton b&&b.getClientProperty("interaction.suggestion")!=null).map(c->(JButton)c).toList();
   check(links.size()==2&&links.stream().allMatch(b->b.getFont().getSize()<=12),"two short unobtrusive text links");links.get(0).doClick();
   check(input.getText().startsWith("My existing draft")&&input.getText().contains("Verifica raportul")&&sends[0]==0,"suggestion edits existing draft without sending");
   assist.restore(List.of(ChatClient.Message.user("Question"),ChatClient.Message.assistant("Astept confirmarea. In ce fabrica procesez?",List.of(),"fixture")),"");check(!assist.isVisible(),"plain assistant text never reconstructs an intrusive form");
   assist.present(ComposerAssistTest.questions(),"decision");check(assist.waiting()&&all(assist).stream().noneMatch(c->c instanceof JCheckBox||c instanceof JRadioButton),"required decisions use plain text choices without check/radio glyphs");
   check(all(assist).stream().anyMatch(c->c instanceof JTextField),"real decisions include a free alternative");
   String before=input.getText();assist.close.doClick();check(!assist.isVisible()&&input.getText().equals(before),"dismiss never changes editor content");
  });
  try(var server=new FeatureTest.Server(FeatureTest.json(PdfAgentTest.request("decision",ComposerAssist.TOOL,ComposerAssistTest.questions())),FeatureTest.json(FeatureTest.answer("New message received",20,4)))){
   LocalChat[] app={null};FeatureTest.edt(()->{app[0]=AttachmentTest.window(home.resolve("app"),server.url());app[0].setSize(1300,950);app[0].addNotify();app[0].validate();});
   NormalModeTest.ask(app[0],"Verifica doar situatia curenta.");
   FeatureTest.edt(()->{
    ComposerAssist assist=FeatureTest.field(app[0],"composerAssist");JTextPane transcript=FeatureTest.field(app[0],"transcript");
    ConversationBody body=(ConversationBody)SwingUtilities.getAncestorOfClass(ConversationBody.class,assist);
    check(body!=null&&SwingUtilities.isDescendingFrom(transcript,body),"proposal and transcript belong to the same scrolling discussion");
    check(!SwingUtilities.isDescendingFrom(AttachmentTest.input(app[0]),body),"editor stays outside the discussion card area");
    for(int theme=0;theme<2;theme++){
     ((JComboBox<?>)FeatureTest.field(app[0],"themeBox")).setSelectedIndex(theme);app[0].validate();ContextUsagePopupTest.layout(app[0].getRootPane());
     check(assist.getWidth()<=620&&assist.getHeight()<270,"compact responsive card stays bounded");
     var image=new java.awt.image.BufferedImage(1300,950,java.awt.image.BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();app[0].getRootPane().printAll(g);g.dispose();javax.imageio.ImageIO.write(image,"png",home.resolve(theme==0?"inline-light.png":"inline-dark.png").toFile());
    }
    AttachmentTest.input(app[0]).setText("Raspund direct aici, fara formular.");FeatureTest.call(app[0],"send",new Class<?>[]{});
   });FeatureTest.idle(app[0]);
   FeatureTest.edt(()->{check(server.requests.size()==2,"normal editor send remains usable with a question displayed");check(NormalModeTest.history(app[0]).stream().anyMatch(m->m.role.equals("user")&&m.content.equals("Raspund direct aici, fara formular.")),"free message sent exactly as written");app[0].dispose();});
  }finally{FeatureTest.edt(()->{for(Window window:Window.getWindows())window.dispose();});}
  System.out.println("PASS: "+checks+" compact inline interaction checks; screenshots: "+home);System.exit(0);
 }
}
