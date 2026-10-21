package localchat;

import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.function.*;
import javax.swing.*;
import javax.swing.text.DefaultEditorKit;

/** Explicit composer bindings survive look-and-feel changes; clipboard is read only on user action. */
final class ComposerClipboard {
    static void install(JTextArea input,Consumer<Exception> failure){install(input,()->Toolkit.getDefaultToolkit().getSystemClipboard(),failure);}
    static void install(JTextArea input,Supplier<Clipboard> clipboard,Consumer<Exception> failure){
        var history=ComposerEditing.install(input);
        Action paste=new AbstractAction(){public void actionPerformed(ActionEvent event){if(!input.isEditable()||!input.isEnabled())return;try{Transferable data=contents(clipboard.get());if(data!=null){boolean[] accepted={false};ComposerEditing.edit(input,()->accepted[0]=input.getTransferHandler().importData(input,data));if(!accepted[0])failure.accept(new IOException(I18n.text("Clipboard-ul nu contine text, fisiere sau o imagine acceptata.")));}}catch(Exception error){failure.accept(error);}}};
        Action copy=new AbstractAction(){public void actionPerformed(ActionEvent event){String text=input.getSelectedText();if(text==null||text.isEmpty())return;try{store(clipboard.get(),new StringSelection(text));}catch(Exception error){failure.accept(error);}}};
        Action cut=new AbstractAction(){public void actionPerformed(ActionEvent event){if(!input.isEditable()||!input.isEnabled())return;String text=input.getSelectedText();if(text==null||text.isEmpty())return;try{store(clipboard.get(),new StringSelection(text));ComposerEditing.edit(input,()->input.replaceSelection(""));}catch(Exception error){failure.accept(error);}}};
        input.getActionMap().put(DefaultEditorKit.pasteAction,paste);input.getActionMap().put(DefaultEditorKit.copyAction,copy);input.getActionMap().put(DefaultEditorKit.cutAction,cut);
        for(int[] binding:new int[][]{{KeyEvent.VK_V,InputEvent.CTRL_DOWN_MASK},{KeyEvent.VK_INSERT,InputEvent.SHIFT_DOWN_MASK}})input.getInputMap().put(KeyStroke.getKeyStroke(binding[0],binding[1]),DefaultEditorKit.pasteAction);
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_C,InputEvent.CTRL_DOWN_MASK),DefaultEditorKit.copyAction);input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_X,InputEvent.CTRL_DOWN_MASK),DefaultEditorKit.cutAction);
        input.getActionMap().put(DefaultEditorKit.selectAllAction,new AbstractAction(){public void actionPerformed(ActionEvent event){input.selectAll();}});
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_A,InputEvent.CTRL_DOWN_MASK),DefaultEditorKit.selectAllAction);
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_INSERT,InputEvent.CTRL_DOWN_MASK),DefaultEditorKit.copyAction);input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE,InputEvent.SHIFT_DOWN_MASK),DefaultEditorKit.cutAction);
        JPopupMenu menu=new JPopupMenu();JMenuItem cutItem=new JMenuItem(cut),copyItem=new JMenuItem(copy),pasteItem=new JMenuItem(paste),all=new JMenuItem();all.addActionListener(e->input.selectAll());menu.add(cutItem);menu.add(copyItem);menu.add(pasteItem);menu.addSeparator();menu.add(all);JMenuItem undoItem=new JMenuItem(history.undo),redoItem=new JMenuItem(history.redo);undoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z,InputEvent.CTRL_DOWN_MASK));redoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Y,InputEvent.CTRL_DOWN_MASK));menu.addSeparator();menu.add(undoItem);menu.add(redoItem);
        menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener(){public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e){history.refresh();undoItem.setText(I18n.text("Anuleaza editarea"));redoItem.setText(I18n.text("Refa editarea"));cutItem.setText(I18n.text("Decupeaza"));copyItem.setText(I18n.text("Copiaza"));pasteItem.setText(I18n.text("Lipeste"));all.setText(I18n.text("Selecteaza tot"));boolean selected=input.getSelectionEnd()>input.getSelectionStart();cutItem.setEnabled(selected&&input.isEditable());copyItem.setEnabled(selected);pasteItem.setEnabled(input.isEnabled()&&input.isEditable());all.setEnabled(!input.getText().isEmpty());}public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e){}public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e){}});input.setComponentPopupMenu(menu);
    }
    static void store(Clipboard clipboard,Transferable data){for(int attempt=0;;attempt++)try{clipboard.setContents(data,null);return;}catch(IllegalStateException locked){if(attempt>=4)throw locked;try{Thread.sleep(30);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw locked;}}}
    static Transferable contents(Clipboard clipboard){
        for(int attempt=0;;attempt++)try{return clipboard.getContents(null);}catch(IllegalStateException locked){if(attempt>=4)throw locked;try{Thread.sleep(30);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw locked;}}
    }
    static Path saveImage(Image image,Path folder)throws IOException{
        int width=image==null?-1:image.getWidth(null),height=image==null?-1:image.getHeight(null);
        if(width<=0||height<=0||(long)width*height>16000000L)throw new IOException(I18n.text("Imaginea din clipboard este prea mare sau indisponibila (maximum 16 milioane de pixeli)."));
        BufferedImage png=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);Graphics2D g=png.createGraphics();try{if(!g.drawImage(image,0,0,null))throw new IOException("Image not ready");}finally{g.dispose();}
        Files.createDirectories(folder);Path file=Files.createTempFile(folder,"clipboard-",".png");try{if(!javax.imageio.ImageIO.write(png,"png",file.toFile()))throw new IOException("PNG encoder unavailable");return file;}catch(IOException|RuntimeException error){Files.deleteIfExists(file);throw error;}
    }
}
