package localchat;
import java.awt.*;import java.awt.image.BufferedImage;import java.nio.file.*;import java.util.*;import java.util.List;import javax.swing.*;
public final class CompactCardsTest {
 static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception{
  Path home=Files.createDirectories(Path.of(System.getProperty("localchat.home")));
  FeatureTest.edt(()->{
   for(String language:I18n.CODES)for(boolean dark:List.of(false,true)){
    if(dark)com.formdev.flatlaf.FlatDarkLaf.setup();else com.formdev.flatlaf.FlatLightLaf.setup();I18n.setLanguage(language);
    ComposerAssist.GhostInput input=new ComposerAssist.GhostInput();input.setText("Pending draft");var a=new ComposerAssist(input,s->{});
    String first="1. Factory A — Z:\\Projects\\506000_A long project name with multiple buildings and a long street address that must wrap within this card";
    a.presentLocal(new ComposerAssist.Request("Project 506000 appears in multiple locations. Choose the factory and folder.",List.of(new ComposerAssist.Question("factory","Where should I process project 506000?",List.of(first,"2. Factory B — Y:\\Projects\\506000_A second location","3. Factory C — folder not yet confirmed","4. Factory D — folder not yet confirmed"))),List.of()),"fixture");
    JFrame frame=new JFrame();JPanel host=new JPanel();host.setLayout(new BoxLayout(host,BoxLayout.Y_AXIS));host.add(a);JScrollPane draft=new JScrollPane(input);draft.setAlignmentX(Component.LEFT_ALIGNMENT);host.add(draft);frame.add(host);frame.setSize(1200,430);frame.addNotify();frame.validate();frame.validate();
    check(a.getWidth()<=720&&a.getHeight()<=265,"bounded card on wide window");
    var buttons=ComposerAssistTest.all(a).stream().filter(c->c instanceof JRadioButton).map(c->(JRadioButton)c).toList();var labels=ComposerAssistTest.all(a).stream().filter(c->c instanceof JTextArea t&&Boolean.TRUE.equals(t.getClientProperty("interaction.label"))).map(c->(JTextArea)c).toList();
    check(buttons.size()==4&&labels.size()==4&&!labels.get(0).getText().startsWith("1."),"single visual numbering");
    for(int i=0;i<4;i++){Point button=SwingUtilities.convertPoint(buttons.get(i),0,0,a),label=SwingUtilities.convertPoint(labels.get(i),0,0,a);check(Math.abs(button.y-label.y)<=2,"selectors align with first text line");}
    check(!a.send.isEnabled()&&input.getText().equals("Pending draft"),"compact layout does not authorize or edit draft");buttons.get(0).doClick();check(a.value("factory").equals(first),"original numbered choice retained");
    BufferedImage image=new BufferedImage(a.getWidth(),a.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();g.setColor(host.getBackground());g.fillRect(0,0,image.getWidth(),image.getHeight());a.printAll(g);g.dispose();
    if(language.equals("ro"))javax.imageio.ImageIO.write(image,"png",home.resolve(dark?"compact-dark.png":"compact-light.png").toFile());
    frame.setSize(430,430);frame.validate();frame.validate();check(a.getWidth()<=430&&labels.get(0).getWidth()>100&&labels.get(0).getWidth()<a.getWidth(),"fits narrow window");
    check(labels.get(0).getPreferredSize().height>labels.get(0).getFontMetrics(labels.get(0).getFont()).getHeight(),"long option wraps vertically");
    if(language.equals("ro")){BufferedImage small=new BufferedImage(a.getWidth(),a.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D sg=small.createGraphics();sg.setColor(host.getBackground());sg.fillRect(0,0,small.getWidth(),small.getHeight());a.printAll(sg);sg.dispose();javax.imageio.ImageIO.write(small,"png",home.resolve(dark?"compact-narrow-dark.png":"compact-narrow-light.png").toFile());}
    a.presentLocal(new ComposerAssist.Request("Long summary ".repeat(90),List.of(new ComposerAssist.Question("q","Choose",List.of("Yes","No"))),List.of()),"long-title");frame.setSize(1200,430);frame.validate();frame.validate();check(a.getHeight()<=265,"long summary cannot enlarge the card without bound");
    frame.dispose();
   }
  });
  System.out.println("PASS: "+checks+" compact card checks; six languages, both themes, alignment, wrapping, width and original values");System.exit(0);
 }
}
