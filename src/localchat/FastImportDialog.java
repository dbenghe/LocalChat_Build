package localchat;
import javax.swing.*;import javax.swing.filechooser.FileNameExtensionFilter;import java.awt.*;import java.awt.event.*;import java.nio.file.*;import java.util.*;import java.util.List;
/** Direct local conversion. No model calls, no conversation edits, no production source overwrite. */
final class FastImportDialog extends JDialog{
 final JTextField project=new JTextField(8),source=new JTextField(),output=new JTextField();
 final JComboBox<String> factory=new JComboBox<>(new String[]{I18n.text("Fabrica neconfirmata"),"Soest","Alphen","Veenoord","Lelystad"});
 final JButton start=new JButton(I18n.text("Pregateste import FAST")),stop=new JButton(I18n.text("Stop"));
 final JTextArea result=new JTextArea(6,50);final JLabel status=new JLabel(I18n.text("Alege proiectul, fabrica, sursa si copia FAST."));
 private final Path folder;private final Tools tools;private boolean running,stopped;
 FastImportDialog(JFrame owner,Path folder,Tools tools){
  super(owner,I18n.text("Pregateste import FAST"),false);this.folder=folder;this.tools=tools;
  if(folder.getFileName()!=null&&folder.getFileName().toString().matches("[0-9]{6}"))project.setText(folder.getFileName().toString());
  JPanel form=new JPanel(new GridBagLayout());GridBagConstraints g=new GridBagConstraints();g.insets=new Insets(5,6,5,6);g.fill=GridBagConstraints.HORIZONTAL;
  row(form,g,0,"Proiect",project,null);row(form,g,1,"Fabrica",factory,null);row(form,g,2,"Fisier XLSX sursa",source,choose(false));row(form,g,3,"Copie FAST noua",output,choose(true));
  result.setEditable(false);result.setLineWrap(true);result.setWrapStyleWord(true);result.setFont(UIManager.getFont("Label.font"));
  JPanel buttons=new JPanel(new FlowLayout(FlowLayout.RIGHT));buttons.add(stop);buttons.add(start);stop.setEnabled(false);
  JPanel bottom=new JPanel(new BorderLayout());bottom.add(status,BorderLayout.CENTER);bottom.add(buttons,BorderLayout.SOUTH);
  JPanel content=new JPanel(new BorderLayout(8,8));content.setBorder(BorderFactory.createEmptyBorder(8,8,8,8));content.add(form,BorderLayout.NORTH);content.add(new JScrollPane(result),BorderLayout.CENTER);content.add(bottom,BorderLayout.SOUTH);setContentPane(content);
  start.addActionListener(e->runPreparation());stop.addActionListener(e->{stopped=true;tools.cancel();stop.setEnabled(false);status.setText(I18n.text("Oprire solicitata; sursele raman nemodificate."));});
  setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);addWindowListener(new WindowAdapter(){public void windowClosing(WindowEvent e){if(running){stopped=true;tools.cancel();status.setText(I18n.text("Oprire solicitata; sursele raman nemodificate."));}else dispose();}});
  setMinimumSize(new Dimension(580,360));setSize(700,410);setLocationRelativeTo(owner);
 }
 private void row(JPanel p,GridBagConstraints g,int y,String label,JComponent field,JButton button){g.gridy=y;g.gridx=0;g.weightx=0;p.add(new JLabel(I18n.text(label)),g);g.gridx=1;g.weightx=1;p.add(field,g);if(button!=null){g.gridx=2;g.weightx=0;p.add(button,g);}}
 private JButton choose(boolean save){JButton b=new JButton("…");b.addActionListener(e->{if(running)return;JFileChooser c=new JFileChooser(folder.toFile());c.setFileFilter(new FileNameExtensionFilter("XLSX","xlsx"));if((save?c.showSaveDialog(this):c.showOpenDialog(this))!=JFileChooser.APPROVE_OPTION)return;String path=c.getSelectedFile().toPath().toAbsolutePath().toString();(save?output:source).setText(path);if(!save&&output.getText().isBlank()){String name=c.getSelectedFile().getName().replaceFirst("(?i)[.]xlsx$","");output.setText(folder.resolve(name+"_FAST.xlsx").toString());}});return b;}
 void runPreparation(){
  if(running||stopped)return;
  if(!project.getText().strip().matches("[0-9]{6}")||factory.getSelectedIndex()==0||source.getText().isBlank()||output.getText().isBlank()){status.setText(I18n.text("Alege proiectul, fabrica, sursa si copia FAST."));return;}
  Map<String,Object> args=Json.obj("project",project.getText().strip(),"factory",factory.getSelectedItem().toString().toLowerCase(Locale.ROOT),"input",source.getText().strip(),"output",output.getText().strip());running=true;start.setEnabled(false);stop.setEnabled(true);status.setText(I18n.text("Comparatie locala in curs; nu se trimit date la model."));
  new SwingWorker<Tools.Result,Void>(){protected Tools.Result doInBackground(){return tools.execute(new ChatClient.ToolCall("direct-fast","pdfcontrol_prepare_fast_import",args));}protected void done(){running=false;start.setEnabled(!stopped);stop.setEnabled(false);try{Tools.Result r=get();result.setText(r.text);status.setText(I18n.text(r.isError?"Control local nereusit; consulta detaliile.":"Control local terminat. Sursele nu au fost modificate."));}catch(Exception e){status.setText(e.getMessage());}}}.execute();
 }
}
