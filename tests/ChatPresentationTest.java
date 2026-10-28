package localchat;
import javax.swing.*;
import javax.swing.text.*;
import java.nio.file.*;
import java.util.*;
import java.awt.datatransfer.*;
public final class ChatPresentationTest{
    static int checks;
    static void check(boolean b,String text){checks++;if(!b)throw new AssertionError(text);}
    static AttributeSet at(JTextPane pane,String text)throws Exception{return pane.getStyledDocument().getCharacterElement(pane.getDocument().getText(0,pane.getDocument().getLength()).indexOf(text)).getAttributes();}
    /** The Windows system clipboard can be held open by another process for a moment (clipboard managers, remote sessions); retry for a bounded time instead of failing a run that has nothing to do with it. */
    static <T> T clip(java.util.concurrent.Callable<T> action)throws Exception{long end=System.nanoTime()+10_000_000_000L;while(true)try{return action.call();}catch(IllegalStateException locked){if(System.nanoTime()>end)throw locked;Thread.sleep(100);}}
    public static void main(String[] args)throws Exception{
        var clipboard=java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();Transferable savedClipboard=clip(()->ComposerClipboard.contents(clipboard));Map<DataFlavor,Object> savedData=new LinkedHashMap<>();if(savedClipboard!=null)for(DataFlavor flavor:savedClipboard.getTransferDataFlavors())try{savedData.put(flavor,savedClipboard.getTransferData(flavor));}catch(Exception ignored){}
        Path home=Files.createDirectories(Path.of(System.getProperty("localchat.home")));
        Path root=Files.createDirectories(home.resolve("files"));Path control=Files.writeString(root.resolve("RAPPORT.pdf"),"test PDF");Path compare=Files.writeString(root.resolve("Vergelijking_OUD_NIEUW.pdf"),"test PDF");
        String result=Json.write(Json.obj("job_id","check-fixture","status","done","delivery_verified",true,"report",control.toString(),"differences_report",compare.toString(),"source_files",List.of("input.pdf"),"artifacts",List.of(Json.obj("path",compare.toString()))));
        check(ReportFiles.discover(result,root).equals(List.of(control,compare)),"both PDF outputs, not source files");
        for(String status:List.of("pending","running","failed","cancelled"))check(ReportFiles.discover(result.replace("\"done\"","\""+status+"\""),root).isEmpty(),"no unconfirmed PDFs "+status);
        check(ReportFiles.discover(result.replace("\"delivery_verified\":true","\"delivery_verified\":false"),root).isEmpty(),"unverified output hidden");
        check(ReportFiles.discover(result,home.resolve("different")).isEmpty(),"outside root hidden");
        var transfer=new FileClipboard(control);check(transfer.getTransferData(DataFlavor.javaFileListFlavor).equals(List.of(control.toFile())),"Explorer file transfer");check(transfer.getTransferData(DataFlavor.stringFlavor).equals(control.toString()),"exact path fallback");
        String md="# Raport verificare\n\n**Important** si *italic* si ~~vechi~~ si _subliniat_. [Documentatie](https://example.invalid/help). [Fisier original](C:/Șantier/raport.pdf).\n\n- primul\n- al doilea\n\n> Nota\n\n| Desen | Status |\n| --- | --- |\n| TRK300 | OK |\n| TRK301 | Control manual |\n\n```java\nString x = \"**literal**\";\n```\n\n`cod **literal**`\n";
        try{FeatureTest.edt(()->{
            LocalChat app=AttachmentTest.window(home,"http://127.0.0.1:1/v1");((JTextField)FeatureTest.field(app,"workDirField")).setText(root.toString());
            JTextPane pane=FeatureTest.field(app,"transcript");var history=(List<ChatClient.Message>)FeatureTest.<List<ChatClient.Message>>field(app,"history");
            history.add(ChatClient.Message.user("Verifica si compara"));history.add(ChatClient.Message.assistant(md,List.of(new ChatClient.ToolCall("call","check",Json.obj())),"Fixture"));history.add(ChatClient.Message.toolResult("call",result,false));
            FeatureTest.call(app,"renderHistory",new Class<?>[]{});FeatureTest.call(app,"formatMarkdown",new Class<?>[]{});
            check(StyleConstants.isBold(at(pane,"Important")),"bold rendered");check(!StyleConstants.isBold(at(pane,"primul")),"normal list body is not bold");check("C:/Șantier/raport.pdf".equals(at(pane,"Fisier original").getAttribute("markdown.href")),"Markdown link preserves original path despite display normalization");check(!MarkdownChat.visibleText(pane.getStyledDocument(),0,pane.getDocument().getLength()).contains("**Important**"),"display copy omits Markdown delimiters");check(StyleConstants.isItalic(at(pane,"italic")),"italic rendered");check(StyleConstants.isItalic(at(pane,"subliniat")),"underscore emphasis rendered");check("https://example.invalid/help".equals(at(pane,"Documentatie").getAttribute("markdown.href")),"Markdown link rendered without loading external content");check(StyleConstants.isStrikeThrough(at(pane,"vechi")),"strike rendered");check(StyleConstants.getFontSize(at(pane,"Raport verificare"))>15,"heading rendered");
            check(StyleConstants.getFontFamily(at(pane,"String x")).equals("Monospaced"),"fenced code rendered");check(!StyleConstants.isBold(at(pane,"literal")),"code not interpreted as Markdown");
            check(StyleConstants.getFontFamily(at(pane,"TRK300")).equals("Monospaced"),"table rendered");check(Boolean.TRUE.equals(at(pane,"**Important").getAttribute(MarkdownChat.HIDE)),"syntax visually hidden");
            var original=(DefaultStyledDocument)FeatureTest.field(app,"originalTranscript");check(original.getText(0,original.getLength()).contains(md),"original Markdown retained");
            var model=(DefaultComboBoxModel<Path>)FeatureTest.<DefaultComboBoxModel<Path>>field(app,"reportModel");check(model.getSize()==2,"both report files available");
            int links=0;for(int n=0;n<pane.getDocument().getLength();){var e=pane.getStyledDocument().getCharacterElement(n);if(e.getAttributes().isDefined("artifact.path"))links++;n=e.getEndOffset();}check(links==8,"four inline actions per PDF");
            for(String language:I18n.CODES){((JComboBox<?>)FeatureTest.field(app,"languageBox")).setSelectedIndex(Arrays.asList(I18n.CODES).indexOf(language));for(String key:List.of("Copiaza raspunsul","Copiaza fisierul","Copiaza calea"))check(I18n.contains(key)&&!I18n.text(key).isBlank(),"translated action "+language);check(pane.getDocument().getText(0,pane.getDocument().getLength()).contains("["+I18n.text("Copiaza fisierul")+"]"),"inline PDF actions translate immediately in "+language);}
            for(int theme=0;theme<2;theme++){
                ((JComboBox<?>)FeatureTest.field(app,"themeBox")).setSelectedIndex(theme);FeatureTest.call(app,"formatMarkdown",new Class<?>[]{});check(StyleConstants.isBold(at(pane,"Important")),"Markdown survives theme");
                app.addNotify();app.validate();var image=new java.awt.image.BufferedImage(app.getWidth(),app.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();app.getRootPane().printAll(g);g.dispose();javax.imageio.ImageIO.write(image,"png",home.resolve("markdown-"+theme+".png").toFile());
                var scroll=(JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,pane);if(scroll!=null){scroll.getViewport().setViewPosition(new java.awt.Point(0,Math.max(0,pane.getHeight()-scroll.getViewport().getHeight())));var bottom=new java.awt.image.BufferedImage(app.getWidth(),app.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);var bg=bottom.createGraphics();app.getRootPane().printAll(bg);bg.dispose();javax.imageio.ImageIO.write(bottom,"png",home.resolve("pdf-actions-"+theme+".png").toFile());}
            }
            FeatureTest.call(app,"appendMarkdown",new Class<?>[]{String.class},"\n**Stream");FeatureTest.call(app,"formatMarkdown",new Class<?>[]{});FeatureTest.call(app,"appendMarkdown",new Class<?>[]{String.class},"ing**\n");FeatureTest.call(app,"formatMarkdown",new Class<?>[]{});check(StyleConstants.isBold(at(pane,"Streaming")),"split streaming delimiter rendered");
            check(history.get(1).content.equals(md),"history never modified");FeatureTest.call(app,"copyResponse",new Class<?>[]{});check(clip(()->java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor)).equals(md),"copy response exact Markdown");
            FeatureTest.call(app,"reportAction",new Class<?>[]{Path.class,int.class},compare,3);check(clip(()->java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.javaFileListFlavor)).equals(List.of(compare.toFile())),"inline action copies real comparison file");
            FeatureTest.call(app,"ensureSession",new Class<?>[]{});FeatureTest.call(app,"saveConversation",new Class<?>[]{});Path saved=FeatureTest.field(app,"conversationFile");
            var archive=Conversations.load(saved);check(archive.messages().get(1).content.equals(md),"saved archive retains original Markdown without UI actions");
            FeatureTest.command(app,"/new");FeatureTest.command(app,"/switch "+Conversations.shortId(saved));FeatureTest.call(app,"formatMarkdown",new Class<?>[]{});check(model.getSize()==2&&StyleConstants.isBold(at(pane,"Important")),"saved chat restores both PDFs and Markdown without inference");
            FeatureTest.command(app,"/new");check(pane.getDocument().getLength()==0&&model.getSize()==0,"no cross-chat artifact or Markdown state");app.dispose();
        });System.out.println("PASS: "+checks+" Markdown/PDF presentation checks");}finally{FeatureTest.edt(()->{for(var w:java.awt.Window.getWindows())w.dispose();});if(!savedData.isEmpty())clipboard.setContents(new Transferable(){public DataFlavor[] getTransferDataFlavors(){return savedData.keySet().toArray(DataFlavor[]::new);}public boolean isDataFlavorSupported(DataFlavor f){return savedData.containsKey(f);}public Object getTransferData(DataFlavor f)throws UnsupportedFlavorException{if(!savedData.containsKey(f))throw new UnsupportedFlavorException(f);return savedData.get(f);}},null);}System.exit(0);
    }
}
