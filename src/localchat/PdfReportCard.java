package localchat;
import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.util.function.*;
final class PdfReportCard extends JPanel{
    final Path file;final JLabel title=new JLabel(),type=new JLabel();final JButton preview=new JButton(),menu=new JButton("...");
    private final BiConsumer<Path,Integer> action;private final IntSupplier available;
    PdfReportCard(Path path,BiConsumer<Path,Integer> action,IntSupplier available){
        super(new BorderLayout(10,0));this.file=path;this.action=action;this.available=available;
        JLabel icon=new JLabel(new UiIcon("pdf",29));add(icon,BorderLayout.WEST);JPanel labels=new JPanel(new GridLayout(2,1));title.setText(path.getFileName().toString());title.putClientProperty("html.disable",true);title.setToolTipText(path.toString());labels.add(title);labels.add(type);add(labels);
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT,4,0));actions.add(preview);actions.add(menu);add(actions,BorderLayout.EAST);preview.addActionListener(e->action.accept(file,5));
        menu.addActionListener(e->{JPopupMenu popup=new JPopupMenu();String[] labelsText={"Deschide extern","Salveaza o copie…","Copiaza fisierul","Copiaza calea"};int[] ids={0,2,3,4};for(int i=0;i<ids.length;i++){int id=ids[i];JMenuItem item=new JMenuItem(I18n.text(labelsText[i]));item.addActionListener(x->action.accept(file,id));popup.add(item);}popup.show(menu,0,menu.getHeight());});refresh();
    }
    public Dimension getPreferredSize(){return new Dimension(Math.max(250,Math.min(560,available.getAsInt()-28)),64);}
    public Dimension getMaximumSize(){return getPreferredSize();}
    void refresh(){
        preview.setText(I18n.text("Previzualizeaza"));preview.setToolTipText(I18n.text("Deschide PDF in panoul din dreapta"));menu.setToolTipText(I18n.text("Actiuni fisier"));
        String size="";try{size=" · "+Math.max(1,Files.size(file)/1024)+" KB";}catch(Exception ignored){}type.setText("PDF"+size);
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor")!=null?UIManager.getColor("Component.borderColor"):Color.GRAY,1,true),BorderFactory.createEmptyBorder(8,10,8,10)));
        setBackground(UIManager.getColor("Panel.background"));title.setForeground(UIManager.getColor("Label.foreground"));type.setForeground(UIManager.getColor("Label.disabledForeground"));title.setFont(title.getFont().deriveFont(Font.BOLD,13f));type.setFont(type.getFont().deriveFont(11f));
        preview.setMargin(new Insets(4,7,4,7));menu.setMargin(new Insets(4,6,4,6));revalidate();repaint();
    }
}
