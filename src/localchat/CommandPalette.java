package localchat;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.function.Consumer;

/** A bounded, scrollable slash menu; input keeps focus for typing and arrow navigation. */
final class CommandPalette extends JPopupMenu {
    final DefaultListModel<String[]> choices=new DefaultListModel<>();
    final JList<String[]> list=new JList<>(choices);
    final JScrollPane scroll=new JScrollPane(list);
    private Consumer<String> select;private Color background,foreground,selected;
    CommandPalette(){
        setFocusable(false);list.setFocusable(false);list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);list.setFixedCellHeight(34);
        list.setCellRenderer((owner,value,index,active,focus)->{JLabel label=new JLabel(value[0]+"   "+I18n.text(value[1]));label.putClientProperty("html.disable",true);label.setOpaque(true);label.setFont(label.getFont().deriveFont(13f));label.setBorder(BorderFactory.createEmptyBorder(4,10,4,10));label.setBackground(active?selected:background);label.setForeground(foreground);label.setToolTipText(label.getText());return label;});
        scroll.setBorder(BorderFactory.createEmptyBorder());scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);add(scroll);
        list.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){if(SwingUtilities.isLeftMouseButton(e)){int i=list.locationToIndex(e.getPoint());if(i>=0&&list.getCellBounds(i,i).contains(e.getPoint())){list.setSelectedIndex(i);accept();}}}});
        theme(true);
    }
    void theme(boolean dark){
        SwingUtilities.updateComponentTreeUI(this);background=new Color(dark?0x1B1F26:0xFFFFFF);foreground=new Color(dark?0xEDF1F7:0x202733);selected=new Color(dark?0x354C70:0xDBE7FF);
        setBackground(background);setForeground(foreground);setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(dark?0x414B5D:0xB7C2D2)),BorderFactory.createEmptyBorder(4,4,4,4)));
        list.setBackground(background);list.setForeground(foreground);list.setSelectionBackground(selected);list.setSelectionForeground(foreground);scroll.setBackground(background);scroll.getViewport().setBackground(background);scroll.getVerticalScrollBar().setBackground(background);repaint();
    }
    void filter(String prefix,String[][] commands,Consumer<String> action,int width){
        select=action;choices.clear();for(String[] command:commands)if(command[0].startsWith(prefix.toLowerCase(java.util.Locale.ROOT)))choices.addElement(command);
        if(!choices.isEmpty())list.setSelectedIndex(0);scroll.getViewport().setViewPosition(new Point(0,0));scroll.setPreferredSize(new Dimension(Math.max(320,Math.min(660,width)),Math.min(360,choices.size()*34)));revalidate();
    }
    void move(int delta){if(choices.isEmpty())return;int index=Math.floorMod(list.getSelectedIndex()+delta,choices.size());list.setSelectedIndex(index);list.ensureIndexIsVisible(index);}
    void accept(){String[] value=list.getSelectedValue();if(value==null)return;setVisible(false);if(select!=null)select.accept(value[0]);}
}
