package localchat;
import java.awt.*;
import java.io.IOException;
import java.util.List;
import javax.swing.*;
import javax.swing.table.*;

/** Inspectable, user-controlled local memory; manual instructions and chat archives are separate. */
final class MemorySettingsDialog extends JDialog {
    final JCheckBox enabled=new JCheckBox(),allowTools=new JCheckBox();
    final DefaultTableModel model=new DefaultTableModel(){public boolean isCellEditable(int r,int c){return false;}};
    final JTable table=new JTable(model);final JTextArea details=new JTextArea(5,44);
    final JButton add=new JButton(),edit=new JButton(),remove=new JButton(),clear=new JButton();
    private final LocalMemories store;private final Runnable changed;private final String currentProject;private List<LocalMemories.Entry> entries=List.of();
    MemorySettingsDialog(Frame owner,LocalMemories store,String project,Runnable changed){
        super(owner,I18n.text("Memorii locale"),true);this.store=store;this.changed=changed;currentProject=project;
        enabled.setText(I18n.text("Activeaza memoriile LocalChat"));enabled.setSelected(store.enabled());allowTools.setText(I18n.text("Permite memorii automate din conversatii cu unelte"));allowTools.setSelected(store.allowTools());
        JPanel settings=new JPanel();settings.setLayout(new BoxLayout(settings,BoxLayout.Y_AXIS));settings.add(enabled);settings.add(allowTools);
        JTextArea help=new JTextArea(I18n.text("Retine local preferintele declarate in chat si foloseste-le in conversatii viitoare. Cererile explicite de retinere se salveaza imediat. Optiunea pentru unelte controleaza memorarea automata. Notele de proiect se aplica doar proiectului curent."));help.setEditable(false);help.setLineWrap(true);help.setWrapStyleWord(true);help.setOpaque(false);help.setRows(4);settings.add(help);settings.add(new JLabel(I18n.text("Preferintele relevante sunt incluse in contextul trimis modelului API selectat.")));
        JLabel path=new JLabel(store.file.toString());path.putClientProperty("html.disable",true);path.setToolTipText(store.file.toString());settings.add(Box.createVerticalStrut(6));settings.add(path);
        model.setColumnIdentifiers(new String[]{I18n.text("Domeniu"),I18n.text("Proiect"),I18n.text("Memorie"),I18n.text("Origine")});table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);table.setAutoCreateRowSorter(true);table.setRowHeight(25);
        table.setDefaultRenderer(Object.class,new DefaultTableCellRenderer(){public Component getTableCellRendererComponent(JTable t,Object value,boolean selected,boolean focus,int row,int column){super.getTableCellRendererComponent(t,value,selected,focus,row,column);putClientProperty("html.disable",true);setToolTipText(value==null?null:value.toString());return this;}});
        table.getSelectionModel().addListSelectionListener(e->{var entry=selected();details.setText(entry==null?"":entry.text());details.setCaretPosition(0);edit.setEnabled(entry!=null);remove.setEnabled(entry!=null);});
        details.setEditable(false);details.setLineWrap(true);details.setWrapStyleWord(true);
        JPanel buttons=new JPanel(new FlowLayout(FlowLayout.LEFT));add.setText(I18n.text("Adauga memorie"));edit.setText(I18n.text("Editeaza memoria"));remove.setText(I18n.text("Sterge memoria selectata"));clear.setText(I18n.text("Sterge toate memoriile LocalChat"));for(JButton b:new JButton[]{add,edit,remove,clear})buttons.add(b);
        add.addActionListener(e->entryDialog(null));edit.addActionListener(e->{var entry=selected();if(entry!=null)entryDialog(entry);});remove.addActionListener(e->{var entry=selected();if(entry!=null)run(()->store.remove(entry.id()));});
        clear.addActionListener(e->{if(JOptionPane.showConfirmDialog(this,I18n.text("Stergi toate memoriile locale? Conversatiile, regulile manuale si PROJECT_INFO.md se gestioneaza separat."),I18n.text("Sterge toate memoriile LocalChat"),JOptionPane.YES_NO_OPTION)==JOptionPane.YES_OPTION)run(store::clear);});
        enabled.addActionListener(e->{store.settings.setProperty("memory.enabled",Boolean.toString(enabled.isSelected()));updateEnabled();changed.run();});allowTools.addActionListener(e->{store.settings.setProperty("memory.allowTools",Boolean.toString(allowTools.isSelected()));changed.run();});
        JPanel center=new JPanel(new BorderLayout(5,5));center.add(new JScrollPane(table));center.add(new JScrollPane(details),BorderLayout.SOUTH);
        JPanel root=new JPanel(new BorderLayout(8,10));root.setBorder(BorderFactory.createEmptyBorder(14,14,14,14));root.add(settings,BorderLayout.NORTH);root.add(center);root.add(buttons,BorderLayout.SOUTH);setContentPane(root);setSize(810,590);setMinimumSize(new Dimension(720,500));setLocationRelativeTo(owner);setDefaultCloseOperation(DISPOSE_ON_CLOSE);updateEnabled();reload();
    }
    private void updateEnabled(){allowTools.setEnabled(enabled.isSelected());}
    private LocalMemories.Entry selected(){int row=table.getSelectedRow();return row<0||table.convertRowIndexToModel(row)>=entries.size()?null:entries.get(table.convertRowIndexToModel(row));}
    private void reload(){try{entries=store.all();model.setRowCount(0);for(var e:entries)model.addRow(new Object[]{I18n.text(e.project().isEmpty()?"General":"Proiect"),e.project(),e.text(),I18n.text(e.source().equals("manual")?"Adaugata manual":"Declarata in chat")});details.setText("");edit.setEnabled(false);remove.setEnabled(false);}catch(IOException e){error(e);}}
    interface Work{void run()throws IOException;}
    private void run(Work operation){try{operation.run();reload();changed.run();}catch(IOException error){error(error);}}
    private void error(Exception error){JOptionPane.showMessageDialog(this,error.getMessage(),I18n.text("Memorii locale"),JOptionPane.ERROR_MESSAGE);}
    private void entryDialog(LocalMemories.Entry entry){
        JComboBox<String> scope=new JComboBox<>(new String[]{I18n.text("General"),I18n.text("Proiect")});JTextField project=new JTextField(entry==null?currentProject:entry.project());JTextArea text=new JTextArea(entry==null?"":entry.text(),6,48);text.setLineWrap(true);text.setWrapStyleWord(true);scope.setSelectedIndex(entry!=null&&!entry.project().isEmpty()?1:0);project.setEnabled(scope.getSelectedIndex()==1);scope.addActionListener(e->project.setEnabled(scope.getSelectedIndex()==1));
        JPanel fields=new JPanel(new GridLayout(2,2,6,6));fields.add(new JLabel(I18n.text("Domeniu")));fields.add(scope);fields.add(new JLabel(I18n.text("Proiect")));fields.add(project);JPanel form=new JPanel(new BorderLayout(6,8));form.add(fields,BorderLayout.NORTH);form.add(new JScrollPane(text));
        if(JOptionPane.showConfirmDialog(this,form,I18n.text(entry==null?"Adauga memorie":"Editeaza memoria"),JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION){String id=scope.getSelectedIndex()==0?"":project.getText().strip();run(()->{if(entry==null)store.add(id,text.getText(),"manual","");else store.replace(entry.id(),id,text.getText());});}
    }
}
