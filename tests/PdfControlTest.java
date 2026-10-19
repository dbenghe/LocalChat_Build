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
    static void ready(PdfControlDialog dialog,boolean job)throws Exception {
        long deadline=System.nanoTime()+60_000_000_000L;
        while(System.nanoTime()<deadline){boolean[] done={false};edt(()->done[0]=job?!dialog.running:dialog.start.isEnabled());if(done[0])return;Thread.sleep(20);}throw new AssertionError("PDFCONTROL dialog timeout");
    }
    public static void main(String[] args)throws Exception {
        Path pack=Path.of(System.getProperty("pdfcontrol.package")),projects=Path.of(System.getProperty("pdfcontrol.projects")),out=projects.getParent().resolve("java-ui");Files.createDirectories(out);
        McpServer server=new McpServer("pdfcontrol",List.of(pack.resolve("runtime/python/python.exe").toString(),"-I",pack.resolve("octop_mcp.py").toString()),Map.of("PDFCONTROL_PACKAGE",pack.toString(),"PDFCONTROL_PROJECTS_ROOT",projects.toString()),pack.toFile());
        List<PdfControlDialog> dialogs=new ArrayList<>();
        try {
            server.start(60000);check(server.tools().stream().anyMatch(t->t.name.equals("pdfcontrol_start_workflow")),"full workflow discovered by Java MCP");
            for(String language:I18n.CODES){I18n.setLanguage(language);PdfControlDialog[] ref={null};
                edt(()->{ref[0]=new PdfControlDialog(null,server,projects.resolve("123456"),s->{},r->{},p->{},()->{});dialogs.add(ref[0]);});ready(ref[0],false);
                edt(()->{
                    PdfControlDialog dialog=ref[0];check(dialog.project.getText().equals("123456"),"project inferred from selected working folder");
                    check(dialog.operation.getItemCount()==6,"six direct operations available");
                    for(int i=0;i<6;i++)check(dialog.operation.getItemAt(i).equals(I18n.text(PdfControlDialog.LABELS[i])),"localized operation "+language);
                    check(dialog.start.getText().equals(I18n.text("Alege PDF-urile și pornește")),"localized start action");
                    for(boolean dark:new boolean[]{false,true}){
                        if(dark)com.formdev.flatlaf.FlatDarkLaf.setup();else com.formdev.flatlaf.FlatLightLaf.setup();SwingUtilities.updateComponentTreeUI(dialog);
                        dialog.addNotify();dialog.validate();check(dialog.start.getWidth()>=dialog.start.getPreferredSize().width,"start button label fits");
                        var picture=new java.awt.image.BufferedImage(dialog.getWidth(),dialog.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);var g=picture.createGraphics();dialog.getRootPane().printAll(g);g.dispose();javax.imageio.ImageIO.write(picture,"png",out.resolve(language+(dark?"-dark":"-light")+".png").toFile());
                    }dialog.dispose();
                });
            }
            var selection=PdfControlDialog.relativeSelection(projects.resolve("123456"),new Path[]{projects.resolve("123456/loose/123456_WA224_V.pdf")});check(selection.equals(List.of("loose/123456_WA224_V.pdf")),"selected PDFs become project-relative arguments");
            try{PdfControlDialog.relativeSelection(projects.resolve("123456/loose"),new Path[]{projects.resolve("123456/bundle.pdf")});throw new AssertionError("Outside selection accepted");}catch(java.io.IOException expected){checks++;}
            AtomicReference<Object> result=new AtomicReference<>();PdfControlDialog[] run={null};
            edt(()->{run[0]=new PdfControlDialog(null,server,projects.resolve("123456"),s->{},result::set,p->{},()->{});dialogs.add(run[0]);});ready(run[0],false);
            edt(()->run[0].execute("pdfcontrol_start_pairs",Json.obj("project","123456","files",List.of("loose/123456_WA224_V.pdf","loose/123456_WA133_W.pdf"))));ready(run[0],true);
            edt(()->{check("done".equals(Json.str(result.get(),"status")),"direct UI executed original engine to completion");check(run[0].artifacts.size()>=2,"real PDF artifacts shown in direct UI");check(run[0].open.isEnabled(),"open PDF action available");check(!run[0].progress.isIndeterminate(),"progress stops at completion");run[0].dispose();});
            result.set(null);edt(()->{run[0]=new PdfControlDialog(null,server,projects.resolve("123456"),s->{},result::set,p->{},()->{});dialogs.add(run[0]);});ready(run[0],false);
            edt(()->{run[0].execute("pdfcontrol_start_workflow",Json.obj("project","123456","files",List.of("bundle.pdf")));run[0].cancel.doClick();});ready(run[0],true);
            edt(()->{check("cancelled".equals(Json.str(result.get(),"status")),"direct UI cancellation reaches original engine worker");run[0].dispose();});
            System.out.println("PASS: "+checks+" direct PDFCONTROL UI/MCP checks; 6 languages x 2 themes; zero model requests");
        }finally{edt(()->{for(var d:dialogs)d.dispose();});server.close();}
        System.exit(0);
    }
}
