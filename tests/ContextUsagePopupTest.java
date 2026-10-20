package localchat;
import javax.swing.*;
import java.awt.*;
import java.nio.file.*;

/** Native Swing rendering, live updates from the dashboard, no inference requests. */
public final class ContextUsagePopupTest {
    static int checks;static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    static void layout(Component c){if(c instanceof Container container){container.doLayout();for(Component child:container.getComponents())layout(child);}}
    static void image(ContextUsagePopup popup,Path file)throws Exception{popup.setSize(popup.getPreferredSize());layout(popup);var image=new java.awt.image.BufferedImage(popup.getWidth(),popup.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();popup.printAll(g);g.dispose();javax.imageio.ImageIO.write(image,"png",file.toFile());}
    static int brightness(Color color){return color.getRed()+color.getGreen()+color.getBlue();}
    static void nativePopup(Path home)throws Exception{
        Files.createDirectories(home);Files.writeString(home.resolve("setari.properties"),"ui.theme=dark\n");
        LocalChat[] app={null};ContextUsagePopup[] card={null};Rectangle[] bounds={null};Color[] expected={null};
        FeatureTest.edt(()->{UIManager.setLookAndFeel(new com.formdev.flatlaf.FlatLightLaf());app[0]=AttachmentTest.window(home,"http://127.0.0.1:1/v1");card[0]=FeatureTest.field(app[0],"contextPopup");app[0].setSize(1050,850);app[0].setLocation(40,40);app[0].setVisible(true);});
        try{
            for(int theme:new int[]{1,0,1}){
                FeatureTest.edt(()->{card[0].setVisible(false);((JComboBox<?>)FeatureTest.field(app[0],"themeBox")).setSelectedIndex(theme);ContextRing ring=FeatureTest.field(app[0],"contextRing");ring.doClick();check(!card[0].names[0].getFont().isBold()&&!card[0].explanation.getFont().isBold(),"native popup body fonts remain plain after UI refresh");check(card[0].api.getIcon()!=null&&!card[0].api.getText().startsWith("▸"),"API expansion uses a vector icon instead of a missing glyph");check(card[0].isShowing(),"ring opens a real popup after cold start or theme change");check(card[0].getUI().getClass().getName().contains("flatlaf"),"detached popup has the current FlatLaf delegate");Point origin=card[0].getLocationOnScreen();bounds[0]=new Rectangle(origin,card[0].getSize());expected[0]=card[0].getBackground();});
                Thread.sleep(180);var captured=new java.awt.Robot().createScreenCapture(bounds[0]);Color actual=new Color(captured.getRGB(8,captured.getHeight()/2));check(Math.abs(actual.getRed()-expected[0].getRed())+Math.abs(actual.getGreen()-expected[0].getGreen())+Math.abs(actual.getBlue()-expected[0].getBlue())<20,"real on-screen popup surface matches theme, not a stale white host");javax.imageio.ImageIO.write(captured,"png",home.resolve("native-context-"+theme+".png").toFile());
                FeatureTest.edt(()->{int height=card[0].getHeight();card[0].api.doClick();check(card[0].isShowing()&&card[0].getHeight()>height,"real popup expands API section without closing");card[0].api.doClick();check(card[0].isShowing()&&card[0].getHeight()==height,"real popup collapses back to compact size");MenuSelectionManager.defaultManager().clearSelectedPath();check(!card[0].isVisible(),"normal popup dismissal closes the card");});
            }
        }finally{FeatureTest.edt(()->{card[0].setVisible(false);app[0].dispose();});}
    }
    public static void main(String[] args)throws Exception{
        Path home=Files.createDirectories(Path.of(System.getProperty("localchat.home")));
        try(var server=new FeatureTest.Server()){
            FeatureTest.edt(()->{
                LocalChat app=AttachmentTest.window(home,server.url());ContextUsagePopup popup=FeatureTest.field(app,"contextPopup");JTextArea input=FeatureTest.field(app,"input");WorkspacePanel workspace=FeatureTest.field(app,"workspace");workspace.limit.setValue(180000);
                for(int lang=0;lang<6;lang++)for(int theme=0;theme<2;theme++){
                    ((JComboBox<?>)FeatureTest.field(app,"languageBox")).setSelectedIndex(lang);((JComboBox<?>)FeatureTest.field(app,"themeBox")).setSelectedIndex(theme);
                    popup.update(2709,4525,53818,180000,"API fixture: separate counters");
                    check(popup.getPreferredSize().width==360&&popup.getPreferredSize().height<360,"compact context card dimensions");
                    check(popup.title.getText().equals(I18n.text("Utilizare context")),"translated context title");
                    check(popup.occupancy.getText().equals(I18n.text("~{0}% ocupat",ContextRing.percentage(61052,180000))),"percentage matches the reference breakdown");
                    check(popup.names[0].getText().equals(I18n.text("Prompt de sistem"))&&popup.names[1].getText().equals(I18n.text("Definitii unelte")),"translated category names");
                    check(popup.values[0].getText().startsWith("~")&&popup.values[0].getToolTipText().contains("2709"),"rounded estimate retains exact tooltip");
                    check(popup.segments.values[2]==53818&&popup.segments.maximum==180000,"bar uses the same categories and local denominator");
                    check(Math.abs(brightness(popup.title.getForeground())-brightness(popup.getBackground()))>250,"readable title in both themes");
                    check(popup.explanation.getText().equals(I18n.text("Estimarea contextului activ pentru cererea urmatoare, inclusiv ciorna. Nu reprezinta consumul API facturat.")),"billing distinction is translated");
                    check(!popup.usage.isVisible(),"billing counters collapsed by default");int closedHeight=popup.getPreferredSize().height;popup.api.doClick();check(popup.usage.isVisible()&&popup.getPreferredSize().height>closedHeight,"billing section expands separately");popup.api.doClick();
                    image(popup,home.resolve("context-"+I18n.language()+"-"+theme+".png"));
                    for(JLabel label:popup.names)check(label.getPreferredSize().width<310,"translated row fits popup");
                    input.setText("live draft ".repeat(150));FeatureTest.call(app,"refreshDashboard",new Class<?>[]{});ContextRing ring=FeatureTest.field(app,"contextRing");check(popup.segments.values[0]==ring.system&&popup.segments.values[1]==ring.tools&&popup.segments.values[2]==ring.conversation,"live popup and ring share the same dashboard estimates");
                    String prior=popup.values[2].getText();input.append("additional material ".repeat(200));FeatureTest.call(app,"refreshDashboard",new Class<?>[]{});check(!popup.values[2].getText().equals(prior),"draft changes update existing popup labels");input.setText("");
                }
                popup.update(100,200,300,0,"");check(popup.occupancy.getText().equals(I18n.text("Limita de context necunoscuta"))&&!popup.budget.getText().contains("0 / 0"),"unknown maximum never becomes zero percent");
                popup.update(100,200,800,1000,"");check(popup.occupancy.getText().contains(ContextRing.percentage(1100,1000)),"overflow percentage is honest rather than clamped");image(popup,home.resolve("overflow.png"));
                check(ContextUsagePopup.shortTokens(999).equals("~999")&&ContextUsagePopup.shortTokens(1000000).endsWith("M"),"small and million token estimates formatted");
                check(server.requests.isEmpty(),"viewing and updating context creates no inference requests");app.dispose();
            });nativePopup(home.resolve("native"));System.out.println("PASS: "+checks+" live context popup checks; six languages, both themes, estimates and native rendering");
        }finally{FeatureTest.edt(()->{for(var w:Window.getWindows())w.dispose();});}System.exit(0);
    }
}
