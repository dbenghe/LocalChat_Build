package localchat;
import javax.swing.*;
import javax.swing.text.*;
import java.nio.file.*;
import java.util.*;
public final class ChatDisplayTest{
    static int checks;static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    public static void main(String[] args)throws Exception{
        Path home=Path.of(System.getProperty("localchat.home"));Files.createDirectories(home);
        String tree="**Structura folderului actuală:**\n```\nPDFCONTROL_Rapoarte/\n└── 505417/\n    └── RAPPORT.pdf\n```\n";
        String originalPath="C:/Șantier/Încercări/raport șuruburi ț.pdf";String raw=tree+originalPath+"\nĂÂÎȘȚ ăâîșț şţ ŞŢ";
        try{FeatureTest.edt(()->{
            for(String code:I18n.CODES){I18n.setLanguage(code);String shown=ChatDisplay.text(raw);check(shown.length()==raw.length(),"selection offsets unchanged in "+code);check(code.equals("ro")?!shown.matches("(?s).*[ăâîșşțţĂÂÎȘŞȚŢ\u2500-\u257f].*"):shown.equals(raw),"plain Romanian view only, preserving other languages");}
            LocalChat app=AttachmentTest.window(home,"http://127.0.0.1:1/v1");((JComboBox<?>)FeatureTest.field(app,"languageBox")).setSelectedIndex(1);I18n.setLanguage("ro");
            JTextPane transcript=FeatureTest.field(app,"transcript");AttributeSet body=FeatureTest.field(app,"body");
            FeatureTest.call(app,"append",new Class<?>[]{String.class,AttributeSet.class},raw.substring(0,20),body);FeatureTest.call(app,"append",new Class<?>[]{String.class,AttributeSet.class},raw.substring(20),body);
            check(transcript.getDocument().getText(0,transcript.getDocument().getLength()).equals(ChatDisplay.text(raw)),"streamed chat content converted at display boundary");check(transcript.getText().contains("+-- 505417/"),"folder tree renders with ordinary visible characters");
            int start=raw.indexOf(originalPath);transcript.select(start,start+originalPath.length());check(FeatureTest.call(app,"originalSelection",new Class<?>[]{}).equals(originalPath),"original accented file path copied from simplified selection");
            DefaultStyledDocument original=FeatureTest.field(app,"originalTranscript");check(original.getText(0,original.getLength()).equals(raw),"raw transcript retains exact model content and paths");
            ((JComboBox<?>)FeatureTest.field(app,"languageBox")).setSelectedIndex(4);check(transcript.getDocument().getText(0,transcript.getDocument().getLength()).equals(raw),"switching language restores original text without rereading or API calls");
            ((JComboBox<?>)FeatureTest.field(app,"languageBox")).setSelectedIndex(1);check(transcript.getDocument().getText(0,transcript.getDocument().getLength()).equals(ChatDisplay.text(raw)),"returning to Romanian simplifies existing history again");
            for(int theme=0;theme<2;theme++){
                ((JComboBox<?>)FeatureTest.field(app,"themeBox")).setSelectedIndex(theme);check(transcript.getDocument().getText(0,transcript.getDocument().getLength()).equals(ChatDisplay.text(raw)),"theme change preserves simplified text");check(original.getText(0,original.getLength()).equals(raw),"theme change preserves original transcript");
                JPopupMenu menu=FeatureTest.field(app,"transcriptPopup");check(!menu.getBackground().equals(menu.getForeground()),"copy menu contrast in both themes");
                app.addNotify();app.validate();var image=new java.awt.image.BufferedImage(app.getWidth(),app.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();app.getRootPane().printAll(g);g.dispose();Path images=Files.createDirectories(home.resolve("screenshots"));javax.imageio.ImageIO.write(image,"png",images.resolve("ro-"+theme+".png").toFile());
            }
            List<ChatClient.Message> history=FeatureTest.field(app,"history");history.add(ChatClient.Message.user("Exact path: "+originalPath));history.add(ChatClient.Message.assistant(raw,List.of(),"fixture"));
            FeatureTest.call(app,"ensureSession",new Class<?>[]{});FeatureTest.call(app,"saveConversation",new Class<?>[]{});Path saved=FeatureTest.field(app,"conversationFile");var loaded=Conversations.load(saved);check(loaded.messages().get(0).content.contains(originalPath)&&loaded.messages().get(1).content.equals(raw),"conversation JSON retains original model text and paths");
            FeatureTest.call(app,"renderHistory",new Class<?>[]{});check(!transcript.getText().matches("(?s).*[ăâîșşțţĂÂÎȘŞȚŢ\u2500-\u257f].*"),"saved old conversations displayed without Romanian accents or missing tree glyphs");
            FeatureTest.command(app,"/new");check(transcript.getDocument().getLength()==0&&original.getLength()==0,"new chat clears both display and original transcript");app.dispose();
        });System.out.println("PASS: "+checks+" chat display checks; streaming, selection offsets, original copying, saved data and both themes");}finally{FeatureTest.edt(()->{for(var window:java.awt.Window.getWindows())window.dispose();});}System.exit(0);
    }
}
