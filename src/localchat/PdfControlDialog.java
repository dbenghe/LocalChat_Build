package localchat;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Direct engine operations: the model is never involved in selecting or executing these calls. */
final class PdfControlDialog extends JDialog {
    static final String[] OPERATIONS={"pdfcontrol_start_check","pdfcontrol_start_extract","pdfcontrol_start_pairs","pdfcontrol_start_compare","pdfcontrol_start_workflow","pdfcontrol_regenerate_report"};
    static final String[] LABELS={"Verifica desenele","Extrage planse si perechi","Completeaza perechi V/W","Compara OUD/NIEUW","Flux complet PDFCONTROL","Regenereaza raportul PDF"};
    final JComboBox<String> operation=new JComboBox<>();
    final JTextField project=new JTextField(8);
    final JButton start=new JButton(),cancel=new JButton(),open=new JButton();
    final JProgressBar progress=new JProgressBar();
    final JLabel status=new JLabel(" ");
    final DefaultListModel<Path> artifacts=new DefaultListModel<>();
    final JList<Path> files=new JList<>(artifacts);
    final AtomicBoolean cancelled=new AtomicBoolean();
    final McpServer server;
    private final Consumer<String> started;
    private final Consumer<Object> complete;
    private final Consumer<Path> selectedFolder;
    private final Runnable finished;
    private Path root;
    boolean running;
    String jobId;

