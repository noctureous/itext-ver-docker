package cerg.pdfanalyzer.controller;

import cerg.pdfanalyzer.dto.FontInfo;
import cerg.pdfanalyzer.dto.PdfAnalysisResult;
import cerg.pdfanalyzer.exception.VirusDetectedException;
import cerg.pdfanalyzer.service.AntivirusService;
import cerg.pdfanalyzer.service.PdfAnalysisService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/pdf")
public class PdfAnalyzerController {
    private static final Logger logger = LoggerFactory.getLogger(PdfAnalyzerController.class);
    private final PdfAnalysisService pdfAnalysisService;
    private final AntivirusService antivirusService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${upload.directory:uploads}")
    private String uploadDirectory;

    @Autowired
    public PdfAnalyzerController(PdfAnalysisService pdfAnalysisService, AntivirusService antivirusService) {
        this.pdfAnalysisService = pdfAnalysisService;
        this.antivirusService = antivirusService;
    }

    @PostConstruct
    private void init() {
        // Ensure upload directory exists after Spring has injected all values
        createUploadDirectoryIfNeeded();
    }

    private void createUploadDirectoryIfNeeded() {
        try {
            // Add null check as a safety measure
            if (uploadDirectory == null || uploadDirectory.trim().isEmpty()) {
                uploadDirectory = "uploads";
                logger.warn("Upload directory was null or empty, using default: {}", uploadDirectory);
            }
            
            Path uploadPath = Paths.get(uploadDirectory);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
                logger.info("Created upload directory: {}", uploadPath.toAbsolutePath());
            }
        } catch (IOException e) {
            logger.error("Failed to create upload directory: {}", uploadDirectory, e);
            // Fallback to system temp directory
            uploadDirectory = System.getProperty("java.io.tmpdir");
            logger.warn("Using system temp directory as fallback: {}", uploadDirectory);
        }
    }

    private File createTempFile(String originalFilename) throws IOException {
        String fileName = UUID.randomUUID().toString() + "_" + 
                          (originalFilename != null ? originalFilename.replaceAll("[^a-zA-Z0-9.-]", "_") : "upload.pdf");
        Path uploadPath = Paths.get(uploadDirectory, fileName);
        
        // Ensure the parent directory exists
        Files.createDirectories(uploadPath.getParent());
        
        // Create the actual file
        File tempFile = uploadPath.toFile();
        if (!tempFile.createNewFile()) {
            logger.warn("Temp file already exists, will overwrite: {}", tempFile.getAbsolutePath());
        }
        
        return tempFile;
    }

    /**
     * Safely copy multipart file content to a temporary file using streams
     * This avoids issues with Tomcat's temporary file cleanup
     */
    private void copyMultipartFileToTempFile(MultipartFile multipartFile, File tempFile) throws IOException {
        try (InputStream inputStream = multipartFile.getInputStream();
             FileOutputStream outputStream = new FileOutputStream(tempFile)) {
            
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            outputStream.flush();
            
            logger.debug("Successfully copied {} bytes to temp file: {}", 
                multipartFile.getSize(), tempFile.getAbsolutePath());
        }
    }

    @PostMapping("/upload")
    public ResponseEntity<?> analyzePdf(@RequestParam("file") MultipartFile file) {
        File tempFile = null;
        try {
            logger.info("Received file upload: {} (size: {} bytes)", file.getOriginalFilename(), file.getSize());
            
            // Validate file
            if (file.isEmpty()) {
                throw new IllegalArgumentException("File is empty");
            }
            
            if (!isValidPdfFile(file)) {
                throw new IllegalArgumentException("File must be a PDF");
            }

            // Perform antivirus scan first
            AntivirusService.ScanResult scanResult = antivirusService.scanFile(file);
            if (!scanResult.isClean()) {
                logger.warn("Virus detected in uploaded file {}: {} ({})", 
                    file.getOriginalFilename(), scanResult.getThreat(), scanResult.getScanEngine());
                
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("error", "VIRUS_DETECTED");
                errorResponse.put("message", "File contains malicious content and cannot be processed");
                errorResponse.put("threat", scanResult.getThreat());
                errorResponse.put("scanEngine", scanResult.getScanEngine());
                errorResponse.put("fileName", file.getOriginalFilename());
                
                return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
            }
            
            logger.info("Antivirus scan passed for file: {} ({})", 
                file.getOriginalFilename(), scanResult.getScanEngine());

            // Create temp file in our upload directory
            tempFile = createTempFile(file.getOriginalFilename());
            logger.debug("Creating temp file: {}", tempFile.getAbsolutePath());
            
            // Copy the uploaded file to our temp location using streams
            copyMultipartFileToTempFile(file, tempFile);
            logger.debug("File copied successfully to: {}", tempFile.getAbsolutePath());

            // Analyze the PDF
            PdfAnalysisResult result = pdfAnalysisService.analyzePdf(tempFile);
            // Set the original filename from the uploaded file
            result.setFileName(file.getOriginalFilename());
            
            logger.info("Successfully analyzed file: {}", file.getOriginalFilename());
            
            // *** COMPREHENSIVE JSON LOGGING FOR DEBUGGING ***
            try {
                String resultJson = objectMapper.writeValueAsString(result);
                logger.info("=== PDF ANALYSIS RESULT JSON ===");
                logger.info("File: {}", file.getOriginalFilename());
                logger.info("Result size: {} characters", resultJson.length());
                
                // Log image text data specifically
                if (result.getImageText() != null && !result.getImageText().isEmpty()) {
                    logger.info("=== IMAGE TEXT DATA DETECTED ===");
                    for (Map.Entry<Integer, List<String>> entry : result.getImageText().entrySet()) {
                        int pageNum = entry.getKey();
                        List<String> imageTexts = entry.getValue();
                        logger.info("Page {}: {} images detected", pageNum, imageTexts.size());
                        for (int i = 0; i < imageTexts.size(); i++) {
                            String imageText = imageTexts.get(i);
                            logger.info("  Image {}: '{}' (length: {})", i + 1, 
                                imageText != null ? imageText.substring(0, Math.min(imageText.length(), 100)) : "null",
                                imageText != null ? imageText.length() : 0);
                        }
                    }
                } else {
                    logger.warn("=== NO IMAGE TEXT DATA FOUND ===");
                    logger.warn("ImageText field: {}", result.getImageText());
                    logger.warn("ImageText is null: {}", result.getImageText() == null);
                    logger.warn("ImageText is empty: {}", result.getImageText() != null && result.getImageText().isEmpty());
                }
                
                // Log font info that includes OCR data
                if (result.getFontInfo() != null && !result.getFontInfo().isEmpty()) {
                    logger.info("=== FONT INFO DATA ===");
                    for (Map.Entry<Integer, List<FontInfo>> entry : result.getFontInfo().entrySet()) {
                        int pageNum = entry.getKey();
                        List<FontInfo> fonts = entry.getValue();
                        logger.info("Page {}: {} fonts detected", pageNum, fonts.size());
                        for (FontInfo font : fonts) {
                            if (font.getFontName() != null && 
                                (font.getFontName().contains("OCR") || font.getFontName().contains("Image"))) {
                                logger.info("  OCR/Image Font: {} - Text: '{}'", font.getFontName(), font.getText());
                            } else {
                                logger.info("  Regular Font: {} - Text: '{}'", font.getFontName(), 
                                    font.getText() != null ? font.getText().substring(0, Math.min(font.getText().length(), 50)) + "..." : "null");
                            }
                        }
                    }
                } else {
                    logger.warn("=== NO FONT INFO DATA FOUND ===");
                    logger.warn("FontInfo field: {}", result.getFontInfo());
                }
                
                // Log compact JSON for inspection
                logger.debug("Full JSON response: {}", resultJson);
                
            } catch (Exception e) {
                logger.error("Error logging JSON result", e);
            }
            
            logger.info("Successfully analyzed file: {}", file.getOriginalFilename());

            return new ResponseEntity<>(result, HttpStatus.OK);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid file upload: {}", e.getMessage());
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "INVALID_FILE");
            errorResponse.put("message", e.getMessage());
            return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
        } catch (IOException e) {
            logger.error("Error analyzing uploaded PDF: {}", file.getOriginalFilename(), e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "PROCESSING_ERROR");
            errorResponse.put("message", "Error processing the uploaded file: " + e.getMessage());
            return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (Exception e) {
            logger.error("Unexpected error analyzing uploaded PDF: {}", file.getOriginalFilename(), e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "UNEXPECTED_ERROR");
            errorResponse.put("message", "An unexpected error occurred while processing the file");
            return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
        } finally {
            // Clean up temp file
            if (tempFile != null && tempFile.exists()) {
                try {
                    if (tempFile.delete()) {
                        logger.debug("Deleted temp file: {}", tempFile.getAbsolutePath());
                    } else {
                        logger.warn("Failed to delete temp file: {}", tempFile.getAbsolutePath());
                    }
                } catch (Exception e) {
                    logger.warn("Error deleting temp file: {}", tempFile.getAbsolutePath(), e);
                }
            }
        }
    }

    private boolean isValidPdfFile(MultipartFile file) {
        String contentType = file.getContentType();
        String filename = file.getOriginalFilename();
        
        return (contentType != null && contentType.equals("application/pdf")) ||
               (filename != null && filename.toLowerCase().endsWith(".pdf"));
    }

    @PostMapping("/upload-multiple")
    public ResponseEntity<?> analyzeMultiplePdfs(@RequestParam("files") List<MultipartFile> files) {
        try {
            logger.info("Received multiple file upload request with {} files", files.size());
            
            List<PdfAnalysisResult> results = new ArrayList<>();
            List<Map<String, Object>> errors = new ArrayList<>();
            
            for (MultipartFile file : files) {
                File tempFile = null;
                try {
                    if (file.isEmpty()) {
                        logger.warn("Skipping empty file: {}", file.getOriginalFilename());
                        Map<String, Object> errorInfo = new HashMap<>();
                        errorInfo.put("fileName", file.getOriginalFilename());
                        errorInfo.put("error", "EMPTY_FILE");
                        errorInfo.put("message", "File is empty");
                        errors.add(errorInfo);
                        continue;
                    }
                    
                    if (!isValidPdfFile(file)) {
                        logger.warn("Skipping non-PDF file: {}", file.getOriginalFilename());
                        Map<String, Object> errorInfo = new HashMap<>();
                        errorInfo.put("fileName", file.getOriginalFilename());
                        errorInfo.put("error", "INVALID_FILE_TYPE");
                        errorInfo.put("message", "File must be a PDF");
                        errors.add(errorInfo);
                        continue;
                    }
                    
                    logger.info("Processing file: {} (size: {} bytes)", file.getOriginalFilename(), file.getSize());

                    // Perform antivirus scan first
                    AntivirusService.ScanResult scanResult = antivirusService.scanFile(file);
                    if (!scanResult.isClean()) {
                        logger.warn("Virus detected in uploaded file {}: {} ({})", 
                            file.getOriginalFilename(), scanResult.getThreat(), scanResult.getScanEngine());
                        
                        Map<String, Object> errorInfo = new HashMap<>();
                        errorInfo.put("fileName", file.getOriginalFilename());
                        errorInfo.put("error", "VIRUS_DETECTED");
                        errorInfo.put("threat", scanResult.getThreat());
                        errorInfo.put("scanEngine", scanResult.getScanEngine());
                        errors.add(errorInfo);
                        continue; // Skip this file
                    }
                    
                    logger.info("Antivirus scan passed for file: {} ({})", 
                        file.getOriginalFilename(), scanResult.getScanEngine());

                    // Create temp file in our upload directory
                    tempFile = createTempFile(file.getOriginalFilename());
                    logger.debug("Creating temp file: {}", tempFile.getAbsolutePath());
                    
                    // Copy the uploaded file to our temp location using streams
                    copyMultipartFileToTempFile(file, tempFile);
                    logger.debug("File copied successfully to: {}", tempFile.getAbsolutePath());

                    try {
                        PdfAnalysisResult result = pdfAnalysisService.analyzePdf(tempFile);
                        // Set the original filename from the uploaded file
                        result.setFileName(file.getOriginalFilename());
                        results.add(result);
                        logger.info("Successfully analyzed file: {}", file.getOriginalFilename());
                    } catch (Exception e) {
                        logger.error("Error analyzing file: {}", file.getOriginalFilename(), e);
                        Map<String, Object> errorInfo = new HashMap<>();
                        errorInfo.put("fileName", file.getOriginalFilename());
                        errorInfo.put("error", "ANALYSIS_ERROR");
                        errorInfo.put("message", "Error analyzing PDF content: " + e.getMessage());
                        errors.add(errorInfo);
                    }
                } catch (IllegalArgumentException e) {
                    logger.warn("Invalid file {}: {}", file.getOriginalFilename(), e.getMessage());
                    Map<String, Object> errorInfo = new HashMap<>();
                    errorInfo.put("fileName", file.getOriginalFilename());
                    errorInfo.put("error", "INVALID_FILE");
                    errorInfo.put("message", e.getMessage());
                    errors.add(errorInfo);
                } catch (IOException e) {
                    logger.error("IO error processing file: {}", file.getOriginalFilename(), e);
                    Map<String, Object> errorInfo = new HashMap<>();
                    errorInfo.put("fileName", file.getOriginalFilename());
                    errorInfo.put("error", "IO_ERROR");
                    errorInfo.put("message", "Error reading file: " + e.getMessage());
                    errors.add(errorInfo);
                } catch (Exception e) {
                    logger.error("Unexpected error processing file: {}", file.getOriginalFilename(), e);
                    Map<String, Object> errorInfo = new HashMap<>();
                    errorInfo.put("fileName", file.getOriginalFilename());
                    errorInfo.put("error", "UNEXPECTED_ERROR");
                    errorInfo.put("message", "Unexpected error occurred");
                    errors.add(errorInfo);
                } finally {
                    // Clean up temp file
                    if (tempFile != null && tempFile.exists()) {
                        try {
                            if (tempFile.delete()) {
                                logger.debug("Deleted temp file: {}", tempFile.getAbsolutePath());
                            } else {
                                logger.warn("Failed to delete temp file: {}", tempFile.getAbsolutePath());
                            }
                        } catch (Exception e) {
                            logger.warn("Error deleting temp file: {}", tempFile.getAbsolutePath(), e);
                        }
                    }
                }
            }

            // Prepare response
            Map<String, Object> response = new HashMap<>();
            response.put("results", results);
            response.put("errors", errors);
            response.put("totalFiles", files.size());
            response.put("successfulScans", results.size());
            response.put("failedScans", errors.size());

            logger.info("Completed multiple file upload: {} successful, {} failed out of {} total files", 
                       results.size(), errors.size(), files.size());

            if (results.isEmpty() && !errors.isEmpty()) {
                logger.warn("No valid PDF files were processed successfully");
                return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
            }

            return new ResponseEntity<>(response, HttpStatus.OK);
        } catch (Exception e) {
            logger.error("Error analyzing uploaded PDFs", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "PROCESSING_ERROR");
            errorResponse.put("message", "Error processing uploaded files: " + e.getMessage());
            return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/analyze/{fileName}")
    public ResponseEntity<PdfAnalysisResult> analyzeExistingPdf(@PathVariable String fileName) {
        try {
            File file = new File(pdfAnalysisService.getInputDirectory() + File.separator + fileName);
            if (!file.exists()) {
                logger.warn("File not found: {}", file.getAbsolutePath());
                return new ResponseEntity<>(null, HttpStatus.NOT_FOUND);
            }

            // Scan existing file for viruses before analysis
            AntivirusService.ScanResult scanResult = antivirusService.scanFile(file);
            if (!scanResult.isClean()) {
                logger.warn("Virus detected in existing file {}: {} ({})", 
                    fileName, scanResult.getThreat(), scanResult.getScanEngine());
                throw new VirusDetectedException(scanResult.getThreat(), scanResult.getScanEngine());
            }

            PdfAnalysisResult result = pdfAnalysisService.analyzePdf(file);
            return new ResponseEntity<>(result, HttpStatus.OK);
        } catch (VirusDetectedException e) {
            logger.error("Virus detected in file: {}", fileName, e);
            return new ResponseEntity<>(null, HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            logger.error("Error analyzing existing PDF: {}", fileName, e);
            return new ResponseEntity<>(null, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/analyze-directory")
    public ResponseEntity<List<PdfAnalysisResult>> analyzeDirectory(@RequestParam("dir") String dirPath) {
        try {
            File dir = new File(dirPath);
            if (!dir.exists() || !dir.isDirectory()) {
                logger.warn("Directory not found or not a directory: {}", dirPath);
                return new ResponseEntity<>(null, HttpStatus.BAD_REQUEST);
            }

            File[] pdfFiles = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".pdf"));
            List<PdfAnalysisResult> results = new ArrayList<>();
            if (pdfFiles != null) {
                logger.info("Found PDF files in directory: {}", dirPath);
                for (File pdf : pdfFiles) {
                    logger.info("Analyzing PDF: {}", pdf.getAbsolutePath());
                    try {
                        // Scan file for viruses before analysis
                        AntivirusService.ScanResult scanResult = antivirusService.scanFile(pdf);
                        if (!scanResult.isClean()) {
                            logger.warn("Skipping virus-infected file {}: {} ({})", 
                                pdf.getName(), scanResult.getThreat(), scanResult.getScanEngine());
                            continue; // Skip infected files
                        }

                        PdfAnalysisResult result = pdfAnalysisService.analyzePdf(pdf);
                        // Log the result as JSON
                        logger.debug("Analysis result for {}: {}", pdf.getName(), objectMapper.writeValueAsString(result));
                        results.add(result);
                    } catch (Exception e) {
                        logger.error("Error analyzing PDF: {}", pdf.getAbsolutePath(), e);
                    }
                }
            } else {
                logger.warn("No PDF files found or directory is not accessible: {}", dirPath);
            }

            if (results.isEmpty()) {
                logger.warn("No analysis results generated for directory: {}", dirPath);
                return new ResponseEntity<>(results, HttpStatus.OK);
            }

            return new ResponseEntity<>(results, HttpStatus.OK);
        } catch (Exception e) {
            logger.error("Error analyzing directory: {}", dirPath, e);
            return new ResponseEntity<>(null, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Global exception handler for file upload size exceeded errors
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException e) {
        logger.warn("File upload size exceeded: {}", e.getMessage());
        
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("error", "FILE_SIZE_EXCEEDED");
        errorResponse.put("message", "File size exceeds the maximum allowed limit. Please upload a smaller file.");
        errorResponse.put("maxSize", "50GB");
        
        return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
    }
}