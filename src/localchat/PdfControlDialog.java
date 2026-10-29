package localchat;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * Direct engine operations (1.28.14: standalone). The dialog needs no conversation: the project is picked from the
 * PRODUCTIE folders (pre-filled from the working folder or the conversation), the PDFs are chosen with {@link PdfAutoFill}
 * unless picked by hand, and every action runs end to end without the model. Failures are shown as messages, never silent.
 */
final class PdfControlDialog extends JDialog {
    static final String[] OPERATIONS={"pdfcontrol_start_check","pdfcontrol_start_extract","pdfcontrol_start_pairs","pdfcontrol_start_compare","pdfcontrol_start_workflow","pdfcontrol_regenerate_report","pdfcontrol_compare_server_revisions"};
    static final String[] LABELS={"Verifica desenele","Extrage planse si perechi","Completeaza perechi V/W","Compara OUD/NIEUW","Flux complet PDFCONTROL","Regenereaza raportul PDF","Compara revizii server cu local"};
    static final int CHECK=0,EXTRACT=1,PAIRS=2,COMPARE=3,WORKFLOW=4,REGENERATE=5,SERVER=6;
    private static final Pattern SIX=Pattern.compile("(?<![0-9])[0-9]{6}(?![0-9])");

    /** Everything the dialog asks the user; replaced in tests so no modal window blocks. */
    interface Ui {
        boolean confirm(String text);
        void error(String text);
        String serverFolder(String current);
        String jobId(List<String> ids);
        List<String> pick(Path project,String title)throws IOException;
    }
    /** One call: an engine job (MCP) or a local tool of this application. */
    record Step(String tool,Map<String,Object> args,boolean local,boolean refresh){}
    record Plan(List<Step> steps,String confirm,int count){}
    private record Outcome(Object record,String details,List<Path> files,boolean failed,String error){}

    final JComboBox<String> operation=new JComboBox<>();
    final JComboBox<String> project=new JComboBox<>();
    final JComboBox<String> factory=new JComboBox<>(new String[]{I18n.text("Fabrica neconfirmata"),"Soest","Alphen","Veenoord","Lelystad"});
    final JTextField serverFolder=new JTextField(28);
    final JButton chooseServer=new JButton("..."),choose=new JButton(),auto=new JButton();
    final JButton start=new JButton(),cancel=new JButton(),open=new JButton();
    final JLabel selection=new JLabel(" "),serverLabel=new JLabel();
    final JProgressBar progress=new JProgressBar();
    final JLabel status=new JLabel(" ");
    final DefaultListModel<Path> artifacts=new DefaultListModel<>();
    final JList<Path> files=new JList<>(artifacts);
    final JTextArea details=new JTextArea(9,70);
    final AtomicBoolean cancelled=new AtomicBoolean();
    final McpServer server;
    private final Path workDir,appDir;
    private final List<String> hints;
    private final Function<Path,Tools> localTools;
    private final BooleanSupplier readOnly;
    private final Consumer<String> started;
    private final Consumer<Object> complete;
    private final Consumer<Path> selectedFolder;
    private final Runnable finished;
    private Path root;
    private String rootError;
    private List<String> manual;
    private List<List<String>> manualCompare;
    private int selectionToken;
    Ui ui=new SwingUi();
    Consumer<Path> opener=path->{try{Desktop.getDesktop().open(path.toFile());}catch(Exception error){ui.error(I18n.text("Nu pot deschide {0}: {1}",path,error.getMessage()));}};
    boolean running;
    String jobId;

