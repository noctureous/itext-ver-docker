package cerg.pdfanalyzer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class AntivirusService {
    
    private static final Logger logger = LoggerFactory.getLogger(AntivirusService.class);
    
    @Value("${antivirus.enabled:true}")
    private boolean antivirusEnabled;
    
    @Value("${antivirus.clamav.path:clamdscan}")
    private String clamavPath;
    
    @Value("${antivirus.timeout:30}")
    private int scanTimeoutSeconds;
    
    @Value("${antivirus.max-file-size:100}")
    private long maxFileSizeMB;
    
    @Value("${antivirus.temp-directory:}")
    private String tempDirectory;

    // Known malicious file signatures (basic implementation)
    private static final List<byte[]> KNOWN_VIRUS_SIGNATURES = Arrays.asList(
        // EICAR test string signature
        "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*".getBytes(),
        // Common malware patterns (simplified examples)
        new byte[]{(byte)0x4D, (byte)0x5A, (byte)0x90, (byte)0x00}, // PE header
        new byte[]{(byte)0x50, (byte)0x4B, (byte)0x03, (byte)0x04}  // ZIP header (could contain malware)
    );
    
    public static class ScanResult {
        private final boolean clean;
        private final String threat;
        private final String scanEngine;
        
        public ScanResult(boolean clean, String threat, String scanEngine) {
            this.clean = clean;
            this.threat = threat;
            this.scanEngine = scanEngine;
        }
        
        public boolean isClean() { return clean; }
        public String getThreat() { return threat; }
        public String getScanEngine() { return scanEngine; }
    }
    
    /**
     * Scans a MultipartFile for viruses and malware
     */
    public ScanResult scanFile(MultipartFile file) throws IOException {
        if (!antivirusEnabled) {
            logger.debug("Antivirus scanning is disabled");
            return new ScanResult(true, null, "disabled");
        }
        
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Cannot scan empty file");
        }
        
        // Check file size
        if (file.getSize() > maxFileSizeMB * 1024 * 1024) {
            logger.warn("File {} exceeds maximum scan size of {}MB", file.getOriginalFilename(), maxFileSizeMB);
            throw new IllegalArgumentException("File too large for scanning");
        }
        
        logger.info("Starting antivirus scan for file: {}", file.getOriginalFilename());
        
        // Create temporary file for scanning - sanitize filename for temp file creation
        String sanitizedFilename = sanitizeFilenameForTempFile(file.getOriginalFilename());
        
        Path tempFile;
        if (tempDirectory != null && !tempDirectory.trim().isEmpty()) {
            // Use configured temp directory
            Path tempDir = Path.of(tempDirectory);
            // Ensure directory exists
            Files.createDirectories(tempDir);
            tempFile = Files.createTempFile(tempDir, "av-scan-", "-" + sanitizedFilename);
        } else {
            // Fall back to system temp directory
            tempFile = Files.createTempFile("av-scan-", "-" + sanitizedFilename);
        }
        
        try {
            // Replaced file.transferTo() with a stream copy to avoid consuming the
            // MultipartFile. This ensures the file can be read by subsequent processes.
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
            return scanFile(tempFile.toFile());
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
    
    /**
     * Sanitizes a filename to be safe for use in temporary file creation
     */
    private String sanitizeFilenameForTempFile(String originalFilename) {
        if (originalFilename == null) {
            return "unknown";
        }
        
        // Replace problematic characters with underscores and limit length
        String sanitized = originalFilename
            .replaceAll("[^a-zA-Z0-9._-]", "_")
            .replaceAll("_{2,}", "_"); // Replace multiple underscores with single
        
        // Ensure it's not too long (temp file suffixes have length limits)
        if (sanitized.length() > 50) {
            String extension = "";
            int lastDot = sanitized.lastIndexOf('.');
            if (lastDot > 0 && lastDot < sanitized.length() - 1) {
                extension = sanitized.substring(lastDot);
                sanitized = sanitized.substring(0, lastDot);
            }
            
            // Keep first 40 chars + extension
            sanitized = sanitized.substring(0, Math.min(40, sanitized.length())) + extension;
        }
        
        return sanitized;
    }
    
    /**
     * Scans a File for viruses and malware
     */
    public ScanResult scanFile(File file) throws IOException {
        if (!antivirusEnabled) {
            return new ScanResult(true, null, "disabled");
        }
        
        logger.debug("Scanning file: {}", file.getAbsolutePath());
        
        // First, try signature-based detection
        ScanResult signatureResult = performSignatureScan(file);
        if (!signatureResult.isClean()) {
            return signatureResult;
        }
        
        // Then try ClamAV if available
        ScanResult clamavResult = performClamAVScan(file);
        if (clamavResult != null) {
            return clamavResult;
        }
        
        // Fallback to basic heuristics
        return performHeuristicScan(file);
    }
    
    /**
     * Performs signature-based scanning
     */
    private ScanResult performSignatureScan(File file) throws IOException {
        logger.debug("Performing signature scan on: {}", file.getName());
        
        byte[] fileBytes = Files.readAllBytes(file.toPath());
        
        for (byte[] signature : KNOWN_VIRUS_SIGNATURES) {
            if (containsSignature(fileBytes, signature)) {
                String threat = "SIGNATURE_MATCH";
                if (Arrays.equals(signature, "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*".getBytes())) {
                    threat = "EICAR-Test-File";
                }
                logger.warn("Virus signature detected in {}: {}", file.getName(), threat);
                return new ScanResult(false, threat, "signature");
            }
        }
        
        return new ScanResult(true, null, "signature");
    }
    
    /**
     * Performs ClamAV scanning if available
     */
    private ScanResult performClamAVScan(File file) {
        try {
            logger.debug("Attempting ClamAV scan on: {}", file.getName());
            
            ProcessBuilder pb = new ProcessBuilder(clamavPath, "--no-summary", file.getAbsolutePath());
            Process process = pb.start();
            
            boolean finished = process.waitFor(scanTimeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                logger.warn("ClamAV scan timed out for file: {}", file.getName());
                return null;
            }
            
            int exitCode = process.exitValue();
            
            if (exitCode == 0) {
                logger.debug("ClamAV scan clean for: {}", file.getName());
                return new ScanResult(true, null, "clamav");
            } else if (exitCode == 1) {
                // Virus found
                String output = readProcessOutput(process);
                String threat = extractThreatName(output);
                logger.warn("ClamAV detected threat in {}: {}", file.getName(), threat);
                return new ScanResult(false, threat, "clamav");
            } else {
                logger.warn("ClamAV scan error (exit code {}): {}", exitCode, file.getName());
                return null;
            }
            
        } catch (Exception e) {
            logger.debug("ClamAV not available or error occurred: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Performs basic heuristic scanning
     */
    private ScanResult performHeuristicScan(File file) throws IOException {
        logger.debug("Performing heuristic scan on: {}", file.getName());
        
        // Check file extension vs content type mismatch
        String fileName = file.getName().toLowerCase();
        if (fileName.endsWith(".pdf")) {
            byte[] header = new byte[4];
            try (FileInputStream fis = new FileInputStream(file)) {
                int bytesRead = fis.read(header);
                if (bytesRead >= 4) {
                    // PDF files should start with %PDF
                    String headerStr = new String(header);
                    if (!headerStr.equals("%PDF")) {
                        logger.warn("PDF file {} has suspicious header: {}", file.getName(), headerStr);
                        return new ScanResult(false, "SUSPICIOUS_PDF_HEADER", "heuristic");
                    }
                }
            }
        }
        
        // Check for suspicious file size (0 bytes or extremely large)
        long fileSize = file.length();
        if (fileSize == 0) {
            return new ScanResult(false, "EMPTY_FILE", "heuristic");
        }
        
        if (fileSize > maxFileSizeMB * 1024 * 1024) {
            return new ScanResult(false, "FILE_TOO_LARGE", "heuristic");
        }
        
        logger.debug("Heuristic scan clean for: {}", file.getName());
        return new ScanResult(true, null, "heuristic");
    }
    
    /**
     * Checks if file contains a specific signature
     */
    private boolean containsSignature(byte[] fileBytes, byte[] signature) {
        if (signature.length > fileBytes.length) {
            return false;
        }
        
        for (int i = 0; i <= fileBytes.length - signature.length; i++) {
            boolean match = true;
            for (int j = 0; j < signature.length; j++) {
                if (fileBytes[i + j] != signature[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Reads process output for error messages
     */
    private String readProcessOutput(Process process) throws IOException {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        return output.toString();
    }
    
    /**
     * Extracts threat name from ClamAV output
     */
    private String extractThreatName(String output) {
        // ClamAV output format: filename: THREAT_NAME FOUND
        String[] lines = output.split("\n");
        for (String line : lines) {
            if (line.contains("FOUND")) {
                String[] parts = line.split(":");
                if (parts.length > 1) {
                    String threatPart = parts[1].trim();
                    return threatPart.replace(" FOUND", "");
                }
            }
        }
        return "UNKNOWN_THREAT";
    }
}