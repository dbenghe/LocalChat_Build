package localchat;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.function.*;

/** A single lazy-rendered page, independent of inference and chat context. */
final class PdfPreview extends JPanel {
    final JLabel name=new JLabel(),status=new JLabel(" "),image=new JLabel();
    final JButton previous=new JButton("<"),next=new JButton(">"),close=new JButton("x"),save=new JButton(),external=new JButton(),copy=new JButton();
    final JSpinner page=new JSpinner(new SpinnerNumberModel(1,1,1,1));
    final JTabbedPane tabs=new JTabbedPane(JTabbedPane.TOP,JTabbedPane.SCROLL_TAB_LAYOUT);
    final JButton addTab=new JButton("+"),expand=new JButton();
    private final java.util.List<Path> openFiles=new java.util.ArrayList<>();
    private final java.util.Map<Path,int[]> positions=new java.util.HashMap<>();
    private final Runnable onEmpty;private boolean expanded;
    final JLabel total=new JLabel("/ 0");final JComboBox<String> zoom=new JComboBox<>();
    final JProgressBar activity=new JProgressBar();final JScrollPane scroll;
    private final Supplier<Path> root;private final Supplier<String> python;private final java.util.function.BiConsumer<Path,Integer> action;
    private Path file;private int pages,generation;private boolean changing,loading,resizedWhileLoading;
    private PdfRenderer renderer;private SwingWorker<?,?> worker;
    private final javax.swing.Timer resize=new javax.swing.Timer(220,e->{if(file!=null&&zoom.getSelectedIndex()==0)load(false);});
    PdfPreview(Supplier<Path> root,Supplier<String> python,BiConsumer<Path,Integer> action,Runnable onClose,Runnable onAdd,Runnable onExpand,Runnable onEmpty){
        super(new BorderLayout(4,4));this.root=root;this.python=python;this.action=action;this.onEmpty=onEmpty;
        setMinimumSize(new Dimension(260,140));setPreferredSize(new Dimension(380,500));setBorder(BorderFactory.createEmptyBorder(6,6,6,6));
        JPanel heading=new JPanel(new BorderLayout(3,0));name.putClientProperty("html.disable",true);tabs.setPreferredSize(new Dimension(170,34));heading.add(tabs);JPanel tabActions=new JPanel(new FlowLayout(FlowLayout.RIGHT,2,0));for(JButton button:new JButton[]{addTab,expand,close}){button.setPreferredSize(new Dimension(27,27));button.setMargin(new Insets(2,2,2,2));tabActions.add(button);}expand.setIcon(new UiIcon("expand",16));close.setText("");close.setIcon(new UiIcon("panels",17));heading.add(tabActions,BorderLayout.EAST);
        tabs.addChangeListener(e->{if(!changing&&tabs.getSelectedIndex()>=0){remember();activate(openFiles.get(tabs.getSelectedIndex()));}});addTab.addActionListener(e->onAdd.run());expand.addActionListener(e->onExpand.run());
        JPanel navigation=new JPanel(new FlowLayout(FlowLayout.LEFT,4,2));page.setPreferredSize(new Dimension(58,26));zoom.setPreferredSize(new Dimension(126,26));navigation.add(previous);navigation.add(page);navigation.add(total);navigation.add(next);
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT,4,2));actions.add(zoom);actions.add(save);actions.add(external);actions.add(copy);
        save.setIcon(new UiIcon("download",15));external.setIcon(new UiIcon("external",15));copy.setIcon(new UiIcon("copy",15));
        JPanel top=new JPanel();top.setLayout(new BoxLayout(top,BoxLayout.Y_AXIS));top.add(heading);top.add(navigation);top.add(actions);activity.setIndeterminate(true);activity.setPreferredSize(new Dimension(100,3));top.add(activity);add(top,BorderLayout.NORTH);
        image.setHorizontalAlignment(JLabel.CENTER);image.setVerticalAlignment(JLabel.TOP);scroll=new JScrollPane(image);scroll.getVerticalScrollBar().setUnitIncrement(24);add(scroll);
        add(status,BorderLayout.SOUTH);resize.setRepeats(false);
        scroll.getViewport().addComponentListener(new java.awt.event.ComponentAdapter(){public void componentResized(java.awt.event.ComponentEvent e){if(file!=null){if(loading)resizedWhileLoading=true;else resize.restart();}}});
        previous.addActionListener(e->{if((Integer)page.getValue()>1)page.setValue((Integer)page.getValue()-1);});next.addActionListener(e->{if((Integer)page.getValue()<pages)page.setValue((Integer)page.getValue()+1);});
        page.addChangeListener(e->{if(!changing&&file!=null)load(false);});zoom.addActionListener(e->{if(!changing&&file!=null)load(false);});close.addActionListener(e->onClose.run());
        save.addActionListener(e->{if(file!=null)action.accept(file,2);});external.addActionListener(e->{if(file!=null)action.accept(file,0);});copy.addActionListener(e->{if(file!=null)action.accept(file,3);});
        translate();clear();
    }
    Path file(){return file;}boolean loading(){return loading;}int pages(){return pages;}
    void translate(){
        changing=true;int index=Math.max(0,zoom.getSelectedIndex());zoom.removeAllItems();for(String label:new String[]{I18n.text("Potriveste latimea"),"50%","75%","100%","125%","150%","200%"})zoom.addItem(label);zoom.setSelectedIndex(Math.min(index,zoom.getItemCount()-1));changing=false;for(int i=0;i<tabs.getTabCount();i++)tabs.setTabComponentAt(i,tabHeader(openFiles.get(i)));
        previous.setToolTipText(I18n.text("Pagina precedenta"));next.setToolTipText(I18n.text("Pagina urmatoare"));close.setToolTipText(I18n.text("Ascunde panoul PDF")+" (Ctrl+Shift+B)");addTab.setToolTipText(I18n.text("Deschide alt PDF"));expand.setToolTipText(I18n.text(expanded?"Restabileste panoul":"Mareste panoul PDF"));
        for(JButton button:new JButton[]{save,external,copy}){button.setPreferredSize(new Dimension(32,28));button.setMargin(new Insets(3,3,3,3));}
        save.setToolTipText(I18n.text("Salveaza o copie…"));external.setToolTipText(I18n.text("Deschide extern"));copy.setToolTipText(I18n.text("Copiaza fisierul"));for(JButton button:new JButton[]{save,external,copy})button.getAccessibleContext().setAccessibleName(button.getToolTipText());page.getAccessibleContext().setAccessibleName(I18n.text("Pagina PDF"));zoom.getAccessibleContext().setAccessibleName(I18n.text("Zoom PDF"));name.getAccessibleContext().setAccessibleName(I18n.text("Previzualizare PDF"));
        if(file!=null){name.setText(file.getFileName().toString());if(loading)status.setText(I18n.text("Se incarca previzualizarea PDF…"));else if(pages>0)status.setText(I18n.text("Pagina {0} din {1}",page.getValue(),pages));else status.setText(I18n.text("Previzualizarea PDF nu este disponibila. Poti deschide fisierul extern."));}
    }
    int tabCount(){return openFiles.size();}
    void expanded(boolean value){expanded=value;expand.setIcon(new UiIcon(value?"restore":"expand",16));expand.setToolTipText(I18n.text(value?"Restabileste panoul":"Mareste panoul PDF"));}
    void suspend(){stopWork();}
    void resume(){if(file!=null&&image.getIcon()==null)load(pages<1);}
    private void remember(){if(file!=null&&openFiles.contains(file))positions.put(file,new int[]{(Integer)page.getValue(),Math.max(0,zoom.getSelectedIndex())});}
    void open(Path path){
        path=path.toAbsolutePath().normalize();int index=openFiles.indexOf(path);if(file!=null&&file.equals(path)){resume();return;}remember();changing=true;
        if(index<0){openFiles.add(path);index=openFiles.size()-1;tabs.addTab(path.getFileName().toString(),new JPanel());tabs.setToolTipTextAt(index,path.toString());tabs.setTabComponentAt(index,tabHeader(path));}
        tabs.setSelectedIndex(index);changing=false;activate(path);
    }
    private JComponent tabHeader(Path path){
        JPanel header=new JPanel(new FlowLayout(FlowLayout.LEFT,3,0));header.setOpaque(false);JLabel label=new JLabel(path.getFileName().toString(),new UiIcon("pdf",16),JLabel.LEFT);label.putClientProperty("html.disable",true);label.setPreferredSize(new Dimension(110,23));label.setToolTipText(path.toString());JButton remove=new JButton("x");remove.setMargin(new Insets(0,3,0,3));remove.setPreferredSize(new Dimension(19,21));remove.setToolTipText(I18n.text("Inchide tabul PDF"));remove.addActionListener(e->closeTab(path));header.add(label);header.add(remove);return header;
    }
    void closeTab(Path path){
        int index=openFiles.indexOf(path);if(index<0)return;boolean selected=path.equals(file);remember();changing=true;tabs.removeTabAt(index);openFiles.remove(index);positions.remove(path);changing=false;
        if(openFiles.isEmpty()){clear();onEmpty.run();}else if(selected)activate(openFiles.get(Math.max(0,tabs.getSelectedIndex())));
    }
    private void activate(Path path){
        stopWork();file=path;pages=0;image.setIcon(null);image.setText("");name.setText(path.getFileName().toString());name.setToolTipText(path.toString());int[] view=positions.getOrDefault(path,new int[]{1,0});
        changing=true;page.setModel(new SpinnerNumberModel(view[0],1,Math.max(1,view[0]),1));zoom.setSelectedIndex(Math.min(view[1],zoom.getItemCount()-1));changing=false;load(true);
    }
    private void stopWork(){generation++;resize.stop();if(worker!=null)worker.cancel(true);if(renderer!=null)renderer.cancel();worker=null;renderer=null;activity.setVisible(false);loading=false;resizedWhileLoading=false;}
    void clear(){
        stopWork();changing=true;tabs.removeAll();openFiles.clear();positions.clear();file=null;pages=0;image.setIcon(null);image.setText("");status.setText(" ");name.setText("");total.setText("/ 0");page.setModel(new SpinnerNumberModel(1,1,1,1));changing=false;previous.setEnabled(false);next.setEnabled(false);
    }
    private void load(boolean metadata){
        if(file==null)return;
        if(worker!=null)worker.cancel(true);if(renderer!=null)renderer.cancel();int ticket=++generation;Path selected=file;boolean readMetadata=metadata||pages<1;
        PdfRenderer backend=new PdfRenderer();renderer=backend;int number=(Integer)page.getValue();int zoomValue=zoom.getSelectedIndex()==0?0:Integer.parseInt(zoom.getSelectedItem().toString().replace("%",""));int width=Math.max(240,scroll.getViewport().getWidth()-20);Path selectedRoot=root.get();String runtime=python.get();
        loading=true;activity.setVisible(true);status.setText(I18n.text("Se incarca previzualizarea PDF…"));
        worker=new SwingWorker<BufferedImage,Void>(){
            int count=pages,renderedPage=number;
            protected BufferedImage doInBackground()throws Exception{
                if(!ReportFiles.allowed(selected,selectedRoot)||!selected.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf"))throw new java.io.IOException("Unavailable file");
                if(readMetadata)count=backend.info(selected,runtime).pages();if(isCancelled())return null;
                renderedPage=Math.min(number,count);return backend.render(selected,runtime,renderedPage,width,zoomValue);
            }
            protected void done(){
                if(ticket!=generation||!selected.equals(file))return;
                loading=false;activity.setVisible(false);
                try{BufferedImage result=get();if(result==null)return;pages=count;changing=true;page.setModel(new SpinnerNumberModel(renderedPage,1,Math.max(1,pages),1));changing=false;total.setText("/ "+pages);image.setText("");image.setIcon(new ImageIcon(result));status.setText(I18n.text("Pagina {0} din {1}",renderedPage,pages));previous.setEnabled(renderedPage>1);next.setEnabled(renderedPage<pages);scroll.getViewport().setViewPosition(new Point(0,0));if(resizedWhileLoading&&zoom.getSelectedIndex()==0){resizedWhileLoading=false;resize.restart();}}
                catch(Exception error){image.setIcon(null);status.setText(I18n.text("Previzualizarea PDF nu este disponibila. Poti deschide fisierul extern."));}
            }
        };worker.execute();
    }
}
