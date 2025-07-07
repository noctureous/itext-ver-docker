package cerg.pdfanalyzer.dto;

import java.util.List;
import java.util.Map;

public class PdfAnalysisResult {
    private String fileName;
    private Map<Integer, List<FontInfo>> fontInfo;
    private Map<Integer, MarginInfo> margins;
    private Map<Integer, PageInfo> pageSizes;
    private Map<Integer, String> pageText;
    private Map<Integer, List<String>> imageText;
    private Map<Integer, SpacingInfo> spacing;
    private boolean pdfACompliant;
    private boolean pdfXCompliant;

    // Getters and Setters
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public Map<Integer, List<FontInfo>> getFontInfo() {
        return fontInfo;
    }

    public void setFontInfo(Map<Integer, List<FontInfo>> fontInfo) {
        this.fontInfo = fontInfo;
    }

    public Map<Integer, MarginInfo> getMargins() { return margins; }
    public void setMargins(Map<Integer, MarginInfo> margins) { this.margins = margins; }

    public Map<Integer, PageInfo> getPageSizes() { return pageSizes; }
    public void setPageSizes(Map<Integer, PageInfo> pageSizes) { this.pageSizes = pageSizes; }

    public Map<Integer, String> getPageText() { return pageText; }
    public void setPageText(Map<Integer, String> pageText) { this.pageText = pageText; }

    public Map<Integer, List<String>> getImageText() { return imageText; }
    public void setImageText(Map<Integer, List<String>> imageText) { this.imageText = imageText; }

    public Map<Integer, SpacingInfo> getSpacing() { return spacing; }
    public void setSpacing(Map<Integer, SpacingInfo> spacing) { this.spacing = spacing; }

    public boolean isPdfACompliant() { return pdfACompliant; }
    public void setPdfACompliant(boolean pdfACompliant) { this.pdfACompliant = pdfACompliant; }

    public boolean isPdfXCompliant() { return pdfXCompliant; }
    public void setPdfXCompliant(boolean pdfXCompliant) { this.pdfXCompliant = pdfXCompliant; }
}