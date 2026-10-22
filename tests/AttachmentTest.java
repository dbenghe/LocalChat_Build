package localchat;
import java.awt.datatransfer.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;

/** Offline file selection, native file-list transfers, session isolation and real tool reading. */
public final class AttachmentTest {
    static int checks;
    static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
    static LocalChat window(Path home,String url)throws Exception{
        Files.createDirectories(home);System.setProperty("localchat.home",home.toString());
        var ctor=LocalChat.class.getDeclaredConstructor();ctor.setAccessible(true);LocalChat app=ctor.newInstance();
        ProviderTest.set(app,Provider.CUSTOM,url,"attachment-fixture","");
        ((JTextField)FeatureTest.field(app,"workDirField")).setText(home.toString());
        ((JCheckBox)FeatureTest.field(app,"toolsBox")).setSelected(true);
        ((JComboBox<?>)FeatureTest.field(app,"permissionBox")).setSelectedIndex(2);
        ((JCheckBox)FeatureTest.field(app,"streamBox")).setSelected(false);return app;
    }
    static Conversations.State state(LocalChat app)throws Exception{return FeatureTest.field(app,"sessionState");}
    static JTextArea input(LocalChat app)throws Exception{return FeatureTest.field(app,"input");}
    static void add(LocalChat app,Path...paths)throws Exception{FeatureTest.call(app,"addAttachments",new Class<?>[]{List.class},List.of(paths));}
    static Transferable files(Path...paths){return new Transferable(){
        public DataFlavor[] getTransferDataFlavors(){return new DataFlavor[]{DataFlavor.javaFileListFlavor};}
        public boolean isDataFlavorSupported(DataFlavor f){return DataFlavor.javaFileListFlavor.equals(f);}
        public Object getTransferData(DataFlavor f)throws UnsupportedFlavorException{if(!isDataFlavorSupported(f))throw new UnsupportedFlavorException(f);return Arrays.stream(paths).map(Path::toFile).toList();}
    };}
    static String toolReply(Path path){return Json.write(Json.obj("choices",List.of(Json.obj("message",Json.obj("content","Reading selected file","tool_calls",List.of(Json.obj("id","attached_read","type","function","function",Json.obj("name","read_file","arguments",Json.write(Json.obj("path",path.toString())))))))),"usage",Json.obj("prompt_tokens",30,"completion_tokens",8)));}
    static void permissions(Path home)throws Exception{
        Path work=Files.createDirectories(home.resolve("work")),outside=Files.createDirectories(home.resolve("external")),file=outside.resolve("selected.txt"),neighbor=outside.resolve("neighbor.txt"),folder=Files.createDirectories(outside.resolve("selected-folder")),child=folder.resolve("child.txt"),secret=home.resolve("setari.properties");
        Files.writeString(file,"SELECTED-CONTENT");Files.writeString(neighbor,"NEIGHBOR-CONTENT");Files.writeString(child,"CHILD-CONTENT");Files.writeString(secret,"fixture-private-settings");
        AtomicInteger asks=new AtomicInteger();Tools tools=new Tools(new Tools.Context(work,home.resolve("skills"),home.resolve("rules.md"),secret,Map.of(),null,new Mcp(),new PathMap(Map.of(),work.toString(),work.toString()),false,EnumSet.noneOf(Tools.Access.class),(a,b)->{asks.incrementAndGet();return Tools.Decision.NO;}),List.of()).readOnly(true).attachments(List.of(home.resolve("missing.txt").toString(),file.toString(),folder.toString(),home.toString()));
        check(!tools.execute(new ChatClient.ToolCall("r","read_file",Json.obj("path",file.toString()))).isError,"explicit selected file readable outside workspace");
        check(!tools.execute(new ChatClient.ToolCall("r","read_file",Json.obj("path",child.toString()))).isError,"explicit selected folder grants reading descendants");
        check(asks.get()==0,"attached reads need no redundant read approval");
        check(tools.execute(new ChatClient.ToolCall("r","read_file",Json.obj("path",secret.toString()))).isError,"settings remain protected even inside selected folder");
        check(!tools.execute(new ChatClient.ToolCall("r","search_text",Json.obj("path",home.toString(),"query","fixture-private-settings"))).text.contains("fixture-private-settings"),"recursive search cannot reveal protected settings from attached folder");
        check(tools.execute(new ChatClient.ToolCall("w","write_file",Json.obj("path",file.toString(),"content","overwritten"))).isError && Files.readString(file).equals("SELECTED-CONTENT"),"attachment grants cannot authorize writes");
        tools.attachments(List.of(file.toString(),folder.toString()));check(tools.execute(new ChatClient.ToolCall("r","read_file",Json.obj("path",neighbor.toString()))).isError&&asks.get()==1,"file attachment does not grant reading sibling files");
        check(!AttachmentSupport.grantsRead(outside,List.of(folder.toString())),"folder attachment does not grant parent access");
        try{AttachmentSupport.validate(secret,secret);throw new AssertionError("settings accepted");}catch(java.io.IOException expected){checks++;}
    }
    static void selection(Path home)throws Exception{
        Path local=home.resolve("selection"),file=home.resolve("drawing file ü.txt"),folder=Files.createDirectories(home.resolve("drawings"));Files.writeString(file,"NOT-UPLOADED-ON-SELECTION");
        try(FeatureTest.Server server=new FeatureTest.Server()){
            LocalChat[] a={null};Path[] alpha={null};
            FeatureTest.edt(()->{a[0]=window(local,server.url());JTextArea text=input(a[0]);
                check(text.getTransferHandler().importData(text,files(file,folder,file)),"native Explorer file-list accepted by composer");
                check(state(a[0]).draftAttachments.size()==2,"duplicate drops deduplicated");
                check(!state(a[0]).title.isBlank(),"attachment-only draft has a visible sidebar title");
                check(((JTextPane)FeatureTest.field(a[0],"transcript")).getTransferHandler().canImport(new TransferHandler.TransferSupport(text,files(file))),"transcript accepts file drops");
                check(((JScrollPane)FeatureTest.field(a[0],"attachmentBar")).isVisible(),"attachment chips visible before send");
                check(server.requests.isEmpty(),"selecting and dropping files makes no API request");
                Path saved=FeatureTest.field(a[0],"conversationFile");alpha[0]=saved;
                check(Conversations.load(saved).state().draftAttachments.size()==2,"unsent selection saved to chat");
                String json=Files.readString(saved);check(!json.contains("NOT-UPLOADED-ON-SELECTION"),"attachment contents not embedded in saved draft");
                check(text.getTransferHandler().importData(text,new StringSelection("normal paste"))&&text.getText().equals("normal paste"),"native text paste preserved");
                Clipboard clipboard=new Clipboard("test");text.selectAll();text.getTransferHandler().exportToClipboard(text,clipboard,TransferHandler.COPY);check(clipboard.getData(DataFlavor.stringFlavor).equals("normal paste"),"native copy preserved");
                FeatureTest.call(a[0],"setBusy",new Class<?>[]{boolean.class},true);check(text.getTransferHandler().importData(text,files(file)),"file drop accepted into next draft during active request");FeatureTest.call(a[0],"setBusy",new Class<?>[]{boolean.class},false);
                FeatureTest.command(a[0],"/new Beta");check(state(a[0]).draftAttachments.isEmpty(),"new chat does not inherit attachments");add(a[0],folder);
                FeatureTest.command(a[0],"/switch "+Conversations.shortId(alpha[0]));check(state(a[0]).draftAttachments.size()==2&&input(a[0]).getText().equals("normal paste"),"resuming restores selection and draft without inference");
                FeatureTest.call(a[0],"removeAttachment",new Class<?>[]{String.class},file.toRealPath().toString());check(state(a[0]).draftAttachments.size()==1&&Files.exists(file),"remove chip does not delete source file");
                Path secret=local.resolve("setari.properties");add(a[0],secret,home.resolve("does-not-exist.pdf"));check(state(a[0]).draftAttachments.size()==1,"protected and missing files rejected");
                ((JCheckBox)FeatureTest.field(a[0],"toolsBox")).setSelected(false);FeatureTest.call(a[0],"send",new Class<?>[]{});check(server.requests.isEmpty()&&!state(a[0]).draftAttachments.isEmpty(),"plain chat explains tools requirement and keeps draft");
                for(int i=0;i<25;i++){Path extra=home.resolve("file"+i+".txt");Files.writeString(extra,"fixture");add(a[0],extra);}check(state(a[0]).draftAttachments.size()==20,"selection bounded to twenty references");
                for(int lang=0;lang<6;lang++)for(int theme=0;theme<2;theme++){
                    ((JComboBox<?>)FeatureTest.field(a[0],"languageBox")).setSelectedIndex(lang);((JComboBox<?>)FeatureTest.field(a[0],"themeBox")).setSelectedIndex(theme);
                    JButton attach=FeatureTest.field(a[0],"attachButton");check(attach.getIcon()!=null&&attach.getToolTipText().equals(I18n.text("Adaugă fișiere sau foldere")),"translated attachment button in "+I18n.language());
                    a[0].addNotify();a[0].validate();java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(a[0].getWidth(),a[0].getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();a[0].getRootPane().printAll(g);g.dispose();Path screenshots=Files.createDirectories(local.resolve("screenshots"));javax.imageio.ImageIO.write(image,"png",screenshots.resolve(I18n.language()+"-"+theme+".png").toFile());
                }
                check(server.requests.isEmpty(),"appearance and session changes never upload or read attachments");a[0].dispose();
                a[0]=window(local,server.url());FeatureTest.command(a[0],"/switch "+Conversations.shortId(alpha[0]));check(state(a[0]).draftAttachments.size()==20,"attachments survive application restart");a[0].dispose();
            });
        }
    }
    static void sending(Path home,boolean failure)throws Exception{
        Path local=home.resolve(failure?"retry":"send"),file=home.resolve("outside-selected.txt");Files.writeString(file,"ATTACHMENT-READ-BY-REAL-TOOL");
        FeatureTest.Response first=failure?new FeatureTest.Response(500,"application/json","{\"error\":\"fixture failure\"}"):FeatureTest.json(toolReply(file));
        try(FeatureTest.Server server=new FeatureTest.Server(first,FeatureTest.json(FeatureTest.answer("Verified attachment",40,10)),FeatureTest.json(toolReply(file)),FeatureTest.json(FeatureTest.answer("Continued",50,12)))){
            LocalChat[] a={null};FeatureTest.edt(()->{a[0]=window(local,server.url());add(a[0],file);input(a[0]).setText("Read the selected file");FeatureTest.call(a[0],"send",new Class<?>[]{});});FeatureTest.idle(a[0]);
            FeatureTest.edt(()->{
                if(failure){check(input(a[0]).getText().equals("Read the selected file")&&state(a[0]).draftAttachments.size()==1,"HTTP failure restores original draft and attachments for retry");}
                else{
                    check(state(a[0]).draftAttachments.isEmpty()&&state(a[0]).attachedPaths.contains(file.toRealPath().toString()),"sent attachment reference retained for follow-up in same chat");
                    check(server.requests.size()==2,"model reads selected file through normal tool loop");String second=Json.write(server.requests.get(1));check(second.contains("ATTACHMENT-READ-BY-REAL-TOOL"),"real tool result reaches API after explicit send");
                    check(!Json.write(server.requests.get(0)).contains("ATTACHMENT-READ-BY-REAL-TOOL"),"file bytes not inserted into initial reference message");
                    check(state(a[0]).usage.requests==2,"attachment selection creates no hidden inference usage");
                    input(a[0]).setText("Continue reading the same file");FeatureTest.call(a[0],"send",new Class<?>[]{});
                }
            });
            if(!failure){FeatureTest.idle(a[0]);FeatureTest.edt(()->{check(server.requests.size()==4&&Json.write(server.requests.get(3)).contains("ATTACHMENT-READ-BY-REAL-TOOL"),"follow-up retains narrow read access");});}
            FeatureTest.edt(()->a[0].dispose());
        }
    }
    static void clipboard(Path home)throws Exception{
        Path local=home.resolve("clipboard");
        try(FeatureTest.Server server=new FeatureTest.Server()){
            FeatureTest.edt(()->{
                LocalChat app=window(local,server.url());JTextArea text=input(app);Clipboard clip=new Clipboard("isolated-composer-test");AtomicInteger failures=new AtomicInteger();ComposerClipboard.install(text,()->clip,error->failures.incrementAndGet());
                for(int lang=0;lang<6;lang++)for(int theme=0;theme<2;theme++){
                    ((JComboBox<?>)FeatureTest.field(app,"languageBox")).setSelectedIndex(lang);((JComboBox<?>)FeatureTest.field(app,"themeBox")).setSelectedIndex(theme);
                    text.setText("before replace after");text.select(7,14);clip.setContents(new StringSelection("linie 1\nRomână 日本語"),null);
                    Object key=text.getInputMap().get(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_V,java.awt.event.InputEvent.CTRL_DOWN_MASK));text.getActionMap().get(key).actionPerformed(new java.awt.event.ActionEvent(text,0,"paste"));
                    check(text.getText().equals("before linie 1\nRomână 日本語 after"),"Ctrl+V replaces selection and preserves multiline Unicode after theme change");
                    check(text.getInputMap().get(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_INSERT,java.awt.event.InputEvent.SHIFT_DOWN_MASK)).equals(javax.swing.text.DefaultEditorKit.pasteAction),"Shift+Insert uses the same paste action");
                    JPopupMenu popup=text.getComponentPopupMenu();for(var listener:popup.getPopupMenuListeners())listener.popupMenuWillBecomeVisible(new javax.swing.event.PopupMenuEvent(popup));
                    check(((JMenuItem)popup.getComponent(2)).getText().equals(I18n.text("Lipeste")),"right-click paste is translated in both themes");
                    text.selectAll();clip.setContents(new StringSelection("context menu paste"),null);((JMenuItem)popup.getComponent(2)).doClick();check(text.getText().equals("context menu paste"),"right-click paste inserts clipboard text");
                    text.selectAll();text.getActionMap().get(javax.swing.text.DefaultEditorKit.copyAction).actionPerformed(new java.awt.event.ActionEvent(text,0,"copy"));check(clip.getData(DataFlavor.stringFlavor).equals("context menu paste"),"copy remains functional");
                    text.getActionMap().get(javax.swing.text.DefaultEditorKit.cutAction).actionPerformed(new java.awt.event.ActionEvent(text,0,"cut"));check(text.getText().isEmpty()&&clip.getData(DataFlavor.stringFlavor).equals("context menu paste"),"cut preserves clipboard text");
                }
                java.awt.image.BufferedImage original=new java.awt.image.BufferedImage(12,8,java.awt.image.BufferedImage.TYPE_INT_ARGB);original.setRGB(1,2,0xFF123456);
                Transferable image=new Transferable(){public DataFlavor[] getTransferDataFlavors(){return new DataFlavor[]{DataFlavor.imageFlavor};}public boolean isDataFlavorSupported(DataFlavor f){return f.equals(DataFlavor.imageFlavor);}public Object getTransferData(DataFlavor f)throws UnsupportedFlavorException{if(!isDataFlavorSupported(f))throw new UnsupportedFlavorException(f);return original;}};
                clip.setContents(image,null);text.getActionMap().get(javax.swing.text.DefaultEditorKit.pasteAction).actionPerformed(new java.awt.event.ActionEvent(text,0,"paste"));
                check(state(app).draftAttachments.size()==1,"clipboard screenshot is attached as a local PNG");Path png=Path.of(state(app).draftAttachments.get(0));check(png.startsWith(local.resolve("atasamente_clipboard"))&&javax.imageio.ImageIO.read(png.toFile()).getRGB(1,2)==original.getRGB(1,2),"saved screenshot preserves pixels in private application storage");
                Path saved=FeatureTest.field(app,"conversationFile");check(Conversations.load(saved).state().draftAttachments.contains(png.toString()),"screenshot reference is persisted with the draft");
                FeatureTest.call(app,"setBusy",new Class<?>[]{boolean.class},true);check(text.getTransferHandler().importData(text,image),"image paste accepted into next draft while generating");clip.setContents(new StringSelection("next question"),null);text.getActionMap().get(javax.swing.text.DefaultEditorKit.pasteAction).actionPerformed(new java.awt.event.ActionEvent(text,0,"paste"));check(text.getText().equals("next question"),"text draft can be pasted while generating");FeatureTest.call(app,"setBusy",new Class<?>[]{boolean.class},false);
                check(failures.get()==0&&server.requests.isEmpty(),"clipboard actions create no API requests or hidden failures");
                ComposerClipboard.install(text,()->{throw new IllegalStateException("fixture clipboard locked");},error->failures.incrementAndGet());text.getActionMap().get(javax.swing.text.DefaultEditorKit.pasteAction).actionPerformed(new java.awt.event.ActionEvent(text,0,"paste"));check(failures.get()==1&&text.getText().equals("next question"),"locked clipboard reports error and preserves draft");
                AtomicInteger transientCalls=new AtomicInteger();Clipboard transientClip=new Clipboard("transient lock"){public synchronized Transferable getContents(Object requestor){if(transientCalls.incrementAndGet()<3)throw new IllegalStateException("fixture lock");return new StringSelection("recovered");}};
                check(ComposerClipboard.contents(transientClip).getTransferData(DataFlavor.stringFlavor).equals("recovered")&&transientCalls.get()==3,"temporary Windows clipboard lock is retried without duplicating input");
                AtomicInteger permanentCalls=new AtomicInteger();try{ComposerClipboard.contents(new Clipboard("locked"){public synchronized Transferable getContents(Object requestor){permanentCalls.incrementAndGet();throw new IllegalStateException("fixture lock");}});throw new AssertionError("permanent lock accepted");}catch(IllegalStateException expected){check(permanentCalls.get()==5,"clipboard lock retries are bounded");}
                try{ComposerClipboard.saveImage(null,local.resolve("invalid-images"));throw new AssertionError("null image accepted");}catch(java.io.IOException expected){check(!Files.exists(local.resolve("invalid-images")),"invalid image creates no attachment file");}
                java.awt.Image giant=new java.awt.Image(){public int getWidth(java.awt.image.ImageObserver observer){return 5000;}public int getHeight(java.awt.image.ImageObserver observer){return 5000;}public java.awt.image.ImageProducer getSource(){return null;}public java.awt.Graphics getGraphics(){return null;}public Object getProperty(String name,java.awt.image.ImageObserver observer){return null;}};
                try{ComposerClipboard.saveImage(giant,local.resolve("invalid-images"));throw new AssertionError("oversize accepted");}catch(java.io.IOException expected){check(!Files.exists(local.resolve("invalid-images")),"oversize image rejected before allocation or file creation");}
                app.dispose();
            });
        }
    }
    public static void main(String[] args)throws Exception{
        Path home=Path.of(System.getProperty("localchat.home"));Files.createDirectories(home);
        try{check(!AttachmentSupport.message("",List.of("fixture.pdf")).isBlank(),"attachments-only send gets a useful request");permissions(home);selection(home);clipboard(home);sending(home,false);sending(home,true);check(Conversations.State.fromJson(Json.obj(),0).draftAttachments.isEmpty(),"old conversation format remains compatible");System.out.println("PASS: "+checks+" attachment checks; six languages x two themes; real file tools; synthetic HTTP only");}
        finally{FeatureTest.edt(()->{for(var w:java.awt.Window.getWindows())w.dispose();});}System.exit(0);
    }
}
