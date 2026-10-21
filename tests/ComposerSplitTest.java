package localchat;
import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.util.*;

/** Actual viewport bounds, persisted user sizing and unchanged drafts; no inference. */
public final class ComposerSplitTest {
    static int checks;static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    static void layout(LocalChat app){app.validate();ContextUsagePopupTest.layout(app.getRootPane());}
    static void bounded(ComposerSplit split,String name){check(split.input.getViewport().getHeight()<=split.response.getViewport().getHeight()+1,name+": composer cannot exceed the answer viewport");check(split.input.getViewport().getHeight()>=Math.min(ComposerSplit.MINIMUM,split.maximumInputHeight())-2,name+": readable minimum unless window space prevents it");}
    static void image(LocalChat app,Path file)throws Exception{var image=new java.awt.image.BufferedImage(app.getWidth(),app.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();app.getRootPane().printAll(g);g.dispose();javax.imageio.ImageIO.write(image,"png",file.toFile());}
    public static void main(String[] args)throws Exception{
        Path home=Files.createDirectories(Path.of(System.getProperty("localchat.home"))),local=Files.createDirectories(home.resolve("app"));
        try(var server=new FeatureTest.Server()){
            FeatureTest.edt(()->{
                LocalChat app=AttachmentTest.window(local,server.url());app.setSize(1320,950);app.addNotify();layout(app);ComposerSplit split=FeatureTest.field(app,"composerSplit");JTextArea text=AttachmentTest.input(app);String draft="Exact draft 507123\n"+"evidence line\n".repeat(80);text.setText(draft);text.select(3,12);layout(app);
                check(split.input.getViewport().getHeight()>=140,"default composer is taller than the former three rows");bounded(split,"default");image(app,home.resolve("composer-default.png"));
                split.setDividerLocation(-10000);layout(app);bounded(split,"maximum drag");check(Math.abs(split.input.getViewport().getHeight()-split.response.getViewport().getHeight())<=2,"maximum makes editable and answer viewports equal");image(app,home.resolve("composer-maximum.png"));
                split.setDividerLocation(100000);layout(app);bounded(split,"minimum drag");check(Math.abs(split.input.getViewport().getHeight()-ComposerSplit.MINIMUM)<=2,"minimum drag stops at 96 pixels");
                split.setDividerLocation(split.getDividerLocation()-45);layout(app);int preference=split.preferredInputHeight();check(preference>ComposerSplit.MINIMUM,"manual dragging changes the preferred height");
                Properties config=FeatureTest.field(app,"config");check(config.getProperty("ui.composer.height").equals(Integer.toString(preference)),"chosen size is saved to configuration");
                Path attachment=Files.writeString(home.resolve("selected.txt"),"fixture");AttachmentTest.add(app,attachment);layout(app);bounded(split,"attachment row");
                JPanel report=FeatureTest.field(app,"reportBar");report.setVisible(true);layout(app);bounded(split,"report row");
                app.setSize(1100,650);layout(app);bounded(split,"small window");check(split.preferredInputHeight()==preference,"temporary small window preserves user preference");app.setSize(1320,1050);layout(app);bounded(split,"large window");check(Math.abs(split.input.getViewport().getHeight()-preference)<=2,"growing window restores preferred input height");
                for(int language=0;language<6;language++)for(int theme=0;theme<2;theme++){
                    ((JComboBox<?>)FeatureTest.field(app,"languageBox")).setSelectedIndex(language);((JComboBox<?>)FeatureTest.field(app,"themeBox")).setSelectedIndex(theme);layout(app);bounded(split,"language/theme");
                    check(split.getToolTipText().equals(I18n.text("Trage separatorul pentru a redimensiona mesajul. Maxim: aceeasi inaltime ca raspunsurile.")),"translated resize description");check(split.preferredInputHeight()==preference,"theme changes preserve height preference");
                }
                check(text.getText().equals(draft)&&text.getSelectionStart()==3&&text.getSelectionEnd()==12,"resizing preserves exact draft and selection");check(AttachmentTest.state(app).draftAttachments.size()==1,"resizing preserves selected attachments");check(server.requests.isEmpty(),"resize, theme and persistence create no API requests");
                FeatureTest.call(app,"saveConfig",new Class<?>[]{});app.dispose();
                LocalChat restored=AttachmentTest.window(local,server.url());restored.setSize(1320,950);restored.addNotify();layout(restored);ComposerSplit resumed=FeatureTest.field(restored,"composerSplit");check(resumed.preferredInputHeight()==preference,"size restored after application restart");bounded(resumed,"restored");resumed.restore("broken");layout(restored);check(resumed.preferredInputHeight()==ComposerSplit.DEFAULT,"invalid preference uses readable default");restored.dispose();
            });System.out.println("PASS: "+checks+" composer resize checks; viewport limits, six languages, themes, persistence and draft isolation");
        }finally{FeatureTest.edt(()->{for(var w:Window.getWindows())w.dispose();});}System.exit(0);
    }
}
