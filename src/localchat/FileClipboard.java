package localchat;
import java.awt.datatransfer.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
/** Explorer-compatible copy, plus a plain path for text editors. */
final class FileClipboard implements Transferable {
    private final Path path;
    FileClipboard(Path path){this.path=path.toAbsolutePath();}
    public DataFlavor[] getTransferDataFlavors(){return new DataFlavor[]{DataFlavor.javaFileListFlavor,DataFlavor.stringFlavor};}
    public boolean isDataFlavorSupported(DataFlavor flavor){return flavor.equals(DataFlavor.javaFileListFlavor)||flavor.equals(DataFlavor.stringFlavor);}
    public Object getTransferData(DataFlavor flavor)throws UnsupportedFlavorException{
        if(flavor.equals(DataFlavor.javaFileListFlavor))return List.of(path.toFile());
        if(flavor.equals(DataFlavor.stringFlavor))return path.toString();
        throw new UnsupportedFlavorException(flavor);
    }
}
