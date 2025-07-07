package cerg.pdfanalyzer.dto;

public class FontInfo {
    private String fontName;
    private float fontSize;
    private boolean embedded;
    private String text; // <-- Add this field

    // Getters and Setters
    public String getFontName() {
        return fontName;
    }

    public void setFontName(String fontName) {
        this.fontName = fontName;
    }

    public float getFontSize() {
        return fontSize;
    }

    public void setFontSize(float fontSize) {
        this.fontSize = fontSize;
    }

    public boolean isEmbedded() {
        return embedded;
    }

    public void setEmbedded(boolean embedded) {
        this.embedded = embedded;
    }

    public String getText() { // <-- Getter for text
        return text;
    }

    public void setText(String text) { // <-- Setter for text
        this.text = text;
    }
}
