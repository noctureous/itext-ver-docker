package cerg.pdfanalyzer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "pdf.spacing")
public class SpacingConfig {
    
    private float minimumLineGap = 12.0f;
    private float singleLineMin = 12.0f;
    private float singleLineMax = 18.0f;
    private float doubleLineMin = 24.0f;
    private float acceptableGapThreshold = 12.0f;
    
    // Getters and Setters
    public float getMinimumLineGap() {
        return minimumLineGap;
    }
    
    public void setMinimumLineGap(float minimumLineGap) {
        this.minimumLineGap = minimumLineGap;
    }
    
    public float getSingleLineMin() {
        return singleLineMin;
    }
    
    public void setSingleLineMin(float singleLineMin) {
        this.singleLineMin = singleLineMin;
    }
    
    public float getSingleLineMax() {
        return singleLineMax;
    }
    
    public void setSingleLineMax(float singleLineMax) {
        this.singleLineMax = singleLineMax;
    }
    
    public float getDoubleLineMin() {
        return doubleLineMin;
    }
    
    public void setDoubleLineMin(float doubleLineMin) {
        this.doubleLineMin = doubleLineMin;
    }
    
    public float getAcceptableGapThreshold() {
        return acceptableGapThreshold;
    }
    
    public void setAcceptableGapThreshold(float acceptableGapThreshold) {
        this.acceptableGapThreshold = acceptableGapThreshold;
    }
}