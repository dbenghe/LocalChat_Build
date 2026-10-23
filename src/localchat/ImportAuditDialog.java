package localchat;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/** Offline comparison, isolated from chat generation; no model or compaction calls. */
final class ImportAuditDialog extends JDialog {
    final JTextField project=new JTextField(8);
    final JComboBox<String> factory=new JComboBox<>(new String[]{I18n.text("Fabrica neconfirmata"),"Soest","Alphen","Veenoord","Lelystad"});
    final JTextField importPaths=new JTextField(),drawingPaths=new JTextField(),catalogue=new JTextField();
    final JButton start=new JButton(I18n.text("Compara local listele de import")),cancel=new JButton(I18n.text("Stop")),open=new JButton(I18n.text("Deschide raportul"));
    final JTextArea result=new JTextArea(7,60);
    final JLabel status=new JLabel(I18n.text("Fara API: selecteaza fabrica, listele si optional desenele."));
    final JProgressBar progress=new JProgressBar();
    private final Path folder;private final Tools tools;private List<String> imports=List.of(),drawings=List.of();private boolean running,stopped;private Path report;
    ImportAuditDialog(JFrame owner,Path folder,Tools tools){
        super(owner,I18n.text("Compara local listele de import"),false);this.folder=folder;this.tools=tools;
        if(folder.getFileName()!=null&&folder.getFileName().toString().matches("[0-9]{6}"))project.setText(folder.getFileName().toString());
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        JPanel form=new JPanel(new GridBagLayout());GridBagConstraints g=new GridBagConstraints();g.insets=new Insets(5,6,5,6);g.fill=GridBagConstraints.HORIZONTAL;
        row(form,g,0,"Proiect",project,null);row(form,g,1,"Fabrica",factory,null);
        importPaths.setEditable(false);drawingPaths.setEditable(false);catalogue.setToolTipText(I18n.text("Catalog gol: snapshotul fabricii selectate."));
        row(form,g,2,"Liste de import",importPaths,choose(()->select(false)));
        row(form,g,3,"Desene PDF",drawingPaths,choose(()->select(true)));
        row(form,g,4,"Catalog XLSX sau JSON",catalogue,choose(this::selectCatalogue));
        result.setEditable(false);result.setLineWrap(true);result.setWrapStyleWord(true);result.setFont(UIManager.getFont("Label.font"));
        JPanel buttons=new JPanel(new FlowLayout(FlowLayout.RIGHT));buttons.add(open);buttons.add(cancel);buttons.add(start);cancel.setEnabled(false);open.setEnabled(false);
        JPanel bottom=new JPanel(new BorderLayout(4,4));bottom.add(status,BorderLayout.NORTH);bottom.add(progress,BorderLayout.CENTER);bottom.add(buttons,BorderLayout.SOUTH);
        JPanel content=new JPanel(new BorderLayout(8,8));content.setBorder(BorderFactory.createEmptyBorder(10,10,10,10));content.add(form,BorderLayout.NORTH);content.add(new JScrollPane(result),BorderLayout.CENTER);content.add(bottom,BorderLayout.SOUTH);setContentPane(content);
        start.addActionListener(e->runAudit());cancel.addActionListener(e->{stopped=true;tools.cancel();cancel.setEnabled(false);status.setText(I18n.text("Oprire solicitata; sursele raman nemodificate."));});
        open.addActionListener(e->{try{if(report!=null&&Files.isRegularFile(report))Desktop.getDesktop().browse(report.toUri());}catch(Exception error){status.setText(error.getMessage());}});
        addWindowListener(new WindowAdapter(){public void windowClosing(WindowEvent e){if(running){stopped=true;tools.cancel();status.setText(I18n.text("Oprire solicitata; sursele raman nemodificate."));}else dispose();}});
        setMinimumSize(new Dimension(580,430));setSize(720,490);setLocationRelativeTo(owner);
    }
    private void row(JPanel panel,GridBagConstraints g,int y,String label,JComponent field,JButton button){g.gridy=y;g.gridx=0;g.weightx=0;panel.add(new JLabel(I18n.text(label)),g);g.gridx=1;g.weightx=1;panel.add(field,g);if(button!=null){g.gridx=2;g.weightx=0;panel.add(button,g);}}
    private JButton choose(Runnable action){JButton button=new JButton("…");button.addActionListener(e->action.run());return button;}
    private JFileChooser chooser(String label,String...extensions){JFileChooser chooser=new JFileChooser(folder.toFile());chooser.setDialogTitle(I18n.text(label));chooser.setFileFilter(new FileNameExtensionFilter(I18n.text(label),extensions));return chooser;}
    private void select(boolean pdf){if(running)return;JFileChooser chooser=pdf?chooser("Desene PDF","pdf"):chooser("Liste de import","xlsx","csv");chooser.setMultiSelectionEnabled(true);if(chooser.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return;List<String> files=Arrays.stream(chooser.getSelectedFiles()).map(f->f.toPath().toAbsolutePath().toString()).toList();if(pdf){drawings=files;drawingPaths.setText(String.join("; ",files));}else{imports=files;importPaths.setText(String.join("; ",files));}}
    private void selectCatalogue(){if(running)return;JFileChooser chooser=chooser("Catalog XLSX sau JSON","xlsx","json");if(chooser.showOpenDialog(this)==JFileChooser.APPROVE_OPTION)catalogue.setText(chooser.getSelectedFile().getAbsolutePath());}
    void runAudit(){
        if(running)return;
        if(!project.getText().strip().matches("[0-9]{6}")||factory.getSelectedIndex()==0||imports.isEmpty()){status.setText(I18n.text("Alege proiectul, fabrica si cel putin o lista de import."));return;}
        Map<String,Object> args=Json.obj("project",project.getText().strip(),"factory",factory.getSelectedItem().toString().toLowerCase(Locale.ROOT),"imports",imports,"drawings",drawings);if(!catalogue.getText().isBlank())args.put("catalogue",catalogue.getText().strip());
        running=true;start.setEnabled(false);cancel.setEnabled(true);open.setEnabled(false);report=null;progress.setIndeterminate(true);status.setText(I18n.text("Comparatie locala in curs; nu se trimit date la model."));
        new SwingWorker<Tools.Result,Void>(){
            protected Tools.Result doInBackground(){return tools.execute(new ChatClient.ToolCall("local-audit","pdfcontrol_compare_import",args));}
            protected void done(){running=false;progress.setIndeterminate(false);cancel.setEnabled(false);start.setEnabled(!stopped);try{Tools.Result value=get();result.setText(value.text);status.setText(I18n.text(value.isError?"Control local nereusit; consulta detaliile.":"Control local terminat. Sursele nu au fost modificate."));if(!value.isError){Object data=Json.parse(value.text);String html=Json.str(data,"report_html");if(html!=null){report=Path.of(html);open.setEnabled(Files.isRegularFile(report));}}}catch(Exception error){status.setText(error.getMessage());}}
        }.execute();
    }
}
