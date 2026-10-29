package localchat;
import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Java UI -> stdio MCP -> original Python engine. No model/provider is instantiated. */
public final class PdfControlTest {
    static int checks;
    static void check(boolean ok,String text){checks++;if(!ok)throw new AssertionError(text);}
    interface Work{void run()throws Exception;}
    static void edt(Work w)throws Exception{Throwable[] failure={null};SwingUtilities.invokeAndWait(()->{try{w.run();}catch(Throwable e){failure[0]=e;}});if(failure[0]!=null)throw new Exception(failure[0]);}
    static final java.util.concurrent.atomic.AtomicInteger finishedRuns=new java.util.concurrent.atomic.AtomicInteger();
    /** Stand-in for the modal windows so tests never block. */
    static final class StubUi implements PdfControlDialog.Ui {
        final List<String> errors=new java.util.concurrent.CopyOnWriteArrayList<>(),confirms=new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile boolean answer=true;volatile List<String> picks=null;volatile String server=null;volatile String job=null;
        public boolean confirm(String text){confirms.add(text);return answer;}
        public void error(String text){errors.add(text);}
        public String serverFolder(String current){return server;}
        public String jobId(List<String> ids){return job!=null?job:ids.get(0);}
        public List<String> pick(Path project,String title){return picks;}
    }
    /** job=false: the engine answered about the projects root; job=true: one run finished. */
    static void ready(PdfControlDialog dialog,boolean job)throws Exception {
        long deadline=System.nanoTime()+120_000_000_000L;
        while(System.nanoTime()<deadline){boolean[] done={false};edt(()->done[0]=job?finishedRuns.get()>0&&!dialog.running:dialog.root()!=null);if(done[0]){if(job)finishedRuns.set(0);return;}Thread.sleep(20);}throw new AssertionError("PDFCONTROL dialog timeout");
    }
    public static void main(String[] args)throws Exception {
        Path pack=Path.of(System.getProperty("pdfcontrol.package")),projects=Path.of(System.getProperty("pdfcontrol.projects")),out=projects.getParent().resolve("java-ui");Files.createDirectories(out);
        McpServer server=new McpServer("pdfcontrol",List.of(pack.resolve("runtime/python/python.exe").toString(),"-I",pack.resolve("octop_mcp.py").toString()),Map.of("PDFCONTROL_PACKAGE",pack.toString(),"PDFCONTROL_PROJECTS_ROOT",projects.toString()),pack.toFile());
        List<PdfControlDialog> dialogs=new ArrayList<>();
        try {
            server.start(60000);check(server.tools().stream().anyMatch(t->t.name.equals("pdfcontrol_start_workflow")),"full workflow discovered by Java MCP");
            for(String language:I18n.CODES){I18n.setLanguage(language);PdfControlDialog[] ref={null};
                edt(()->{ref[0]=new PdfControlDialog(null,server,projects.resolve("123456"),List.of(),out,f->null,()->false,s->{},r->{},p->{},finishedRuns::incrementAndGet);dialogs.add(ref[0]);});ready(ref[0],false);
                edt(()->{
                    PdfControlDialog dialog=ref[0];check("123456".equals(dialog.project.getSelectedItem()),"project inferred from selected working folder and listed from the projects root");
                    check(dialog.operation.getItemCount()==7,"seven direct operations available");check(dialog.factory.getItemCount()==5,"factory is explicit with unconfirmed default");check(PdfControlDialog.limitations(Json.obj("limitation_codes",List.of("factory_catalogue_unavailable"))).equals(I18n.text("Catalogul fabricii nu este confirmat sau disponibil; verificarea este partiala.")),"factory limitation translated "+language);
                    for(int i=0;i<7;i++)check(dialog.operation.getItemAt(i).equals(I18n.text(PdfControlDialog.LABELS[i])),"localized operation "+language);
                    check(dialog.start.getText().equals(I18n.text("Porneste")),"localized start action");
                    for(boolean dark:new boolean[]{false,true}){
                        if(dark)com.formdev.flatlaf.FlatDarkLaf.setup();else com.formdev.flatlaf.FlatLightLaf.setup();SwingUtilities.updateComponentTreeUI(dialog);
                        dialog.addNotify();dialog.validate();check(dialog.start.getWidth()>=dialog.start.getPreferredSize().width,"start button label fits");
                        var picture=new java.awt.image.BufferedImage(dialog.getWidth(),dialog.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);var g=picture.createGraphics();dialog.getRootPane().printAll(g);g.dispose();javax.imageio.ImageIO.write(picture,"png",out.resolve(language+(dark?"-dark":"-light")+".png").toFile());
                    }dialog.dispose();
                });
            }
            var selection=PdfControlDialog.relativeSelection(projects.resolve("123456"),new Path[]{projects.resolve("123456/loose/123456_WA224_V.pdf")});check(selection.equals(List.of("loose/123456_WA224_V.pdf")),"selected PDFs become project-relative arguments");
            check(PdfControlDialog.relativeSelection(projects.resolve("123456"),new Path[]{projects.resolve("123456/Controle/Atasamente_nou/123456_TRK300_V.pdf")}).equals(List.of("Controle/Atasamente_nou/123456_TRK300_V.pdf")),"UI preserves valid sources under Controle");
            try{PdfControlDialog.relativeSelection(projects.resolve("123456/loose"),new Path[]{projects.resolve("123456/bundle.pdf")});throw new AssertionError("Outside selection accepted");}catch(java.io.IOException expected){checks++;}
            AtomicReference<Object> result=new AtomicReference<>();PdfControlDialog[] run={null};
            edt(()->{run[0]=new PdfControlDialog(null,server,projects.resolve("123456"),List.of(),out,f->null,()->false,s->{},result::set,p->{},finishedRuns::incrementAndGet);dialogs.add(run[0]);});ready(run[0],false);
            StubUi stub=new StubUi();stub.picks=List.of("loose/123456_WA224_V.pdf","loose/123456_WA133_W.pdf");
            edt(()->{run[0].ui=stub;run[0].operation.setSelectedIndex(PdfControlDialog.CHECK);run[0].choose.doClick();run[0].start.doClick();});ready(run[0],true);
            check(stub.errors.isEmpty(),"no error popup on a normal run "+stub.errors);check(stub.confirms.size()==1&&stub.confirms.get(0).contains("123456")&&stub.confirms.get(0).contains("2"),"confirmation names the project and file count");
            edt(()->{check(List.of("done","partial").contains(Json.str(result.get(),"status")),"direct UI executed original engine to completion: "+result.get()+" errors="+stub.errors+" details="+run[0].details.getText()+" status="+run[0].status.getText());check(run[0].artifacts.size()>=2,"real PDF artifacts shown in direct UI");check(run[0].open.isEnabled(),"open PDF action available");check(!run[0].progress.isIndeterminate(),"progress stops at completion");run[0].dispose();});
            result.set(null);edt(()->{run[0]=new PdfControlDialog(null,server,projects.resolve("123456"),List.of(),out,f->null,()->false,s->{},result::set,p->{},finishedRuns::incrementAndGet);dialogs.add(run[0]);});ready(run[0],false);
            StubUi stub2=new StubUi();stub2.picks=List.of("bundle.pdf");
            edt(()->{run[0].ui=stub2;run[0].operation.setSelectedIndex(PdfControlDialog.WORKFLOW);run[0].choose.doClick();run[0].start.doClick();});
            for(long until=System.nanoTime()+60_000_000_000L;System.nanoTime()<until;){boolean[] go={false};edt(()->go[0]=run[0].jobId!=null&&run[0].cancel.isEnabled());if(go[0])break;Thread.sleep(20);}
            edt(()->run[0].cancel.doClick());ready(run[0],true);
            edt(()->{check("cancelled".equals(Json.str(result.get(),"status")),"direct UI cancellation reaches original engine worker");run[0].dispose();});
            System.out.println("PASS: "+checks+" direct PDFCONTROL UI/MCP checks; 6 languages x 2 themes; zero model requests");
        }finally{edt(()->{for(var d:dialogs)d.dispose();});server.close();}
        System.exit(0);
    }
}