    PdfControlDialog(JFrame owner,McpServer server,Path workDir,Consumer<String> started,Consumer<Object> complete,Consumer<Path> selectedFolder,Runnable finished) {
        super(owner,"PDFCONTROL",true);this.server=server;this.started=started;this.complete=complete;this.selectedFolder=selectedFolder;this.finished=finished;
        for(String label:LABELS)operation.addItem(I18n.text(label));
        if(workDir!=null)for(Path segment:workDir)if(segment.toString().matches("[0-9]{6}"))project.setText(segment.toString());
        JPanel form=new JPanel(new FlowLayout(FlowLayout.LEFT,6,6));form.add(new JLabel(I18n.text("Proiect")));form.add(project);form.add(operation);
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT));start.setText(I18n.text("Alege PDF-urile si porneste"));cancel.setText(I18n.text("Anuleaza lucrarea"));open.setText(I18n.text("Deschide raportul"));cancel.setEnabled(false);open.setEnabled(false);actions.add(open);actions.add(cancel);actions.add(start);
        JPanel north=new JPanel(new BorderLayout());north.add(form);north.add(new JLabel(I18n.text("Motorul original PDFCONTROL. Sursele sunt pastrate; rezultatele se salveaza in Controle.")),BorderLayout.SOUTH);
        JPanel south=new JPanel(new BorderLayout(4,4));south.add(status,BorderLayout.NORTH);south.add(progress);south.add(actions,BorderLayout.SOUTH);
        JPanel panel=new JPanel(new BorderLayout(8,8));panel.setBorder(BorderFactory.createEmptyBorder(10,10,10,10));panel.add(north,BorderLayout.NORTH);panel.add(new JScrollPane(files));panel.add(south,BorderLayout.SOUTH);setContentPane(panel);
        files.setCellRenderer(new DefaultListCellRenderer(){public Component getListCellRendererComponent(JList<?> l,Object value,int i,boolean selected,boolean focus){return super.getListCellRendererComponent(l,value instanceof Path?((Path)value).getFileName():value,i,selected,focus);}});
        files.addListSelectionListener(e->{Path p=files.getSelectedValue();files.setToolTipText(p==null?null:p.toString());open.setEnabled(p!=null);});
        start.addActionListener(e->prepare());cancel.addActionListener(e->{cancelled.set(true);cancel.setEnabled(false);status.setText(I18n.text("Anulare solicitata…"));});
        open.addActionListener(e->{Path p=files.getSelectedValue();if(p!=null)try{Desktop.getDesktop().open(p.toFile());}catch(Exception error){status.setText(error.getMessage());}});
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);addWindowListener(new WindowAdapter(){public void windowClosing(WindowEvent e){if(running){cancelled.set(true);cancel.setEnabled(false);status.setText(I18n.text("Anulare solicitata…"));}else dispose();}});
        setSize(900,510);setMinimumSize(new Dimension(760,420));setLocationRelativeTo(owner);
        // Initialize metadata in the background; it never makes an inference request.
        start.setEnabled(false);
        new SwingWorker<Path,Void>(){
            protected Path doInBackground()throws Exception{Object result=invoke(server,"pdfcontrol_status",Json.obj());return Path.of(Json.str(result,"projects_root")).toRealPath();}
            protected void done(){try{root=get();start.setEnabled(true);status.setText(root.toString());}catch(Exception error){status.setText(I18n.text("PDFCONTROL indisponibil: {0}",error.getMessage()));}}
        }.execute();
    }
    static Object invoke(McpServer server,String tool,Map<String,Object> args)throws Exception {
        Tools.Result result=server.call(tool,args,60_000);if(result.isError)throw new java.io.IOException(result.text);return Json.parse(result.text);
    }
    static List<String> relativeSelection(Path project,Path[] selected)throws java.io.IOException {
        Path actual=project.toRealPath();List<String> result=new ArrayList<>();
        for(Path file:selected){Path path=file.toRealPath();if(!path.startsWith(actual)||!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))throw new java.io.IOException(I18n.text("Alege PDF-uri din proiectul selectat."));
            result.add(actual.relativize(path).toString().replace('\\','/'));}
        return result;
    }
    private List<String> choose(Path project,String title)throws Exception {
        JFileChooser chooser=new JFileChooser(project.toFile());chooser.setDialogTitle(title);chooser.setMultiSelectionEnabled(true);chooser.setFileFilter(new FileNameExtensionFilter("PDF","pdf"));
        if(chooser.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return null;
        return relativeSelection(project,Arrays.stream(chooser.getSelectedFiles()).map(java.io.File::toPath).toArray(Path[]::new));
    }
    private void prepare() {
        if(running || root==null)return;
        try {
            String number=project.getText().strip();if(!number.matches("[0-9]{6}"))throw new java.io.IOException(I18n.text("Proiectul trebuie sa aiba sase cifre."));
            Path folder=root.resolve(number).toRealPath();if(!folder.getParent().equals(root))throw new java.io.IOException(I18n.text("Alege PDF-uri din proiectul selectat."));
            int selected=operation.getSelectedIndex();Map<String,Object> args=Json.obj("project",number);
            if(selected==5){String id=JOptionPane.showInputDialog(this,I18n.text("Identificatorul lucrarii de control"));if(id==null)return;args.put("job_id",id.strip());}
            else if(selected==3){var oldFiles=choose(folder,I18n.text("Alege versiunea OUD (veche)"));if(oldFiles==null)return;var newFiles=choose(folder,I18n.text("Alege versiunea NIEUW (noua)"));if(newFiles==null)return;args.put("old_files",oldFiles);args.put("new_files",newFiles);}
            else{var selectedFiles=choose(folder,I18n.text(LABELS[selected]));if(selectedFiles==null || selectedFiles.isEmpty())return;args.put("files",selectedFiles);}
            if(JOptionPane.showConfirmDialog(this,I18n.text("Pornesti {0} pentru proiectul {1}? Se creeaza copii si PDF-uri in Controle.",I18n.text(LABELS[selected]),number),"PDFCONTROL",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;
            selectedFolder.accept(folder);started.accept(I18n.text(LABELS[selected]));execute(OPERATIONS[selected],args);
        }catch(Exception error){status.setText(error.getMessage());}
    }
    void execute(String tool,Map<String,Object> args) {
        running=true;jobId=null;cancelled.set(false);artifacts.clear();start.setEnabled(false);operation.setEnabled(false);project.setEnabled(false);cancel.setEnabled(true);progress.setIndeterminate(true);
        new SwingWorker<Object,Object>(){
            protected Object doInBackground()throws Exception {
                Object record=invoke(server,tool,args);jobId=Json.str(record,"job_id");boolean requested=false;
                while(Set.of("pending","running").contains(Objects.toString(Json.str(record,"status"),""))) {
                    if(cancelled.get() && !requested){invoke(server,"pdfcontrol_cancel_job",Json.obj("project",args.get("project"),"job_id",jobId));requested=true;}
                    record=invoke(server,"pdfcontrol_job_status",Json.obj("project",args.get("project"),"job_id",jobId,"wait_seconds",3));publish(record);
                }
                // Fetch paged artifacts without repeating execution or asking the model.
                List<Object> all=new ArrayList<>();Object page=record;
                while(true){Object rows=Json.get(page,"artifacts");if(rows instanceof List)all.addAll((List<?>)rows);Object next=Json.get(page,"next_artifact_offset");if(!(next instanceof Number))break;
                    page=invoke(server,"pdfcontrol_job_status",Json.obj("project",args.get("project"),"job_id",jobId,"wait_seconds",0,"artifact_offset",next));}
                if(record instanceof Map)((Map<String,Object>)record).put("artifacts",all);return record;
            }
            protected void process(List<Object> chunks){Object last=chunks.get(chunks.size()-1);status.setText(I18n.text("Lucrare {0}: {1}",jobId,localizedStatus(Json.str(last,"status")))+" · "+Objects.toString(Json.get(last,"progress","item"),""));}
            protected void done(){try{
                Object record=get();String state=Json.str(record,"status");status.setText(I18n.text("Lucrare {0}: {1}",jobId,localizedStatus(state)));
                Object paths=Json.get(record,"artifacts");if(paths instanceof List)for(Object row:(List<?>)paths){String value=Json.str(row,"path");if(value!=null){Path path=Path.of(value);if(Files.isRegularFile(path)&&path.toAbsolutePath().normalize().startsWith(root))artifacts.addElement(path);}}
                if(!artifacts.isEmpty())files.setSelectedIndex(0);complete.accept(record);
                if(Json.str(record,"error")!=null)status.setText(status.getText()+" · "+Json.str(record,"error"));
            }catch(Exception error){status.setText(I18n.text("PDFCONTROL indisponibil: {0}",error.getMessage()));complete.accept(Json.obj("status","failed","error",error.getMessage()));}
            finally{running=false;progress.setIndeterminate(false);cancel.setEnabled(false);start.setEnabled(true);operation.setEnabled(true);project.setEnabled(true);finished.run();}}
        }.execute();
    }
    static String localizedStatus(String status){return I18n.text(switch(Objects.toString(status,"")){case "done"->"Finalizat";case "partial"->"Partial: verificare manuala necesara";case "running"->"In lucru";case "pending"->"In asteptare";case "cancelled"->"Anulat";default->"Eroare";});}
}
