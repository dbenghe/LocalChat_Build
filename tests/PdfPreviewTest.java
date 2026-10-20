package localchat;
import javax.swing.*;
import javax.swing.text.*;
import java.nio.file.*;
import java.util.*;
import java.awt.Window;
import java.awt.image.BufferedImage;
public final class PdfPreviewTest{
    static int checks;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
    static void waitFor(PdfPreview panel)throws Exception{for(int n=0;n<500;n++){boolean[] done={false};FeatureTest.edt(()->done[0]=!panel.loading());if(done[0]){Thread.sleep(300);FeatureTest.edt(()->done[0]=!panel.loading());if(done[0])return;}Thread.sleep(25);}throw new AssertionError("Preview worker timed out");}
    public static void main(String[] args)throws Exception{
        Path home=Files.createDirectories(Path.of(System.getProperty("localchat.home"))),root=Files.createDirectories(home.resolve("project"));String python=System.getProperty("pdfpreview.python");
        Path pdf=root.resolve("Raport șantier ' [a].pdf"),other=root.resolve("Compare.pdf"),broken=Files.writeString(root.resolve("broken.pdf"),"invalid");
        Process fixture=new ProcessBuilder(python,"-I","-c","import pymupdf,sys; d=pymupdf.open(); p=d.new_page(width=480,height=640); p.insert_text((40,60),'PDF REPORT - PAGE 1',fontsize=20); p.draw_rect(pymupdf.Rect(40,100,440,500),color=(0,0,1)); p=d.new_page(width=640,height=480); p.insert_text((40,60),'DRAWING - PAGE 2',fontsize=20); p.draw_rect(pymupdf.Rect(60,100,500,350),color=(1,0,0)); d.save(sys.argv[1]); d.close()",pdf.toString()).inheritIO().start();check(fixture.waitFor()==0,"real local multipage PDF fixture");Files.copy(pdf,other,StandardCopyOption.REPLACE_EXISTING);Path outside=Files.copy(pdf,home.resolve("outside.pdf"),StandardCopyOption.REPLACE_EXISTING);
        Path huge=root.resolve("large-page.pdf"),encrypted=root.resolve("encrypted.pdf");
        Process safety=new ProcessBuilder(python,"-I","-c","import pymupdf,sys; d=pymupdf.open(); p=d.new_page(width=20000,height=20000); p.insert_text((50,80),'LARGE PAGE'); d.save(sys.argv[1]); d.save(sys.argv[2],encryption=pymupdf.PDF_ENCRYPT_AES_256,user_pw='test-only'); d.close()",huge.toString(),encrypted.toString()).inheritIO().start();check(safety.waitFor()==0,"large and encrypted PDF fixtures");
        PdfRenderer renderer=new PdfRenderer();BufferedImage bounded=renderer.render(huge,python,1,100000,10000);check(bounded.getWidth()<=4096&&bounded.getHeight()<=4096&&(long)bounded.getWidth()*bounded.getHeight()<=10000000,"oversized drawing raster bounded before allocation");
        try{renderer.info(encrypted,python);throw new AssertionError("encrypted PDF accepted");}catch(java.io.IOException expected){check(true,"encrypted PDF rejected without password requests or API");}
        PdfRenderer cancelled=new PdfRenderer();cancelled.cancel();try{cancelled.info(pdf,python);throw new AssertionError("cancel ignored");}catch(java.io.InterruptedIOException expected){check(true,"cancel before start prevents rendering");}
        byte[] original=Files.readAllBytes(pdf);LocalChat[] app={null};PdfPreview[] panel={null};PdfReportCard[] card={null};
        try(var server=new FeatureTest.Server()){
            FeatureTest.edt(()->{
                app[0]=AttachmentTest.window(home,server.url());var config=LocalChat.class.getDeclaredField("toolsConfig");config.setAccessible(true);config.set(app[0],Json.obj("pdfPython",python));
                ((JTextField)FeatureTest.field(app[0],"workDirField")).setText(root.toString());((JComboBox<?>)FeatureTest.field(app[0],"languageBox")).setSelectedIndex(1);
                var history=FeatureTest.<List<ChatClient.Message>>field(app[0],"history");String result=Json.write(Json.obj("job_id","fixture","status","done","delivery_verified",true,"report",pdf.toString()));history.add(ChatClient.Message.user("Genereaza raport PDF"));history.add(ChatClient.Message.assistant("## Raport generat\nPDF disponibil mai jos.",List.of(new ChatClient.ToolCall("pdf","pdfcontrol_start_check",Json.obj())),"Fixture"));history.add(ChatClient.Message.toolResult("pdf",result,false));
                FeatureTest.call(app[0],"renderHistory",new Class<?>[]{});JTextPane transcript=FeatureTest.field(app[0],"transcript");var doc=transcript.getStyledDocument();for(int n=0;n<doc.getLength();){var element=doc.getCharacterElement(n);if(StyleConstants.getComponent(element.getAttributes()) instanceof PdfReportCard c)card[0]=c;n=element.getEndOffset();}
                check(card[0]!=null&&card[0].file.equals(pdf),"report card retains exact Unicode file path");panel[0]=FeatureTest.field(app[0],"pdfPreview");check(panel[0].file()==null&&!panel[0].isVisible(),"restoring cards does not render or open PDF");
                app[0].setSize(1600,1000);app[0].addNotify();app[0].validate();card[0].preview.doClick();check(panel[0].isVisible(),"inline card opens right panel");
            });waitFor(panel[0]);
            FeatureTest.edt(()->{check(panel[0].pages()==2&&panel[0].image.getIcon()!=null,"real PDF rendered locally");check(panel[0].next.isEnabled()&&!panel[0].previous.isEnabled(),"first-page navigation state");panel[0].next.doClick();});waitFor(panel[0]);
            FeatureTest.edt(()->{check(panel[0].page.getValue().equals(2)&&!panel[0].next.isEnabled(),"next page and final-page bounds");check(panel[0].previous.isEnabled(),"previous page enabled");panel[0].zoom.setSelectedItem("200%");});waitFor(panel[0]);
            FeatureTest.edt(()->{
                check(((ImageIcon)panel[0].image.getIcon()).getIconWidth()>500,"zoom rerenders page");
                for(int language=0;language<I18n.CODES.length;language++){((JComboBox<?>)FeatureTest.field(app[0],"languageBox")).setSelectedIndex(language);check(card[0].preview.getText().equals(I18n.text("Previzualizeaza")),"card translated "+I18n.CODES[language]);check(panel[0].save.getToolTipText().equals(I18n.text("Salveaza o copie…")),"viewer translated "+I18n.CODES[language]);check(((JButton)FeatureTest.field(app[0],"chatActions")).getToolTipText().equals(I18n.text("Actiuni conversatie")),"conversation action icon translated");check(panel[0].addTab.getToolTipText().equals(I18n.text("Deschide alt PDF")),"add-tab icon translated");}
                ((JComboBox<?>)FeatureTest.field(app[0],"languageBox")).setSelectedIndex(1);
                for(int theme=0;theme<2;theme++){((JComboBox<?>)FeatureTest.field(app[0],"themeBox")).setSelectedIndex(theme);check(!card[0].getBackground().equals(card[0].title.getForeground()),"card contrast in theme "+theme);app[0].validate();var shot=new BufferedImage(app[0].getWidth(),app[0].getHeight(),BufferedImage.TYPE_INT_RGB);var graphics=shot.createGraphics();app[0].getRootPane().printAll(graphics);graphics.dispose();javax.imageio.ImageIO.write(shot,"png",home.resolve("preview-"+theme+".png").toFile());}
                panel[0].close.doClick();check(!panel[0].isVisible()&&panel[0].file()!=null,"hide panel retains its PDF tab");app[0].getRootPane().getActionMap().get("toggle-pdf-pane").actionPerformed(new java.awt.event.ActionEvent(app[0],0,"test"));check(panel[0].isVisible(),"Ctrl+Shift+B binding restores hidden PDF tabs");
                panel[0].expand.doClick();check(!((JSplitPane)FeatureTest.field(app[0],"chatSplit")).getLeftComponent().isVisible(),"expand uses full chat region");panel[0].expand.doClick();check(((JSplitPane)FeatureTest.field(app[0],"chatSplit")).getLeftComponent().isVisible(),"restore returns split view");
                FeatureTest.call(app[0],"showPdfPreview",new Class<?>[]{Path.class},pdf);FeatureTest.call(app[0],"showPdfPreview",new Class<?>[]{Path.class},other);
            });waitFor(panel[0]);
            FeatureTest.edt(()->{
                check(panel[0].file().equals(other)&&panel[0].pages()==2,"stale worker cannot replace newer PDF");check(panel[0].tabCount()==2,"multiple PDFs have separate tabs");
                int count=panel[0].tabCount();FeatureTest.call(app[0],"showPdfPreview",new Class<?>[]{Path.class},other);check(panel[0].tabCount()==count,"opening an existing PDF reuses its tab");panel[0].closeTab(pdf);check(panel[0].tabCount()==1&&panel[0].file().equals(other),"inactive PDF tab closes without changing active document");FeatureTest.call(app[0],"showPdfPreview",new Class<?>[]{Path.class},pdf);panel[0].closeTab(pdf);check(panel[0].tabCount()==1&&panel[0].file().equals(other),"closing active tab selects remaining PDF");
                var quick=(JPopupMenu)FeatureTest.call(app[0],"buildQuickSettings",new Class<?>[]{});((JMenu)quick.getComponent(1)).getItem(0).doClick();check(((JComboBox<?>)FeatureTest.field(app[0],"responseProfile")).getSelectedIndex()==0,"sliders menu changes real response profile");
                boolean tools=((JCheckBox)FeatureTest.field(app[0],"toolsBox")).isSelected();((JMenuItem)quick.getComponent(3)).doClick();check(((JCheckBox)FeatureTest.field(app[0],"toolsBox")).isSelected()!=tools,"sliders menu toggles actual tools setting");
                var menu=(JPopupMenu)FeatureTest.call(app[0],"buildChatActions",new Class<?>[]{});boolean pinned=((Conversations.State)FeatureTest.field(app[0],"sessionState")).pinned;((JMenuItem)menu.getComponent(1)).doClick();check(((Conversations.State)FeatureTest.field(app[0],"sessionState")).pinned!=pinned,"three dots menu pins actual conversation");
                FeatureTest.call(app[0],"showPdfPreview",new Class<?>[]{Path.class},pdf);var busy=LocalChat.class.getDeclaredField("busy");busy.setAccessible(true);busy.setBoolean(app[0],true);
                FeatureTest.call(app[0],"addReports",new Class<?>[]{String.class},Json.write(Json.obj("job_id","new-comparison","status","done","delivery_verified",true,"report",other.toString())));busy.setBoolean(app[0],false);check(panel[0].file().equals(other),"new verified delivery opens the preview during a tool turn");
                FeatureTest.call(app[0],"showPdfPreview",new Class<?>[]{Path.class},outside);check(panel[0].file().equals(other),"outside-workspace PDF rejected before open");
                ((JTextArea)FeatureTest.field(app[0],"input")).setText("mesaj netrimis");FeatureTest.call(app[0],"showPdfPreview",new Class<?>[]{Path.class},broken);
            });waitFor(panel[0]);
            FeatureTest.edt(()->{
                check(panel[0].image.getIcon()==null&&panel[0].status.getText().equals(I18n.text("Previzualizarea PDF nu este disponibila. Poti deschide fisierul extern.")),"invalid PDF gives localized fallback");check(panel[0].external.isEnabled(),"external-open action remains available");
                ((JTextField)FeatureTest.field(app[0],"workDirField")).setText(home.toString());check(!panel[0].isVisible()&&panel[0].file()==null,"workspace change clears preview");check(((JTextArea)FeatureTest.field(app[0],"input")).getText().equals("mesaj netrimis"),"preview preserves draft");
                FeatureTest.call(app[0],"showPdfPreview",new Class<?>[]{Path.class},pdf);FeatureTest.command(app[0],"/new");check(panel[0].file()==null&&!panel[0].isVisible(),"new chat cancels and clears preview");
                FeatureTest.call(app[0],"showPdfPreview",new Class<?>[]{Path.class},pdf);app[0].setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);app[0].dispatchEvent(new java.awt.event.WindowEvent(app[0],java.awt.event.WindowEvent.WINDOW_CLOSING));check(panel[0].file()==null&&!panel[0].loading(),"application close stops preview before JVM exit");app[0].dispose();
            });check(server.requests.isEmpty(),"preview has zero API requests or billed token usage");check(Arrays.equals(original,Files.readAllBytes(pdf)),"original PDF never changed");
        }finally{FeatureTest.edt(()->{for(var window:Window.getWindows())window.dispose();});}System.out.println("PASS: "+checks+" inline PDF card and right preview checks; actual PDF renderer, navigation, zoom, six languages, themes and cancellation");System.exit(0);
    }
}
