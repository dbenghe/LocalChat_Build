package localchat;
import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Offline UI regression tests. Uses only a fresh test-home, never actual user settings or APIs. */
public final class UiTest {
    private static int checks;
    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }
    @SuppressWarnings("unchecked") private static <T> T field(Object target, String name) throws Exception {
        Field f=target.getClass().getDeclaredField(name); f.setAccessible(true); return (T)f.get(target);
    }
    private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m=target.getClass().getDeclaredMethod(name,types); m.setAccessible(true); return m.invoke(target,args);
    }
    private static LocalChat window() throws Exception {
        Constructor<LocalChat> c=LocalChat.class.getDeclaredConstructor(); c.setAccessible(true); return c.newInstance();
    }
    public static void main(String[] args) throws Exception {
        Path home=Path.of(System.getProperty("localchat.home")); Files.createDirectories(home);
        Throwable[] failure={null};
        SwingUtilities.invokeAndWait(() -> { try { run(home); } catch(Throwable t) { failure[0]=t; }
            finally { for(Window w:Window.getWindows()) w.dispose(); } });
        if (failure[0]!=null) { failure[0].printStackTrace(); System.exit(1); }
        System.out.println("PASS: " + checks + " checks; 6 languages x 2 themes, persistence and transcript safety");
        System.exit(0);
    }
    private static void run(Path home) throws Exception {
        check(AppVersion.VALUE.matches("[0-9]+\\.[0-9]+\\.[0-9]+"),"semantic version");
        for(String code:I18n.CODES) {
            check(I18n.keys(code).equals(I18n.keys("ro")),"catalog coverage: "+code);
            I18n.setLanguage(code);
            for(String key:I18n.keys(code)) check(!I18n.text(key).isBlank(),"nonempty translation: "+code+":"+key);
            for(String token : new String[]{"{0}","{1}"}) for(String k:I18n.keys(code))
                check(k.contains(token) == I18n.text(k).contains(token), "format arguments: "+code+":"+k);
            check(I18n.text("Nu pot deschide {0}: {1}","C:/a/{1}.pdf","$\\result").contains("C:/a/{1}.pdf"),"literal placeholder argument");
        }
        for(String code:I18n.CODES){
            I18n.setLanguage(code);
            check(I18n.text("Conversații").equals(I18n.text("Conversatii")),"accented and plain application keys translate equally in "+code);
        }
        I18n.setLanguage("ro");
        for(String key:I18n.keys("ro"))check(!I18n.text(key).matches("(?s).*[ăâîșşțţĂÂÎȘŞȚŢ].*"),"Romanian application copy has no Romanian diacritics: "+key);
        check(I18n.romanianAscii("ăâîșşțţ ĂÂÎȘŞȚŢ").equals("aaisstt AAISSTT"),"modern and legacy Romanian accents normalized");
        check(I18n.NAMES[1].equals("Romana"),"plain Romanian language name");
        String originalPath="C:/Șantier/Încercări/raport șuruburi ț.pdf",originalText="Conținut şi ținte: {1} $";
        check(I18n.text("Nu pot deschide {0}: {1}",originalPath,originalText).equals("Nu pot deschide "+originalPath+": "+originalText),"dynamic paths and document/error text remain byte-for-byte unchanged");
        check(I18n.text("Mesaj nou cu șțîăâ {0}",originalPath).equals("Mesaj nou cu stiaa "+originalPath),"uncatalogued Romanian application template normalized before interpolation");
        I18n.setLanguage("tr");check(I18n.text("Șterge conversația").equals("Sohbeti sil"),"Turkish translation preserved after source key normalization");
        I18n.setLanguage("es");check(I18n.text("Limbă").equals("Idioma"),"Spanish translation preserved");
        I18n.setLanguage("unknown"); check(I18n.language().equals("ro"),"unknown locale fallback");
        LocalChat app=window();
        check(app.getTitle().contains(AppVersion.VALUE),"version in title");
        JComboBox<String> languages=field(app,"languageBox"), themes=field(app,"themeBox"), permissions=field(app,"permissionBox"), model=field(app,"modelBox");
        JTextArea input=field(app,"input"); JTextPane transcript=field(app,"transcript");
        JTextField system=field(app,"systemField"),url=field(app,"urlField"),work=field(app,"workDirField");
        JPasswordField key=field(app,"keyField"); JButton send=field(app,"sendButton");
        input.setText("Draft Română Nederlands Türkçe / C:/PROJECT/{0}.pdf");
        system.setText("User system instructions"); url.setText("http://localhost:1234/v1");
        work.setText(home.toString()); key.setText("test-only-not-a-real-key");
        model.getEditor().setItem("custom-unsaved-model"); permissions.setSelectedIndex(1);
        SimpleAttributeSet body=field(app,"body"), error=field(app,"errorStyle");
        call(app,"append",new Class<?>[]{String.class,AttributeSet.class},"Original assistant text / partial streaming",body);
        call(app,"append",new Class<?>[]{String.class,AttributeSet.class},"\nOriginal error text",error);
        String before=transcript.getText();
        call(app,"setBusy",new Class<?>[]{boolean.class},true);
        JButton stop=field(app,"stopButton"), newChat=field(app,"newChatButton");
        call(app,"setStatus",new Class<?>[]{String.class,boolean.class,Object[].class},"Rulez {0}…",false,new Object[]{"read_file"});
        Path images=home.getParent().resolve("test-artifacts");Files.createDirectories(images);
        for(int i=0;i<6;i++) {
            check(languages.getItemAt(i).equals(I18n.NAMES[i]),"language order "+i);
            languages.setSelectedIndex(i);
            for(int mode=0;mode<2;mode++) {
                themes.setSelectedIndex(mode);
                check(I18n.language().equals(I18n.CODES[i]),"selected locale");
                check(send.getText().isEmpty() && send.getIcon()!=null && send.getToolTipText().equals(I18n.text("Adauga mesajul in coada")),"icon send with translated accessible tooltip");
                check(app.getJMenuBar().getMenu(0).getItem(0).getText().equals(I18n.text("Conversație nouă")),"translated menu");
                JLabel status=field(app,"status");
                check(status.getText().equals(I18n.text("Rulez {0}…","read_file")),"translated dynamic status");
                check(transcript.getText().equals(before),"streaming transcript preserved");
                check(send.isEnabled() && stop.isEnabled() && newChat.isEnabled(),"busy controls retain Stop, allow queue submission and independent new chat");
                Color errorColor=StyleConstants.getForeground(transcript.getStyledDocument().getCharacterElement(before.length()-2).getAttributes());
                check(errorColor.equals(new Color(mode==1?0xFF9C9C:0xB42332)),"existing errors recolored");
                check(key.getEchoChar()!=0,"API key remains masked");
                check(input.getText().startsWith("Draft Română"),"draft preserved");
                check(system.getText().equals("User system instructions"),"system prompt preserved");
                check(url.getText().equals("http://localhost:1234/v1"),"URL preserved");
                check(new String(key.getPassword()).equals("test-only-not-a-real-key"),"key preserved");
                check(model.getEditor().getItem().equals("custom-unsaved-model"),"custom model preserved");
                check(permissions.getSelectedIndex()==1,"permission semantics preserved");
                check(transcript.getBackground().equals(new Color(mode==1?0x1B1F26:0xFFFFFF)),"theme background");
                Color text=StyleConstants.getForeground(transcript.getStyledDocument().getCharacterElement(0).getAttributes());
                check(text.equals(new Color(mode==1?0xEDF1F7:0x202733)),"existing body recolored");
                check(!input.getForeground().equals(input.getBackground()),"text visible");
                app.addNotify(); app.validate();
                check(languages.getWidth()>=languages.getPreferredSize().width && themes.getWidth()>=themes.getPreferredSize().width,"appearance controls laid out");
                check(send.getWidth()==34 && send.getHeight()==32,"compact send icon size");
                BusyIndicator activity=field(app,"busyIndicator");check(activity.active && activity.isVisible() && activity.animation.isRunning(),"busy spinner animates while generating");
                JButton folder=field(app,"downloadsButton");check(folder.getText().equals(I18n.text("Deschide {0}",home.getFileName())),"folder action follows selected workspace in every language");
                check(((JButton)field(app,"newChatButton")).getIcon()!=null,"new conversation icon");
                for(String name:new String[]{"contextRing","bottomModel","responseProfile","bottomMode","bottomApproval"}) {
                    JComponent control=field(app,name);Point point=SwingUtilities.convertPoint(control,0,0,app.getRootPane());
                    check(control.getWidth()>30 && point.x>=0 && point.x+control.getWidth()<=app.getRootPane().getWidth(),"footer visible: "+name);
                    check(name.equals("contextRing") || !control.isEnabled(),"footer respects busy state: "+name);
                }
                BufferedImage image=new BufferedImage(app.getWidth(),app.getHeight(),BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics=image.createGraphics();app.getRootPane().printAll(graphics);graphics.dispose();
                check(image.getRGB(20,40) != Color.BLACK.getRGB(),"render contains UI");
                ImageIO.write(image,"png",images.resolve(I18n.CODES[i]+"-"+(mode==1?"dark":"light")+".png").toFile());
            }
        }
        app.dispose();
        LocalChat restored=window();
        check(I18n.language().equals("tr"),"locale restored after restart");
        check(((JComboBox<?>)field(restored,"themeBox")).getSelectedIndex()==1,"theme restored after restart");
        check(((JComboBox<?>)field(restored,"permissionBox")).getSelectedIndex()==1,"permission restored");
        check(((JComboBox<?>)field(restored,"modelBox")).getEditor().getItem().equals("custom-unsaved-model"),"model restored");
        // Defaults and invalid settings remain compatible with existing installations.
        Properties props=new Properties();
        try(var in=Files.newInputStream(home.resolve("setari.properties"))) {props.load(in);}
        props.setProperty("ui.language","invalid");props.setProperty("ui.theme","invalid");
        try(var out=Files.newOutputStream(home.resolve("setari.properties"))) {props.store(out,"Test only");}
        restored.dispose(); LocalChat fallback=window();
        check(I18n.language().equals("ro"),"invalid saved locale fallback");
        check(((JComboBox<?>)field(fallback,"themeBox")).getSelectedIndex()==0,"invalid saved theme fallback");
        fallback.dispose();
    }
}
