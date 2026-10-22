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
    private final JLabel emptyHint=new JLabel();
    final JButton resetFilters=new JButton();
    private final JPanel emptyPanel=new JPanel(new BorderLayout(4,6));
    private Consumer<Conversations.Saved> opener;
    private Conversations.Saved pendingOpen;
    private final javax.swing.Timer openTimer=new javax.swing.Timer(500,e->flushOpen());
    private void flushOpen(){openTimer.stop();var saved=pendingOpen;pendingOpen=null;if(saved!=null)opener.accept(saved);}
    private void scheduleOpen(Conversations.Saved saved){pendingOpen=saved;Object interval=Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval");openTimer.setInitialDelay(interval instanceof Number n?Math.max(250,n.intValue()):500);openTimer.setRepeats(false);openTimer.restart();}
    private void openSelection(Conversations.Saved saved){if(EventQueue.getCurrentEvent() instanceof MouseEvent mouse&&SwingUtilities.isLeftMouseButton(mouse))scheduleOpen(saved);else{openTimer.stop();pendingOpen=null;opener.accept(saved);}}

    private Set<Path> runningFiles=Set.of();
    void running(Set<Path> files){if(!files.equals(runningFiles)){runningFiles=Set.copyOf(files);list.repaint();}}

    Consumer<String> statusChange=value->{};
    final JButton notifications=new JButton();
    private final javax.swing.Timer notificationPulse=new javax.swing.Timer(700,e->{notifications.putClientProperty("JButton.buttonType",Boolean.TRUE.equals(notifications.getClientProperty("pulse"))?null:"roundRect");notifications.putClientProperty("pulse",!Boolean.TRUE.equals(notifications.getClientProperty("pulse")));notifications.setForeground(Boolean.TRUE.equals(notifications.getClientProperty("pulse"))?new Color(getBackground().getRed()<128?0x32BBCB:0x137585):UIManager.getColor("Label.foreground"));});
    WorkspacePanel(Runnable newChat,Consumer<Conversations.Saved> open,Runnable title,Runnable togglePin) {this(newChat,open,title,togglePin,()->{},()->{},()->{});}
    WorkspacePanel(Runnable newChat,Consumer<Conversations.Saved> open,Runnable title,Runnable togglePin,Runnable delete,Runnable archive,Runnable export) {
        super(new BorderLayout(5,7));opener=open;setPreferredSize(new Dimension(255,400));
        setBorder(BorderFactory.createEmptyBorder(8,8,8,3));
        JPanel top=new JPanel(new BorderLayout(4,5));JPanel headingRow=new JPanel(new BorderLayout());headingRow.add(heading);headingRow.add(notifications,BorderLayout.EAST);top.add(headingRow,BorderLayout.NORTH);notifications.setMargin(new Insets(2,4,2,4));notifications.addActionListener(e->showNotifications());top.add(search);
        JPanel actions=new JPanel(new GridLayout(2,2,3,3));actions.add(fresh);actions.add(remove);actions.add(rename);actions.add(pin);top.add(actions,BorderLayout.SOUTH);
        JPanel header=new JPanel(new BorderLayout(2,3));header.add(top);header.add(archived,BorderLayout.SOUTH);
        add(header,BorderLayout.NORTH);
        JPanel sessionArea=new JPanel(new BorderLayout(3,5));sessionArea.add(new JScrollPane(list));emptyPanel.add(emptyHint);emptyPanel.add(resetFilters,BorderLayout.SOUTH);emptyPanel.setBorder(BorderFactory.createEmptyBorder(5,2,5,2));sessionArea.add(emptyPanel,BorderLayout.SOUTH);add(sessionArea);
        resetFilters.addActionListener(e->{search.setText("");archived.setSelected(false);filter();});
        archived.addActionListener(e->{openTimer.stop();pendingOpen=null;filter();});remove.addActionListener(e->delete.run());
        list.addMouseListener(new MouseAdapter(){
            private void popup(MouseEvent e){if(!e.isPopupTrigger())return;int index=list.locationToIndex(e.getPoint());
                if(index<0 || !list.getCellBounds(index,index).contains(e.getPoint()))return;
                list.setSelectedIndex(index);if(!list.getSelectedValue().file().equals(sessions.get(index).file()))return;
                JPopupMenu menu=new JPopupMenu();
                JMenuItem renameItem=new JMenuItem(I18n.text("Redenumeste conversatia…"));renameItem.addActionListener(x->title.run());menu.add(renameItem);
                JMenuItem a=new JMenuItem(I18n.text(list.getSelectedValue().state().archived?"Restaureaza conversatia":"Arhiveaza conversatia"));a.addActionListener(x->archive.run());menu.add(a);
                JMenuItem ex=new JMenuItem(I18n.text("Exporta chat JSON…"));ex.addActionListener(x->export.run());menu.add(ex);
                JMenuItem d=new JMenuItem(I18n.text("Sterge conversatia"));d.addActionListener(x->delete.run());menu.add(d);menu.addSeparator();menu.add(statusMenu(list.getSelectedValue().state().projectStatus,statusChange));menu.show(list,e.getX(),e.getY());}
            public void mousePressed(MouseEvent e){popup(e);}public void mouseReleased(MouseEvent e){popup(e);}
        });
        list.setCellRenderer(new DefaultListCellRenderer(){
            @Override public Component getListCellRendererComponent(JList<?> l,Object value,int index,boolean selected,boolean focus) {
                Conversations.Saved s=(Conversations.Saved)value;
                JLabel label=(JLabel)super.getListCellRendererComponent(l,(s.state().unread?"\u2022 ":"")+(s.state().pinned?"* ":"")+s.title(),index,selected,focus);label.putClientProperty("html.disable",true);boolean live=runningFiles.contains(s.file().toAbsolutePath().normalize());label.setFont(label.getFont().deriveFont(live?Font.BOLD:Font.PLAIN));label.setIcon(ProjectStatus.icon(live?"in_progress":s.state().projectStatus));if(live&&!selected)label.setForeground(new Color(StatusColors.dark(l)?0x5ED5CB:0x087B72));label.setToolTipText(live?I18n.text("Chat in lucru — continua independent de selectie"):ProjectStatus.label(s.state().projectStatus));return label;
            }});
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(e->{if(!refreshing && !e.getValueIsAdjusting() && list.getSelectedValue()!=null)openSelection(list.getSelectedValue());});
        list.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){if(SwingUtilities.isLeftMouseButton(e)&&e.getClickCount()==2&&list.getSelectedValue()!=null){int row=list.locationToIndex(e.getPoint());if(row>=0&&list.getCellBounds(row,row).contains(e.getPoint()))scheduleOpen(list.getSelectedValue());}}});
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,0),"open-session");
        list.getActionMap().put("open-session",new AbstractAction(){public void actionPerformed(ActionEvent e){if(list.getSelectedValue()!=null){openTimer.stop();pendingOpen=null;open.accept(list.getSelectedValue());}}});
        fresh.addActionListener(e->newChat.run());rename.addActionListener(e->title.run());pin.addActionListener(e->togglePin.run());
        search.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){changed();}
            public void removeUpdate(DocumentEvent e){changed();}public void changedUpdate(DocumentEvent e){changed();}private void changed(){openTimer.stop();pendingOpen=null;filter();}});
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
        notifications.setToolTipText(I18n.text("Notificari: {0}",all.stream().filter(s->s.state().unread&&!s.state().archived).count()));notifications.getAccessibleContext().setAccessibleName(notifications.getToolTipText());heading.setText(I18n.text("Conversatii"));search.putClientProperty("JTextField.placeholderText",I18n.text("Cauta conversatii"));search.setToolTipText(I18n.text("Cauta conversatii"));
        search.getAccessibleContext().setAccessibleName(I18n.text("Cauta conversatii"));
        fresh.setText("+");fresh.setToolTipText(I18n.text("Conversatie noua"));
        resetFilters.setText(I18n.text("Reseteaza filtrele"));emptyHint.setText("<html><div style='width:180px'>"+I18n.text("Lista este filtrata; conversatiile raman salvate."));archived.setToolTipText(I18n.text("Afiseaza numai conversatiile arhivate"));
        remove.setText("−");remove.setToolTipText(I18n.text("Sterge conversatia"));archived.setText(I18n.text("Arhivate"));
        rename.setText(I18n.text("Titlu"));pin.setText(I18n.text("Fixeaza"));pin.setToolTipText(I18n.text("Fixeaza / desprinde"));
        limitLabel.setText("<html>"+I18n.text("Limita context (0 = necunoscut)"));limitLabel.setPreferredSize(new Dimension(220,32));
        autoCompact.setText(I18n.text("Compactare automata"));
        autoCompact.setToolTipText(I18n.text("Necesita o limita de context cunoscuta. Foloseste modelul curent si consuma tokeni."));
        thresholdLabel.setText(I18n.text("Prag context (%)"));
        autoThreshold.getAccessibleContext().setAccessibleName(I18n.text("Prag context (%)"));
        context.setToolTipText(I18n.text("Estimare pentru cererea urmatoare, nu consum facturat."));
    }
    void select(Path file) {
        refreshing=true;
        try{list.clearSelection();if(file!=null)for(int i=0;i<sessions.size();i++)if(sessions.get(i).file().equals(file)){list.setSelectedIndex(i);break;}}
        finally{refreshing=false;}
    }
    void refresh(List<Conversations.Saved> saved) {all=saved;filter();int count=(int)all.stream().filter(s->s.state().unread&&!s.state().archived).count();notifications.setText(count>0?Integer.toString(count):"");notifications.setIcon(new Icon(){public int getIconWidth(){return 15;}public int getIconHeight(){return 15;}public void paintIcon(Component c,Graphics g,int x,int y){Graphics2D a=(Graphics2D)g.create();a.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);a.setColor(c.getForeground());a.setStroke(new BasicStroke(1.4f));a.drawArc(x+4,y+2,7,9,0,180);a.drawLine(x+4,y+6,x+3,y+11);a.drawLine(x+11,y+6,x+12,y+11);a.drawLine(x+3,y+11,x+12,y+11);a.fillOval(x+6,y+12,3,2);a.dispose();}});notifications.setToolTipText(I18n.text("Notificari: {0}",count));if(count>0&&isShowing())notificationPulse.start();else {notificationPulse.stop();notifications.setForeground(UIManager.getColor("Label.foreground"));}}
    static JMenu statusMenu(String value,Consumer<String> change){JMenu menu=new JMenu(I18n.text("Status proiect"));for(String id:ProjectStatus.VALUES){JRadioButtonMenuItem choice=new JRadioButtonMenuItem(ProjectStatus.label(id),id.equals(value));choice.setIcon(ProjectStatus.icon(id));choice.addActionListener(e->change.accept(id));menu.add(choice);}return menu;}
    private void showNotifications(){JPopupMenu menu=new JPopupMenu();for(var s:all)if(s.state().unread&&!s.state().archived){JMenuItem item=new JMenuItem(s.title(),ProjectStatus.icon(s.state().projectStatus));item.putClientProperty("html.disable",true);item.addActionListener(e->selectAndOpen(s));menu.add(item);}if(menu.getComponentCount()==0)menu.add(new JMenuItem(I18n.text("Nicio notificare noua")));menu.show(notifications,0,notifications.getHeight());}
    private void selectAndOpen(Conversations.Saved saved){search.setText("");archived.setSelected(false);filter();boolean same=list.getSelectedValue()!=null&&list.getSelectedValue().file().equals(saved.file());list.setSelectedValue(saved,true);if(same)openSelection(saved);}
    @Override public void addNotify(){super.addNotify();if(!notifications.getText().isBlank())notificationPulse.start();}
    @Override public void removeNotify(){openTimer.stop();pendingOpen=null;notificationPulse.stop();super.removeNotify();}
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
        emptyPanel.setVisible(sessions.isEmpty()&&!all.isEmpty());
    }
    void meter(long system,long tools,long conversation,int maximum,String usage) {
        long total=system+tools+conversation;
        long percent=maximum>0 ? (long)Math.ceil(total*100.0/maximum):0;
        context.setValue((int)Math.min(100,percent));
        context.setString(maximum>0 ? I18n.text("Context: ~{0}% · {1}/{2}",ContextRing.percentage(total,maximum),total,maximum)
                : I18n.text("Context: ~{0} · limita necunoscuta",total));
        details.setText(I18n.text("Sistem: ~{0}\nTool-uri: ~{1}\nConversatie: ~{2}",system,tools,conversation)+"\n"+usage);
    }
}
