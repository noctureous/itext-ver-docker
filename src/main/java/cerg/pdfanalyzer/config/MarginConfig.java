package cerg.pdfanalyzer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "pdf.margin")
public class MarginConfig {
    
    private float minimumMargin = 2.5f; // Default minimum margin in cm
    private float leftMinimum = 2.5f;
    private float topMinimum = 2.5f;
    private float rightMinimum = 2.5f;
    private float bottomMinimum = 2.5f;
    
    // Getters and Setters
    public float getMinimumMargin() {
        return minimumMargin;
    }
    
    public void setMinimumMargin(float minimumMargin) {
        this.minimumMargin = minimumMargin;
    }
    
    public float getLeftMinimum() {
        return leftMinimum;
    }
    
    public void setLeftMinimum(float leftMinimum) {
        this.leftMinimum = leftMinimum;
    }
    
    public float getTopMinimum() {
        return topMinimum;
    }
    
    public void setTopMinimum(float topMinimum) {
        this.topMinimum = topMinimum;
    }
    
    public float getRightMinimum() {
        return rightMinimum;
    }
    
    public void setRightMinimum(float rightMinimum) {
        this.rightMinimum = rightMinimum;
    }
    
    public float getBottomMinimum() {
        return bottomMinimum;
    }
    
    public void setBottomMinimum(float bottomMinimum) {
        this.bottomMinimum = bottomMinimum;
    }
    
    /**
     * Get the minimum margin for a specific side, falling back to general minimum if not specified
     */
    public float getMinimumForSide(String side) {
        if (side == null) return minimumMargin;
        
        switch (side.toLowerCase()) {
            case "left": return leftMinimum;
            case "top": return topMinimum;
            case "right": return rightMinimum;
            case "bottom": return bottomMinimum;
            default: return minimumMargin;
        }
    }
}