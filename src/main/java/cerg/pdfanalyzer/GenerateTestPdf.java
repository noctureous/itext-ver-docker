package cerg.pdfanalyzer;

import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import java.io.File;

public class GenerateTestPdf {
    public static void main(String[] args) throws Exception {
        File file = new File("src/test/resources/test.pdf");
        file.getParentFile().mkdirs();
        PdfDocument pdfDoc = new PdfDocument(new PdfWriter(file));
        Document doc = new Document(pdfDoc);
        doc.add(new Paragraph("Hello World").setFont(PdfFontFactory.createFont("Helvetica")));
        doc.close();
    }
}