    PdfControlDialog(JFrame owner,McpServer server,Path workDir,List<String> hints,Path appDir,Function<Path,Tools> localTools,BooleanSupplier readOnly,
                     Consumer<String> started,Consumer<Object> complete,Consumer<Path> selectedFolder,Runnable finished) {
        super(owner,"PDFCONTROL",true);this.server=server;this.workDir=workDir;this.hints=hints==null?List.of():hints;this.appDir=appDir;this.localTools=localTools;this.readOnly=readOnly==null?()->false:readOnly;
        this.started=started==null?x->{}:started;this.complete=complete==null?x->{}:complete;this.selectedFolder=selectedFolder==null?x->{}:selectedFolder;this.finished=finished==null?()->{}:finished;
        for(String label:LABELS)operation.addItem(I18n.text(label));
        project.setEditable(true);project.setPrototypeDisplayValue("000000000");
        start.setText(I18n.text("Porneste"));cancel.setText(I18n.text("Anuleaza lucrarea"));open.setText(I18n.text("Deschide raportul"));choose.setText(I18n.text("Alege PDF-uri manual..."));auto.setText(I18n.text("Automat"));serverLabel.setText(I18n.text("Folder proiect pe server"));
        cancel.setEnabled(false);open.setEnabled(false);factory.setToolTipText(I18n.text("Alege fabrica confirmata pentru aceste desene."));
        details.setEditable(false);details.setLineWrap(true);details.setWrapStyleWord(true);
        JPanel row1=new JPanel(new FlowLayout(FlowLayout.LEFT,6,3));row1.add(new JLabel(I18n.text("Proiect")));row1.add(project);row1.add(operation);row1.add(factory);
        JPanel row2=new JPanel(new FlowLayout(FlowLayout.LEFT,6,3));row2.add(selection);row2.add(choose);row2.add(auto);
        JPanel row3=new JPanel(new FlowLayout(FlowLayout.LEFT,6,3));row3.add(serverLabel);row3.add(serverFolder);row3.add(chooseServer);
        JPanel north=new JPanel();north.setLayout(new BoxLayout(north,BoxLayout.Y_AXIS));north.add(row1);north.add(row2);north.add(row3);north.add(new JLabel(I18n.text("Motorul original PDFCONTROL. Sursele sunt pastrate; rezultatele se salveaza in Controle.")));
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT));actions.add(open);actions.add(cancel);actions.add(start);
        JPanel south=new JPanel(new BorderLayout(4,4));south.add(status,BorderLayout.NORTH);south.add(progress);south.add(actions,BorderLayout.SOUTH);
        JSplitPane middle=new JSplitPane(JSplitPane.VERTICAL_SPLIT,new JScrollPane(files),new JScrollPane(details));middle.setResizeWeight(0.35);
        JPanel panel=new JPanel(new BorderLayout(8,8));panel.setBorder(BorderFactory.createEmptyBorder(10,10,10,10));panel.add(north,BorderLayout.NORTH);panel.add(middle);panel.add(south,BorderLayout.SOUTH);setContentPane(panel);
        files.setCellRenderer(new DefaultListCellRenderer(){public Component getListCellRendererComponent(JList<?> l,Object value,int i,boolean selected,boolean focus){return super.getListCellRendererComponent(l,value instanceof Path?((Path)value).getFileName():value,i,selected,focus);}});
        files.addListSelectionListener(e->{Path p=files.getSelectedValue();files.setToolTipText(p==null?null:p.toString());});
        operation.addActionListener(e->{manual=null;manualCompare=null;layoutFor();refreshSelection();});
        project.addActionListener(e->{manual=null;manualCompare=null;refreshSelection();});
        choose.addActionListener(e->chooseManually());auto.addActionListener(e->{manual=null;manualCompare=null;refreshSelection();});
        chooseServer.addActionListener(e->{String picked=ui.serverFolder(serverFolder.getText().strip());if(picked!=null)serverFolder.setText(picked);});
        start.addActionListener(e->prepare());cancel.addActionListener(e->{cancelled.set(true);cancel.setEnabled(false);status.setText(I18n.text("Anulare solicitata."));});
        open.addActionListener(e->{Path p=openTarget();if(p==null)ui.error(I18n.text("Nu exista un raport de deschis. Porneste mai intai o operatie."));else opener.accept(p);});
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);addWindowListener(new WindowAdapter(){public void windowClosing(WindowEvent e){if(running){cancelled.set(true);cancel.setEnabled(false);status.setText(I18n.text("Anulare solicitata."));}else dispose();}});
        setSize(940,620);setMinimumSize(new Dimension(780,480));setLocationRelativeTo(owner);
        layoutFor();
        // Initialize metadata in the background; it never makes an inference request.
        new SwingWorker<Path,Void>(){
            protected Path doInBackground()throws Exception{Object result=invoke(server,"pdfcontrol_status",Json.obj());return Path.of(Json.str(result,"projects_root")).toRealPath();}
            protected void done(){
                try{root=get();status.setText(root.toString());List<String> known=projectFolders(root);
                    for(String number:known)project.addItem(number);
                    String guess=guessProject(workDir,PdfControlDialog.this.hints,known);project.setSelectedItem(guess);
                    if(known.isEmpty()){rootError=I18n.text("Nu am gasit proiecte (foldere cu sase cifre) in {0}.",root);status.setText(rootError);details.setText(rootError);}
                    refreshSelection();
                }catch(Exception error){rootError=I18n.text("PDFCONTROL indisponibil: {0}",Objects.toString(error.getCause()==null?error.getMessage():error.getCause().getMessage(),error.toString()));status.setText(rootError);details.setText(rootError);}
            }
        }.execute();
    }

    // ---- project and selection helpers (static: tested directly) ----

    static Object invoke(McpServer server,String tool,Map<String,Object> args)throws Exception {
        Tools.Result result=server.call(tool,args,60_000);if(result.isError)throw new IOException(result.text);return Json.parse(result.text);
    }
    /** Six-digit folders of the projects root, newest first. */
    static List<String> projectFolders(Path root){
        if(root==null||!Files.isDirectory(root))return List.of();
        try(Stream<Path> children=Files.list(root)){
            return children.filter(p->p.getFileName().toString().matches("[0-9]{6}")&&Files.isDirectory(p))
                .sorted(Comparator.comparingLong((Path p)->{try{return Files.getLastModifiedTime(p).toMillis();}catch(IOException e){return 0L;}}).reversed().thenComparing(p->p.getFileName().toString(),Comparator.reverseOrder()))
                .map(p->p.getFileName().toString()).limit(300).collect(Collectors.toList());
        }catch(IOException e){return List.of();}
    }
    /** Project of the working folder, else the newest six-digit number in the conversation that exists under PRODUCTIE; "" when unknown. */
    static String guessProject(Path workDir,List<String> hints,Collection<String> known){
        String fromFolder=PdfAutoFill.project(workDir);if(fromFolder!=null&&known.contains(fromFolder))return fromFolder;
        for(int i=hints.size()-1;i>=0;i--){
            Matcher m=SIX.matcher(Objects.toString(hints.get(i),""));String found=null;while(m.find())if(known.contains(m.group()))found=m.group();
            if(found!=null)return found;
        }
        return "";
    }
    static List<String> relativeSelection(Path project,Path[] selected)throws IOException {
        Path actual=project.toRealPath();List<String> result=new ArrayList<>();
        for(Path file:selected){Path path=file.toRealPath();if(!path.startsWith(actual)||!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))throw new IOException(I18n.text("Alege PDF-uri din proiectul selectat."));
            result.add(actual.relativize(path).toString().replace('\\','/'));}
        return result;
    }
    /** Finished control jobs of a project that have results (the only ones a report can be regenerated from), newest first. */
    static List<String> reportJobs(Path projectFolder){
        Path jobs=projectFolder.resolve("Controle").resolve("Octop-PDFCONTROL");if(!Files.isDirectory(jobs))return List.of();
        try(Stream<Path> children=Files.list(jobs)){
            return children.filter(p->p.getFileName().toString().matches("[a-f0-9]{32}")&&Files.isRegularFile(p.resolve("engine/checked/resultaten.json"))&&Files.isRegularFile(p.resolve("job.json")))
                .filter(p->{try{String state=Json.str(Json.parse(Files.readString(p.resolve("job.json"))),"status");return "done".equals(state)||"partial".equals(state);}catch(Exception e){return false;}})
                .sorted(Comparator.comparingLong((Path p)->{try{return Files.getLastModifiedTime(p.resolve("job.json")).toMillis();}catch(IOException e){return 0L;}}).reversed())
                .map(p->p.getFileName().toString()).limit(50).collect(Collectors.toList());
        }catch(IOException e){return List.of();}
    }
    /** Builds the calls of one action from the project's files; throws a clear, localized message when the action cannot run. */
    static Plan plan(int op,String number,Path folder,List<?> rows,List<String> manual,List<List<String>> manualCompare,String factory,String serverFolder,String job)throws IOException {
        String label=I18n.text(LABELS[op]);
        switch(op){
            case CHECK,EXTRACT,PAIRS,WORKFLOW:{
                List<String> list=manual!=null?manual:PdfAutoFill.drawings(rows);
                if(list.isEmpty())throw new IOException(I18n.text("Proiectul {0} nu are PDF-uri originale in afara de OLD, Controle si Output. Pune desenele in proiect sau alege-le manual.",number));
                if(list.size()>PdfAutoFill.MAX_FILES)throw new IOException(I18n.text("Proiectul {0} are {1} PDF-uri; limita este {2} pe lucrare. Alege manual un subset.",number,list.size(),PdfAutoFill.MAX_FILES));
                Map<String,Object> args=Json.obj("project",number,"files",list);
                if((op==CHECK||op==WORKFLOW)&&factory!=null&&!factory.isBlank())args.put("factory",factory);
                List<Step> steps=new ArrayList<>();
                if(op==PAIRS)steps.add(new Step("pdfcontrol_fix_missing_pairs",Json.obj("project",number,"project_root",folder.toString()),true,false));
                steps.add(new Step(OPERATIONS[op],args,false,op==PAIRS&&manual==null));
                String text=I18n.text("Pornesti {0} pentru proiectul {1} ({2} PDF-uri)? Rezultatele se salveaza in Controle\\Octop-PDFCONTROL; desenele originale nu se modifica.",label,number,list.size());
                if(op==PAIRS)text+="\n"+I18n.text("Plasele pereche lipsa (W) se copiaza ca fisiere noi langa desenul V; nimic nu se suprascrie sau sterge.");
                return new Plan(steps,text,list.size());
            }
            case COMPARE:{
                List<List<String>> sides=manualCompare!=null?manualCompare:PdfAutoFill.compareDefaults(rows);
                if(sides==null||sides.get(0).isEmpty()||sides.get(1).isEmpty())throw new IOException(I18n.text("Proiectul {0} nu are foldere OUD si NIEUW cu PDF-uri. Alege manual versiunile OUD si NIEUW.",number));
                if(sides.get(0).size()>PdfAutoFill.MAX_FILES||sides.get(1).size()>PdfAutoFill.MAX_FILES)throw new IOException(I18n.text("Proiectul {0} are {1} PDF-uri; limita este {2} pe lucrare. Alege manual un subset.",number,Math.max(sides.get(0).size(),sides.get(1).size()),PdfAutoFill.MAX_FILES));
                Map<String,Object> args=Json.obj("project",number,"old_files",sides.get(0),"new_files",sides.get(1));
                String text=I18n.text("Pornesti {0} pentru proiectul {1} ({2} PDF-uri)? Rezultatele se salveaza in Controle\\Octop-PDFCONTROL; desenele originale nu se modifica.",label,number,sides.get(0).size()+" OUD / "+sides.get(1).size()+" NIEUW");
                return new Plan(List.of(new Step(OPERATIONS[op],args,false,false)),text,sides.get(0).size()+sides.get(1).size());
            }
            case REGENERATE:{
                if(job==null||job.isBlank())throw new IOException(I18n.text("Proiectul {0} nu are lucrari de control terminate cu rezultate. Ruleaza mai intai Verifica desenele sau Flux complet.",number));
                String text=I18n.text("Pornesti {0} pentru proiectul {1} ({2} PDF-uri)? Rezultatele se salveaza in Controle\\Octop-PDFCONTROL; desenele originale nu se modifica.",label,number,job);
                return new Plan(List.of(new Step(OPERATIONS[op],Json.obj("project",number,"job_id",job),false,false)),text,1);
            }
            default:{
                Path server=serverFolder==null||serverFolder.isBlank()?null:Path.of(serverFolder);
                if(server==null)throw new IOException(I18n.text("Alege folderul proiectului de pe server."));
                if(!Files.isDirectory(server))throw new IOException(I18n.text("Folderul de pe server nu exista: {0}",serverFolder));
                return new Plan(List.of(new Step("pdfcontrol_compare_server_revisions",Json.obj("project",number,"local_folder",folder.toString(),"server_folder",server.toString()),true,false)),null,1);
            }
        }
    }

    // ---- UI state ----

    private String projectNumber(){return Objects.toString(project.getEditor().getItem(),"").strip();}
    Path root(){return root;}
    /** The file "Deschide raportul" opens: the selected one, else the report PDF, else the first result. */
    Path openTarget(){
        Path chosen=files.getSelectedValue();if(chosen!=null)return chosen;
        for(int i=0;i<artifacts.size();i++){String name=artifacts.get(i).getFileName().toString().toLowerCase(Locale.ROOT);if(name.endsWith(".pdf")&&(name.contains("rapport")||name.contains("vergelijking")))return artifacts.get(i);}
        return artifacts.isEmpty()?null:artifacts.get(0);
    }
    private void layoutFor(){
        int op=operation.getSelectedIndex();
        factory.setVisible(op==CHECK||op==WORKFLOW);choose.setVisible(op<=WORKFLOW);auto.setVisible(op<=WORKFLOW);selection.setVisible(op<=WORKFLOW);
        serverLabel.setVisible(op==SERVER);serverFolder.setVisible(op==SERVER);chooseServer.setVisible(op==SERVER);
        revalidate();repaint();
    }
    /** Shows, in the background, which PDFs the next run will use. */
    void refreshSelection(){
        int op=operation.getSelectedIndex();if(op>WORKFLOW){selection.setText(" ");return;}
        if(manual!=null){selection.setText(I18n.text("PDF-uri: {0} alese manual.",manual.size()));return;}
        if(manualCompare!=null){selection.setText(I18n.text("PDF-uri: OUD {0} / NIEUW {1} alese manual.",manualCompare.get(0).size(),manualCompare.get(1).size()));return;}
        String number=projectNumber();selection.setText(I18n.text("PDF-uri: automat din proiect (desenele originale; OUD/NIEUW la comparare)."));
        if(root==null||!number.matches("[0-9]{6}")||!Files.isDirectory(root.resolve(number)))return;
        int token=++selectionToken;
        new SwingWorker<String,Void>(){
            protected String doInBackground()throws Exception{
                List<Object> rows=rows(number);
                if(op==COMPARE){List<List<String>> sides=PdfAutoFill.compareDefaults(rows);return sides==null?I18n.text("PDF-uri: nu exista foldere OUD si NIEUW (alege manual)."):I18n.text("PDF-uri: OUD {0} / NIEUW {1} (automat).",sides.get(0).size(),sides.get(1).size());}
                int count=PdfAutoFill.drawings(rows).size();return count==0?I18n.text("PDF-uri: nu exista desene originale (alege manual)."):I18n.text("PDF-uri: {0} desene originale (automat).",count);
            }
            protected void done(){try{String text=get();if(token==selectionToken&&manual==null&&manualCompare==null)selection.setText(text);}catch(Exception ignored){}}
        }.execute();
    }
    List<Object> rows(String number){
        return PdfAutoFill.rows(number,(tool,args)->{try{return invoke(server,tool,args);}catch(Exception error){throw new IllegalStateException(I18n.text("Nu pot citi fisierele proiectului: {0}",error.getMessage()),error);}});
    }
    private void lock(boolean on){
        running=on;start.setEnabled(!on);operation.setEnabled(!on);project.setEnabled(!on);factory.setEnabled(!on);choose.setEnabled(!on);auto.setEnabled(!on);serverFolder.setEnabled(!on);chooseServer.setEnabled(!on);progress.setIndeterminate(on);
        if(!on)cancel.setEnabled(false);
    }
    /** Validates the project; returns its folder or reports the problem and returns null. */
    private Path projectFolder(){
        if(root==null){ui.error(rootError!=null?rootError:I18n.text("PDFCONTROL nu a raspuns inca; asteapta o clipa si incearca din nou."));return null;}
        String number=projectNumber();
        if(!number.matches("[0-9]{6}")){ui.error(I18n.text("Alege un proiect din lista (folder cu sase cifre in {0}).",root));return null;}
        Path folder=root.resolve(number);
        if(!Files.isDirectory(folder)){ui.error(I18n.text("Folderul proiectului {0} nu exista in {1}.",number,root));return null;}
        return folder;
    }
    private void chooseManually(){
        Path folder=projectFolder();if(folder==null)return;int op=operation.getSelectedIndex();
        try{
            if(op==COMPARE){List<String> oldFiles=ui.pick(folder,I18n.text("Alege versiunea OUD (veche)"));if(oldFiles==null||oldFiles.isEmpty())return;List<String> newFiles=ui.pick(folder,I18n.text("Alege versiunea NIEUW (noua)"));if(newFiles==null||newFiles.isEmpty())return;manualCompare=List.of(oldFiles,newFiles);manual=null;}
            else{List<String> picked=ui.pick(folder,I18n.text(LABELS[op]));if(picked==null||picked.isEmpty())return;manual=picked;manualCompare=null;}
            refreshSelection();
        }catch(IOException error){ui.error(error.getMessage());}
    }

    // ---- run ----

    private void prepare() {
        if(running)return;
        int op=operation.getSelectedIndex();
        if(op!=SERVER&&readOnly.getAsBoolean()){ui.error(I18n.text("Mod doar citire: PDFCONTROL scrie rezultate in proiect. Schimba permisiunile sau foloseste comparatia cu serverul."));return;}
        Path folder=projectFolder();if(folder==null)return;String number=projectNumber();
        String job=null;
        if(op==REGENERATE){List<String> ids=reportJobs(folder);if(ids.isEmpty()){ui.error(I18n.text("Proiectul {0} nu are lucrari de control terminate cu rezultate. Ruleaza mai intai Verifica desenele sau Flux complet.",number));return;}job=ids.size()==1?ids.get(0):ui.jobId(ids);if(job==null)return;}
        String serverText=serverFolder.getText().strip();
        String factoryName=factory.getSelectedIndex()>0?factory.getSelectedItem().toString().toLowerCase(Locale.ROOT):null;
        List<String> manualFiles=manual;List<List<String>> manualPair=manualCompare;String chosenJob=job;
        lock(true);status.setText(I18n.text("Citesc fisierele proiectului..."));
        new SwingWorker<Plan,Void>(){
            protected Plan doInBackground()throws Exception{
                List<Object> rows=op==SERVER||op==REGENERATE||(manualFiles!=null&&op!=PAIRS&&op!=COMPARE)||(manualPair!=null&&op==COMPARE)?List.of():rows(number);
                return plan(op,number,folder,rows,manualFiles,manualPair,factoryName,serverText,chosenJob);
            }
            protected void done(){
                Plan plan;
                try{plan=get();}catch(Exception error){lock(false);String message=message(error);status.setText(message);details.setText(message);ui.error(message);return;}
                if(plan.confirm()!=null&&!ui.confirm(plan.confirm())){lock(false);status.setText(" ");return;}
                begin(op,number,folder,plan);
            }
        }.execute();
    }
    private static String message(Throwable error){Throwable cause=error;while((cause instanceof java.util.concurrent.ExecutionException||cause instanceof IllegalStateException&&cause.getCause()!=null)&&cause.getCause()!=null)cause=cause.getCause();return Objects.toString(cause.getMessage(),cause.toString());}

    private void begin(int op,String number,Path folder,Plan plan){
        jobId=null;cancelled.set(false);artifacts.clear();open.setEnabled(false);details.setText("");cancel.setEnabled(true);
        selectedFolder.accept(folder);started.accept(I18n.text(LABELS[op]));
        long since=System.currentTimeMillis();
        new SwingWorker<Outcome,Object>(){
            protected Outcome doInBackground()throws Exception{return runPlan(plan,number,folder,since,this::publish);}
            protected void process(List<Object> chunks){Object last=chunks.get(chunks.size()-1);status.setText(String.valueOf(last));}
            protected void done(){
                Outcome outcome=null;
                try{outcome=get();}catch(Exception error){String message=message(error);outcome=new Outcome(Json.obj("status","failed","error",message),message,List.of(),true,message);}
                try{
                    for(Path path:outcome.files())artifacts.addElement(path);
                    details.setText(outcome.details());details.setCaretPosition(0);
                    String state=Json.str(outcome.record(),"status");
                    status.setText(jobId!=null?I18n.text("Lucrare {0}: {1}",jobId,localizedStatus(state)):localizedStatus(state));
                    String limitation=limitations(outcome.record());if(!limitation.isBlank())status.setText(status.getText()+" - "+limitation);
                    if(outcome.error()!=null)status.setText(status.getText()+" - "+outcome.error());
                    status.setToolTipText(status.getText());
                    Path target=openTarget();if(target!=null){files.setSelectedValue(target,true);}
                    open.setEnabled(!artifacts.isEmpty());
                    complete.accept(outcome.record());
                    if(outcome.failed()&&outcome.error()!=null)ui.error(outcome.error());
                }finally{lock(false);finished.run();}
            }
        }.execute();
    }

    private Outcome runPlan(Plan plan,String number,Path folder,long since,Consumer<Object> publish)throws Exception {
        StringBuilder text=new StringBuilder();Object last=null;boolean failed=false;String error=null;List<Path> found=new ArrayList<>();
        for(Step step:plan.steps()){
            if(cancelled.get())break;
            if(step.local()){
                publish.accept(I18n.text("Pas local: {0}",I18n.text(step.tool().equals("pdfcontrol_fix_missing_pairs")?"Completeaza perechi V/W":"Compara revizii server cu local")));
                Tools.Result result=localTools.apply(folder).execute(new ChatClient.ToolCall("pdfcontrol-direct",step.tool(),step.args()));
                String output=result.text==null?"":result.text.strip();
                text.append(I18n.text(step.tool().equals("pdfcontrol_fix_missing_pairs")?"Completeaza perechi V/W":"Compara revizii server cu local")).append(":\n").append(output.length()>4000?output.substring(0,4000)+"...":output).append("\n\n");
                found.addAll(localReports(step.tool(),since));
                if(result.isError){failed=true;error=I18n.text("Pasul a esuat: {0}",output.length()>400?output.substring(0,400)+"...":output);break;}
            }else{
                Map<String,Object> args=new LinkedHashMap<>(step.args());
                if(step.refresh()){List<String> list=PdfAutoFill.drawings(rows(number));if(list.isEmpty())throw new IOException(I18n.text("Proiectul {0} nu are PDF-uri originale in afara de OLD, Controle si Output. Pune desenele in proiect sau alege-le manual.",number));args.put("files",list);}
                Object record=job(step.tool(),args,publish);last=record;
                text.append(describe(summary(number),record));
                String state=Json.str(record,"status");
                Object paths=Json.get(record,"artifacts");
                if(paths instanceof List)for(Object row:(List<?>)paths){String value=Json.str(row,"path");if(value!=null){Path path=Path.of(value);if(Files.isRegularFile(path)&&path.toAbsolutePath().normalize().startsWith(root))found.add(path);}}
                if(!Set.of("done","partial").contains(state)){failed=true;error=Json.str(record,"error");if(error==null)error=localizedStatus(state);break;}
            }
        }
        Object record=last;
        if(record==null){
            List<Object> rows=new ArrayList<>();for(Path path:found)rows.add(Json.obj("path",path.toString()));
            record=Json.obj("status",failed?"failed":cancelled.get()?"cancelled":"done","artifacts",rows);if(error!=null)((Map<String,Object>)record).put("error",error);
        }
        return new Outcome(record,text.toString().strip(),found,failed,error);
    }
    private Object summary(String number){
        try{return invoke(server,"pdfcontrol_job_summary",Json.obj("project",number,"job_id",jobId));}catch(Exception ignored){return null;}
    }
    /** Runs one engine job to its final state and pages its artifacts. */
    private Object job(String tool,Map<String,Object> args,Consumer<Object> publish)throws Exception {
        Object record=invoke(server,tool,args);jobId=Json.str(record,"job_id");boolean requested=false;
        while(Set.of("pending","running").contains(Objects.toString(Json.str(record,"status"),""))) {
            if(cancelled.get() && !requested){invoke(server,"pdfcontrol_cancel_job",Json.obj("project",args.get("project"),"job_id",jobId));requested=true;}
            record=invoke(server,"pdfcontrol_job_status",Json.obj("project",args.get("project"),"job_id",jobId,"wait_seconds",3));
            publish.accept(I18n.text("Lucrare {0}: {1}",jobId,localizedStatus(Json.str(record,"status")))+" - "+Objects.toString(Json.get(record,"progress","item"),""));
        }
        // Fetch paged artifacts without repeating execution or asking the model.
        List<Object> all=new ArrayList<>();Object page=record;
        while(true){Object rows=Json.get(page,"artifacts");if(rows instanceof List)all.addAll((List<?>)rows);Object next=Json.get(page,"next_artifact_offset");if(!(next instanceof Number))break;
            page=invoke(server,"pdfcontrol_job_status",Json.obj("project",args.get("project"),"job_id",jobId,"wait_seconds",0,"artifact_offset",next));}
        if(record instanceof Map)((Map<String,Object>)record).put("artifacts",all);
        return record;
    }
    /** Reports the local tools wrote under the application folder during this run. */
    private List<Path> localReports(String tool,long since){
        Path dir=appDir==null?null:appDir.resolve("analyses").resolve(tool.equals("pdfcontrol_fix_missing_pairs")?"pairs":"revisions");
        if(dir==null||!Files.isDirectory(dir))return List.of();
        try(Stream<Path> children=Files.list(dir)){
            return children.filter(Files::isRegularFile).filter(p->{try{return Files.getLastModifiedTime(p).toMillis()>=since-2000;}catch(IOException e){return false;}})
                .sorted(Comparator.comparingLong((Path p)->{try{return Files.getLastModifiedTime(p).toMillis();}catch(IOException e){return 0L;}}).reversed()).limit(3).collect(Collectors.toList());
        }catch(IOException e){return List.of();}
    }
    private static String number(Object value){return value instanceof Number n?String.valueOf(n.longValue()):Objects.toString(value,"?");}
    /** Plain-language result of one engine job (status, counters, notes, report). */
    static String describe(Object summary,Object record){
        List<String> lines=new ArrayList<>();
        lines.add(I18n.text("Lucrare {0}: {1}",Objects.toString(Json.str(record,"job_id"),"-"),localizedStatus(Json.str(record,"status"))));
        String error=Json.str(record,"error");if(error==null)error=Json.str(summary,"error");if(error!=null)lines.add(error);
        Object drawings=Json.get(summary,"counters","drawings"),findings=Json.get(summary,"counters","findings");
        if(drawings!=null||findings!=null)lines.add(I18n.text("Desene: {0}, constatari: {1}",number(drawings),number(findings)));
        for(String key:new String[]{"summary","pair_summary"}){Object value=Json.get(summary,key);if(value instanceof Map){String json=Json.write(value);lines.add(key+": "+(json.length()>700?json.substring(0,700)+"...":json));}}
        String note=Json.str(summary,"note");if(note!=null)lines.add(note);
        String limitation=limitations(record);if(!limitation.isBlank())lines.add(limitation);
        for(String key:new String[]{"report","differences_report"}){String value=Json.str(record,key);if(value!=null)lines.add(I18n.text("Raport: {0}",value));}
        return String.join("\n",lines)+"\n\n";
    }
    static String limitations(Object record){Object codes=Json.get(record,"limitation_codes");List<String> text=new ArrayList<>();if(codes instanceof List<?> list)for(Object code:list)text.add(I18n.text("factory_catalogue_invalid".equals(code)?"Catalogul fabricii nu poate fi validat; verifica sursa.":"Catalogul fabricii nu este confirmat sau disponibil; verificarea este partiala."));return String.join(" - ",text);}
    static String localizedStatus(String status){return I18n.text(switch(Objects.toString(status,"")){case "done"->"Finalizat";case "partial"->"Partial: verificare manuala necesara";case "running"->"In lucru";case "pending"->"In asteptare";case "cancelled"->"Anulat";default->"Eroare";});}

    /** Real dialogs. */
    private final class SwingUi implements Ui {
        public boolean confirm(String text){return JOptionPane.showConfirmDialog(PdfControlDialog.this,text,"PDFCONTROL",JOptionPane.YES_NO_OPTION)==JOptionPane.YES_OPTION;}
        public void error(String text){JOptionPane.showMessageDialog(PdfControlDialog.this,text,"PDFCONTROL",JOptionPane.WARNING_MESSAGE);}
        public String serverFolder(String current){
            JFileChooser chooser=new JFileChooser(!current.isBlank()&&Files.isDirectory(Path.of(current))?new java.io.File(current):Files.isDirectory(Path.of("J:\\"))?new java.io.File("J:\\"):null);
            chooser.setDialogTitle(I18n.text("Alege folderul proiectului de pe server."));chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            return chooser.showOpenDialog(PdfControlDialog.this)==JFileChooser.APPROVE_OPTION?chooser.getSelectedFile().getPath():null;
        }
        public String jobId(List<String> ids){
            Object picked=JOptionPane.showInputDialog(PdfControlDialog.this,I18n.text("Lucrarea de control pentru raport (cea mai noua prima)"),"PDFCONTROL",JOptionPane.QUESTION_MESSAGE,null,ids.toArray(),ids.get(0));
            return picked==null?null:picked.toString();
        }
        public List<String> pick(Path folder,String title)throws IOException{
            JFileChooser chooser=new JFileChooser(folder.toFile());chooser.setDialogTitle(title);chooser.setMultiSelectionEnabled(true);chooser.setFileFilter(new FileNameExtensionFilter("PDF","pdf"));
            if(chooser.showOpenDialog(PdfControlDialog.this)!=JFileChooser.APPROVE_OPTION)return null;
            return relativeSelection(folder,Arrays.stream(chooser.getSelectedFiles()).map(java.io.File::toPath).toArray(Path[]::new));
        }
    }
}