package localchat;
import javax.swing.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.function.*;

/** Local session navigation and explicitly estimated next-request context. */
final class WorkspacePanel extends JPanel {
    final JTextField search=new JTextField();
    final DefaultListModel<Conversations.Saved> sessions=new DefaultListModel<>();
    final JList<Conversations.Saved> list=new JList<>(sessions);
    final JSpinner limit=new JSpinner(new SpinnerNumberModel(0,0,2000000,1024));
    final JCheckBox archived=new JCheckBox();
    final JCheckBox autoCompact=new JCheckBox();
    final JSpinner autoThreshold=new JSpinner(new SpinnerNumberModel(70,30,95,5));
    private final JLabel thresholdLabel=new JLabel();
    final JProgressBar context=new JProgressBar(0,100);
    final JTextArea details=new JTextArea(8,20);
    private final JLabel heading=new JLabel(), limitLabel=new JLabel();
    private final JButton fresh=new JButton(),rename=new JButton(),pin=new JButton(),remove=new JButton();
    private List<Conversations.Saved> all=List.of();
    private boolean refreshing;
    WorkspacePanel(Runnable newChat,Consumer<Conversations.Saved> open,Runnable title,Runnable togglePin) {this(newChat,open,title,togglePin,()->{},()->{},()->{});}
    WorkspacePanel(Runnable newChat,Consumer<Conversations.Saved> open,Runnable title,Runnable togglePin,Runnable delete,Runnable archive,Runnable export) {
        super(new BorderLayout(5,7));setPreferredSize(new Dimension(255,400));
        setBorder(BorderFactory.createEmptyBorder(8,8,8,3));
        JPanel top=new JPanel(new BorderLayout(4,5));top.add(heading,BorderLayout.NORTH);top.add(search);
        JPanel actions=new JPanel(new GridLayout(2,2,3,3));actions.add(fresh);actions.add(remove);actions.add(rename);actions.add(pin);top.add(actions,BorderLayout.SOUTH);
        JPanel header=new JPanel(new BorderLayout(2,3));header.add(top);header.add(archived,BorderLayout.SOUTH);
        add(header,BorderLayout.NORTH); add(new JScrollPane(list));
        archived.addActionListener(e->filter());remove.addActionListener(e->delete.run());
        list.addMouseListener(new MouseAdapter(){
            private void popup(MouseEvent e){if(!e.isPopupTrigger())return;int index=list.locationToIndex(e.getPoint());
                if(index<0 || !list.getCellBounds(index,index).contains(e.getPoint()))return;
                list.setSelectedIndex(index);if(!list.getSelectedValue().file().equals(sessions.get(index).file()))return;
                JPopupMenu menu=new JPopupMenu();
                JMenuItem renameItem=new JMenuItem(I18n.text("Redenumește conversația…"));renameItem.addActionListener(x->title.run());menu.add(renameItem);
                JMenuItem a=new JMenuItem(I18n.text(list.getSelectedValue().state().archived?"Restaurează conversația":"Arhivează conversația"));a.addActionListener(x->archive.run());menu.add(a);
                JMenuItem ex=new JMenuItem(I18n.text("Exportă chat JSON…"));ex.addActionListener(x->export.run());menu.add(ex);
                JMenuItem d=new JMenuItem(I18n.text("Șterge conversația"));d.addActionListener(x->delete.run());menu.add(d);menu.show(list,e.getX(),e.getY());}
            public void mousePressed(MouseEvent e){popup(e);}public void mouseReleased(MouseEvent e){popup(e);}
        });
        list.setCellRenderer(new DefaultListCellRenderer(){
            @Override public Component getListCellRendererComponent(JList<?> l,Object value,int index,boolean selected,boolean focus) {
                Conversations.Saved s=(Conversations.Saved)value;
                return super.getListCellRendererComponent(l,(s.state().pinned?"* ":"")+s.title(),index,selected,focus);
            }});
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(e->{if(!refreshing && !e.getValueIsAdjusting() && list.getSelectedValue()!=null)open.accept(list.getSelectedValue());});
        list.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){if(e.getClickCount()==2 && list.getSelectedValue()!=null)open.accept(list.getSelectedValue());}});
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,0),"open-session");
        list.getActionMap().put("open-session",new AbstractAction(){public void actionPerformed(ActionEvent e){if(list.getSelectedValue()!=null)open.accept(list.getSelectedValue());}});
        fresh.addActionListener(e->newChat.run());rename.addActionListener(e->title.run());pin.addActionListener(e->togglePin.run());
        search.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){filter();}
            public void removeUpdate(DocumentEvent e){filter();}public void changedUpdate(DocumentEvent e){filter();}});
        JPanel bottom=new JPanel(new BorderLayout(3,5));
        JPanel config=new JPanel(new BorderLayout(3,4));config.add(limitLabel,BorderLayout.NORTH);config.add(limit);
        JPanel auto=new JPanel(new BorderLayout(4,3));auto.add(autoCompact,BorderLayout.NORTH);
        JPanel threshold=new JPanel(new BorderLayout(3,2));threshold.add(thresholdLabel);threshold.add(autoThreshold,BorderLayout.EAST);auto.add(threshold);
        config.add(auto,BorderLayout.SOUTH);bottom.add(config,BorderLayout.NORTH);
        context.setStringPainted(true);bottom.add(context);
        details.setEditable(false);details.setLineWrap(true);details.setWrapStyleWord(true);details.setOpaque(false);
        bottom.add(details,BorderLayout.SOUTH);add(bottom,BorderLayout.SOUTH);
        translate();
    }
    void translate() {
        heading.setText(I18n.text("Conversații"));search.putClientProperty("JTextField.placeholderText",I18n.text("Caută conversații"));search.setToolTipText(I18n.text("Caută conversații"));
        search.getAccessibleContext().setAccessibleName(I18n.text("Caută conversații"));
        fresh.setText("+");fresh.setToolTipText(I18n.text("Conversație nouă"));
        remove.setText("−");remove.setToolTipText(I18n.text("Șterge conversația"));archived.setText(I18n.text("Arhivate"));
        rename.setText(I18n.text("Titlu"));pin.setText(I18n.text("Fixează"));pin.setToolTipText(I18n.text("Fixează / desprinde"));
        limitLabel.setText("<html>"+I18n.text("Limită context (0 = necunoscut)"));limitLabel.setPreferredSize(new Dimension(220,32));
        autoCompact.setText(I18n.text("Compactare automată"));
        autoCompact.setToolTipText(I18n.text("Necesită o limită de context cunoscută. Folosește modelul curent și consumă tokeni."));
        thresholdLabel.setText(I18n.text("Prag context (%)"));
        autoThreshold.getAccessibleContext().setAccessibleName(I18n.text("Prag context (%)"));
        context.setToolTipText(I18n.text("Estimare pentru cererea următoare, nu consum facturat."));
    }
    void select(Path file) {
        refreshing=true;
        try{list.clearSelection();if(file!=null)for(int i=0;i<sessions.size();i++)if(sessions.get(i).file().equals(file)){list.setSelectedIndex(i);break;}}
        finally{refreshing=false;}
    }
    void refresh(List<Conversations.Saved> saved) {all=saved;filter();}
    private void filter() {
        String q=search.getText().strip().toLowerCase(Locale.ROOT);
        Conversations.Saved selected=list.getSelectedValue();
        refreshing=true;
        try {
            sessions.clear();
            for(var s:all)if(s.state().archived==archived.isSelected() && (s.title()+" "+Conversations.shortId(s.file())).toLowerCase(Locale.ROOT).contains(q)) {
                sessions.addElement(s);
                if(selected!=null && selected.file().equals(s.file()))list.setSelectedIndex(sessions.size()-1);
            }
        }finally{refreshing=false;}
    }
    void meter(long system,long tools,long conversation,int maximum,String usage) {
        long total=system+tools+conversation;
        long percent=maximum>0 ? (long)Math.ceil(total*100.0/maximum):0;
        context.setValue((int)Math.min(100,percent));
        context.setString(maximum>0 ? I18n.text("Context: ~{0}% · {1}/{2}",ContextRing.percentage(total,maximum),total,maximum)
                : I18n.text("Context: ~{0} · limită necunoscută",total));
        details.setText(I18n.text("Sistem: ~{0}\nTool-uri: ~{1}\nConversație: ~{2}",system,tools,conversation)+"\n"+usage);
    }
}
