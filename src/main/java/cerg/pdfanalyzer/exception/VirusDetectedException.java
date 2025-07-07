package cerg.pdfanalyzer.exception;

public class VirusDetectedException extends RuntimeException {
    private final String threatName;
    private final String scanEngine;
    
    public VirusDetectedException(String threatName, String scanEngine) {
        super("Virus detected: " + threatName + " (detected by: " + scanEngine + ")");
        this.threatName = threatName;
        this.scanEngine = scanEngine;
    }
    
    public String getThreatName() {
        return threatName;
    }
    
    public String getScanEngine() {
        return scanEngine;
    }
}