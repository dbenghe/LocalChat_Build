package localchat;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Plain text from .docx and .xlsx files (zipped XML), e.g. attachments and import lists. */
final class OfficeText {
    private static final int MAX_ROWS = 5000;

    private OfficeText() {
    }

    static String docx(Path file) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Document doc = xml(zip, "word/document.xml");
            if (doc == null) throw new IOException("Nu e un .docx valid.");
            StringBuilder b = new StringBuilder();
            NodeList paragraphs = doc.getElementsByTagNameNS("*", "p");
            for (int i = 0; i < paragraphs.getLength(); i++) {
                appendRuns(paragraphs.item(i), b);
                b.append('\n');
            }
            return b.toString();
        }
    }

    private static void appendRuns(Node node, StringBuilder b) {
        for (Node n = node.getFirstChild(); n != null; n = n.getNextSibling()) {
            String name = n.getLocalName();
            if ("t".equals(name)) b.append(n.getTextContent());
            else if ("tab".equals(name)) b.append('\t');
            else if ("br".equals(name)) b.append('\n');
            else if (!"p".equals(name)) appendRuns(n, b); // nested paragraphs are visited on their own
        }
    }

    static String xlsx(Path file) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            List<String> shared = new ArrayList<>();
            Document strings = xml(zip, "xl/sharedStrings.xml");
            if (strings != null) {
                NodeList items = strings.getElementsByTagNameNS("*", "si");
                for (int i = 0; i < items.getLength(); i++) shared.add(textOf(items.item(i), "t"));
            }
            Map<String, String> targets = new LinkedHashMap<>();
            Document rels = xml(zip, "xl/_rels/workbook.xml.rels");
            if (rels != null) {
                NodeList list = rels.getElementsByTagNameNS("*", "Relationship");
                for (int i = 0; i < list.getLength(); i++) {
                    Element r = (Element) list.item(i);
                    String target = r.getAttribute("Target");
                    targets.put(r.getAttribute("Id"), target.startsWith("/") ? target.substring(1) : "xl/" + target);
                }
            }
            Document workbook = xml(zip, "xl/workbook.xml");
            if (workbook == null) throw new IOException("Nu e un .xlsx valid.");
            StringBuilder b = new StringBuilder();
            NodeList sheets = workbook.getElementsByTagNameNS("*", "sheet");
            for (int s = 0; s < sheets.getLength(); s++) {
                Element sheet = (Element) sheets.item(s);
                String rid = sheet.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id");
                String path = targets.getOrDefault(rid, "xl/worksheets/sheet" + (s + 1) + ".xml");
                b.append("## Foaia: ").append(sheet.getAttribute("name")).append('\n');
                appendSheet(xml(zip, path), shared, b);
                b.append('\n');
            }
            return b.toString();
        }
    }

    private static void appendSheet(Document doc, List<String> shared, StringBuilder b) {
        if (doc == null) return;
        NodeList rows = doc.getElementsByTagNameNS("*", "row");
        for (int r = 0; r < rows.getLength() && r < MAX_ROWS; r++) {
            Element row = (Element) rows.item(r);
            TreeMap<Integer, String> cells = new TreeMap<>();
            NodeList cs = row.getElementsByTagNameNS("*", "c");
            for (int i = 0; i < cs.getLength(); i++) {
                Element c = (Element) cs.item(i);
                String type = c.getAttribute("t");
                String value;
                if ("inlineStr".equals(type)) {
                    value = textOf(c, "t");
                } else {
                    value = textOf(c, "v");
                    if ("s".equals(type) && !value.isEmpty()) {
                        int index = Integer.parseInt(value.trim());
                        value = index < shared.size() ? shared.get(index) : "";
                    } else if ("b".equals(type)) {
                        value = "1".equals(value) ? "TRUE" : "FALSE";
                    }
                }
                if (!value.isEmpty()) cells.put(column(c.getAttribute("r"), i), value.replace('\n', ' ').replace('\t', ' '));
            }
            if (cells.isEmpty()) continue;
            String rowNumber = row.getAttribute("r");
            b.append(rowNumber.isEmpty() ? String.valueOf(r + 1) : rowNumber).append(':');
            int next = 0;
            for (Map.Entry<Integer, String> e : cells.entrySet()) {
                while (next < e.getKey()) {
                    b.append('\t');
                    next++;
                }
                b.append('\t').append(e.getValue());
                next++;
            }
            b.append('\n');
        }
        if (rows.getLength() > MAX_ROWS) b.append("… încă ").append(rows.getLength() - MAX_ROWS).append(" rânduri neafișate\n");
    }

    /** Zero-based column of a cell reference like "C12"; falls back to the cell's position. */
    private static int column(String ref, int fallback) {
        int col = 0;
        int i = 0;
        while (i < ref.length() && Character.isLetter(ref.charAt(i))) {
            col = col * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
            i++;
        }
        return i == 0 ? fallback : col - 1;
    }

    private static String textOf(Node node, String localName) {
        StringBuilder b = new StringBuilder();
        NodeList list = ((Element) node).getElementsByTagNameNS("*", localName);
        for (int i = 0; i < list.getLength(); i++) b.append(list.item(i).getTextContent());
        return b.toString();
    }

    private static Document xml(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) return null;
        try (InputStream in = zip.getInputStream(entry)) {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            return f.newDocumentBuilder().parse(in);
        } catch (ParserConfigurationException | org.xml.sax.SAXException e) {
            throw new IOException("XML invalid în " + name + ": " + e.getMessage());
        }
    }
}
