package cerg.pdfanalyzer.dto;

import java.util.List;

public class SpacingInfo {
    private float lineSpacing;
    private String spacingType; // "Single", "1.5x", "Double", "Custom"
    private float spacingRatio; // Ratio compared to font size
    private boolean isSingleLineSpacing;
    private List<ParagraphSpacingDetail> paragraphDetails; // NEW: Details for each paragraph

    // Getters and Setters
    public float getLineSpacing() { return lineSpacing; }
    public void setLineSpacing(float lineSpacing) { this.lineSpacing = lineSpacing; }

    public String getSpacingType() { return spacingType; }
    public void setSpacingType(String spacingType) { this.spacingType = spacingType; }

    public float getSpacingRatio() { return spacingRatio; }
    public void setSpacingRatio(float spacingRatio) { this.spacingRatio = spacingRatio; }

    public boolean isSingleLineSpacing() { return isSingleLineSpacing; }
    public void setSingleLineSpacing(boolean singleLineSpacing) { this.isSingleLineSpacing = singleLineSpacing; }

    public List<ParagraphSpacingDetail> getParagraphDetails() { return paragraphDetails; }
    public void setParagraphDetails(List<ParagraphSpacingDetail> paragraphDetails) { this.paragraphDetails = paragraphDetails; }

    // Inner class for paragraph details
    public static class ParagraphSpacingDetail {
        private int paragraphNumber;
        private float lineGap;
        private float spacingRatio;
        private boolean isAcceptable;
        private String sampleText;
        private int lineCount;

        // Getters and Setters
        public int getParagraphNumber() { return paragraphNumber; }
        public void setParagraphNumber(int paragraphNumber) { this.paragraphNumber = paragraphNumber; }

        public float getLineGap() { return lineGap; }
        public void setLineGap(float lineGap) { this.lineGap = lineGap; }

        public float getSpacingRatio() { return spacingRatio; }
        public void setSpacingRatio(float spacingRatio) { this.spacingRatio = spacingRatio; }

        public boolean isAcceptable() { return isAcceptable; }
        public void setAcceptable(boolean acceptable) { this.isAcceptable = acceptable; }

        public String getSampleText() { return sampleText; }
        public void setSampleText(String sampleText) { this.sampleText = sampleText; }

        public int getLineCount() { return lineCount; }
        public void setLineCount(int lineCount) { this.lineCount = lineCount; }
    }
}