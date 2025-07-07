package cerg.pdfanalyzer.service;

import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.PdfResources;
import com.itextpdf.kernel.pdf.PdfName;
import com.itextpdf.kernel.pdf.PdfStream;
import com.itextpdf.kernel.pdf.PdfDictionary;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.io.image.ImageData;

@Service
public class OcrService {
    private static final Logger logger = LoggerFactory.getLogger(OcrService.class);
    
    @Value("${ocr.enabled:true}")
    private boolean ocrEnabled;
    
    @Value("${ocr.tesseract.datapath:}")
    private String tesseractDataPath;
    
    @Value("${ocr.tesseract.language:eng}")
    private String tesseractLanguage;
    
    private volatile boolean tesseractInitialized = false;
    private volatile boolean tesseractFailed = false;
    private final ReentrantLock tesseractLock = new ReentrantLock();
    
    @PostConstruct
    public void init() {
        if (!ocrEnabled) {
            logger.info("OCR service is disabled by configuration");
            return;
        }
        
        logger.info("OCR service configured - will initialize Tesseract on first use");
        logger.info("Tesseract configuration: Language={}, DataPath={}", 
                   tesseractLanguage, 
                   tesseractDataPath.isEmpty() ? "default" : tesseractDataPath);
    }
    
    @PreDestroy
    public void cleanup() {
        tesseractLock.lock();
        try {
            tesseractInitialized = false;
            tesseractFailed = false;
            logger.info("OCR service cleanup completed");
        } finally {
            tesseractLock.unlock();
        }
    }
    
    /**
     * Lazy initialization of Tesseract - only initialize when first needed
     */
    private boolean ensureTesseractInitialized() {
        if (tesseractInitialized) {
            return true;
        }
        
        if (tesseractFailed) {
            return false;
        }
        
        tesseractLock.lock();
        try {
            // Double-check pattern
            if (tesseractInitialized) {
                return true;
            }
            
            if (tesseractFailed) {
                return false;
            }
            
            logger.info("Initializing Tesseract OCR for first use...");
            
            // Try to create a basic Tesseract instance without doing OCR
            try {
                Tesseract testInstance = createTesseractInstance();
                // Just check if we can create the instance without native calls
                if (testInstance != null) {
                    logger.info("Tesseract OCR initialized successfully - Language: {}", tesseractLanguage);
                    tesseractInitialized = true;
                    return true;
                }
            } catch (UnsatisfiedLinkError e) {
                logger.warn("Tesseract native libraries not found: {}", e.getMessage());
                tesseractFailed = true;
                return false;
            } catch (Exception e) {
                logger.warn("Failed to initialize Tesseract: {}", e.getMessage());
                tesseractFailed = true;
                return false;
            } catch (Error e) {
                logger.error("Critical error during Tesseract initialization: {}", e.getMessage());
                tesseractFailed = true;
                return false;
            }
            
            logger.warn("Tesseract initialization failed - unknown reason");
            tesseractFailed = true;
            return false;
            
        } finally {
            tesseractLock.unlock();
        }
    }
    
    private Tesseract createTesseractInstance() {
        Tesseract tesseract = new Tesseract();
        
        // Set data path if provided
        if (tesseractDataPath != null && !tesseractDataPath.trim().isEmpty()) {
            tesseract.setDatapath(tesseractDataPath);
        }
        
        // Set language - support multiple languages for better Chinese detection
        String language = tesseractLanguage;
        if (language.equals("eng") || language.equals("chi_sim") || language.equals("chi_tra")) {
            // Use combined language model for better Chinese + English detection
            if (language.equals("chi_sim")) {
                language = "chi_sim+eng";
            } else if (language.equals("chi_tra")) {
                language = "chi_tra+eng";
            }
        }
        tesseract.setLanguage(language);
        
        // Set page segmentation mode to handle various image types
        tesseract.setPageSegMode(1); // Automatic page segmentation with OSD
        tesseract.setOcrEngineMode(1); // Neural nets LSTM engine only
        
        return tesseract;
    }
    
    /**
     * Extract text from images in a PDF file using Tesseract OCR
     */
    public Map<Integer, List<String>> extractTextFromImages(File pdfFile) {
        Map<Integer, List<String>> imageTexts = new HashMap<>();
        
        logger.info("=== OCR SERVICE STARTING ===");
        logger.info("OCR enabled: {}", ocrEnabled);
        logger.info("PDF file: {}", pdfFile.getAbsolutePath());
        logger.info("File exists: {}", pdfFile.exists());
        logger.info("File size: {} bytes", pdfFile.length());
        
        if (!ocrEnabled) {
            logger.debug("OCR service is disabled. Skipping image text extraction.");
            return imageTexts;
        }
        
        if (!ensureTesseractInitialized()) {
            logger.warn("Tesseract not available. Skipping image text extraction.");
            return imageTexts;
        }
        
        try (PdfReader reader = new PdfReader(pdfFile);
             PdfDocument document = new PdfDocument(reader)) {
            
            logger.info("PDF opened successfully. Pages: {}", document.getNumberOfPages());
            
            for (int pageIndex = 1; pageIndex <= document.getNumberOfPages(); pageIndex++) {
                logger.info("Processing page {} of {}", pageIndex, document.getNumberOfPages());
                PdfPage page = document.getPage(pageIndex);
                List<String> pageImageTexts = extractTextFromImagesOnPage(page, pageIndex);
                
                logger.info("Page {}: found {} images", pageIndex, pageImageTexts.size());
                for (int i = 0; i < pageImageTexts.size(); i++) {
                    logger.info("  Image {}: '{}'", i + 1, pageImageTexts.get(i));
                }
                
                if (!pageImageTexts.isEmpty()) {
                    imageTexts.put(pageIndex, pageImageTexts);
                }
            }
        } catch (IOException e) {
            logger.error("Error extracting images from PDF: {}", e.getMessage(), e);
        } catch (Exception e) {
            logger.error("Unexpected error during OCR processing: {}", e.getMessage(), e);
        }
        
        logger.info("=== OCR SERVICE COMPLETED ===");
        logger.info("Total pages with images: {}", imageTexts.size());
        logger.info("Result: {}", imageTexts);
        
        return imageTexts;
    }
    
    /**
     * Extract text from images on a specific page using Tesseract OCR
     */
    private List<String> extractTextFromImagesOnPage(PdfPage page, int pageNumber) {
        List<String> imageTexts = new ArrayList<>();
        
        logger.info("=== EXTRACTING IMAGES FROM PAGE {} ===", pageNumber);
        
        if (!tesseractInitialized) {
            logger.debug("Tesseract not initialized, skipping page {}", pageNumber);
            return imageTexts;
        }
        
        try {
            PdfResources resources = page.getResources();
            logger.info("Page {} resources: {}", pageNumber, resources != null ? "found" : "null");
            
            PdfDictionary xObjects = resources.getResource(PdfName.XObject);
            logger.info("Page {} XObjects: {}", pageNumber, xObjects != null ? "found" : "null");
            
            if (xObjects != null) {
                logger.info("Page {} XObject keys: {}", pageNumber, xObjects.keySet().size());
                
                for (PdfName name : xObjects.keySet()) {
                    logger.info("Processing XObject: {}", name.getValue());
                    
                    PdfStream xObjectStream = xObjects.getAsStream(name);
                    if (xObjectStream != null) {
                        logger.info("XObject stream found for: {}", name.getValue());
                        
                        // FIX: Get the subtype correctly from the stream's dictionary
                        PdfName subtype = xObjectStream.getAsName(PdfName.Subtype);
                        logger.info("XObject subtype: {}", subtype);
                        
                        if (PdfName.Image.equals(subtype)) {
                            logger.info("Found IMAGE XObject: {}", name.getValue());
                            
                            try {
                                // Extract image data using iText
                                byte[] imageBytes = xObjectStream.getBytes();
                                logger.info("Image {} bytes length: {}", name.getValue(), 
                                    imageBytes != null ? imageBytes.length : "null");
                                
                                if (imageBytes != null && imageBytes.length > 0) {
                                    BufferedImage image = createBufferedImageFromBytes(imageBytes);
                                    logger.info("BufferedImage created for {}: {}x{}", 
                                        name.getValue(), 
                                        image != null ? image.getWidth() : "null",
                                        image != null ? image.getHeight() : "null");
                                    
                                    if (image != null) {
                                        String extractedText = performTesseractOCR(image, pageNumber, name.getValue());
                                        
                                        if (extractedText != null && !extractedText.trim().isEmpty()) {
                                            logger.info("OCR SUCCESS for {}: '{}' ({} chars)", 
                                                name.getValue(), extractedText.trim(), extractedText.trim().length());
                                            imageTexts.add(extractedText.trim());
                                        } else {
                                            logger.info("OCR NO TEXT for {}: marking as image detected", name.getValue());
                                            imageTexts.add("[IMAGE DETECTED - NO TEXT]");
                                        }
                                        
                                        logger.debug("Processed image '{}' on page {}: dimensions={}x{}, hasText={}", 
                                                   name.getValue(), pageNumber, image.getWidth(), image.getHeight(),
                                                   (extractedText != null && !extractedText.trim().isEmpty()));
                                    } else {
                                        logger.warn("Failed to create BufferedImage for {}", name.getValue());
                                    }
                                } else {
                                    logger.warn("No image bytes found for {}", name.getValue());
                                }
                            } catch (Exception e) {
                                logger.warn("Error processing image '{}' on page {}: {}", 
                                           name.getValue(), pageNumber, e.getMessage());
                                imageTexts.add("[IMAGE DETECTED - PROCESSING ERROR]");
                            }
                        } else {
                            logger.info("XObject {} is not an image (subtype: {})", name.getValue(), subtype);
                        }
                    } else {
                        logger.warn("XObject stream is null for: {}", name.getValue());
                    }
                }
            } else {
                logger.info("No XObjects found on page {}", pageNumber);
            }
        } catch (Exception e) {
            logger.error("Error extracting images from page {}: {}", pageNumber, e.getMessage());
        }
        
        logger.info("=== PAGE {} EXTRACTION COMPLETE: {} images ===", pageNumber, imageTexts.size());
        return imageTexts;
    }
    
    /**
     * Create BufferedImage from raw image bytes
     */
    private BufferedImage createBufferedImageFromBytes(byte[] imageBytes) {
        try {
            logger.debug("Attempting to create BufferedImage from {} bytes", imageBytes.length);
            
            // Log first few bytes to understand the image format
            if (imageBytes.length >= 16) {
                StringBuilder hexDump = new StringBuilder();
                for (int i = 0; i < Math.min(16, imageBytes.length); i++) {
                    hexDump.append(String.format("%02X ", imageBytes[i] & 0xFF));
                }
                logger.debug("Image bytes header: {}", hexDump.toString());
            }
            
            // Try direct conversion from bytes using ImageIO first
            try (java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(imageBytes)) {
                BufferedImage image = javax.imageio.ImageIO.read(bais);
                if (image != null) {
                    logger.debug("Successfully created BufferedImage using ImageIO: {}x{}", image.getWidth(), image.getHeight());
                    return image;
                } else {
                    logger.debug("ImageIO.read returned null - unsupported format or corrupted data");
                }
            } catch (Exception e) {
                logger.debug("ImageIO failed: {}", e.getMessage());
            }
            
            // If direct ImageIO fails, try using iText's ImageData for conversion
            try {
                logger.debug("Trying iText ImageDataFactory...");
                ImageData imageData = ImageDataFactory.create(imageBytes);
                logger.debug("Created iText ImageData: {}x{}", 
                    imageData.getWidth(), imageData.getHeight());
                
                // For some PDF images, we might need to extract the underlying image format
                if (imageData != null) {
                    // Try to get the original data in a standard format
                    byte[] processedBytes = imageData.getData();
                    logger.debug("ImageData processed bytes length: {}", 
                        processedBytes != null ? processedBytes.length : "null");
                    
                    if (processedBytes != null && processedBytes.length > 0 && 
                        !java.util.Arrays.equals(imageBytes, processedBytes)) {
                        // Only try if the processed bytes are different from original
                        try (java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(processedBytes)) {
                            BufferedImage image = javax.imageio.ImageIO.read(bais);
                            if (image != null) {
                                logger.debug("Successfully created BufferedImage using iText processed data: {}x{}", 
                                    image.getWidth(), image.getHeight());
                                return image;
                            }
                        } catch (Exception e) {
                            logger.debug("Failed to create BufferedImage from processed bytes: {}", e.getMessage());
                        }
                    }
                    
                    // Create BufferedImage manually from ImageData dimensions
                    int width = (int) imageData.getWidth();
                    int height = (int) imageData.getHeight();
                    if (width > 0 && height > 0) {
                        // Create a placeholder image with the correct dimensions
                        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                        
                        // Fill with a light gray color to indicate it's a placeholder
                        java.awt.Graphics2D g2d = image.createGraphics();
                        g2d.setColor(java.awt.Color.LIGHT_GRAY);
                        g2d.fillRect(0, 0, width, height);
                        g2d.setColor(java.awt.Color.BLACK);
                        g2d.drawString("IMAGE PLACEHOLDER", 10, height/2);
                        g2d.dispose();
                        
                        logger.debug("Created placeholder BufferedImage: {}x{}", width, height);
                        return image;
                    }
                }
            } catch (Exception e) {
                logger.debug("iText ImageData conversion failed: {}", e.getMessage());
            }
            
            // Final fallback: try to determine image format from bytes
            String imageFormat = detectImageFormat(imageBytes);
            logger.debug("Detected image format: {}", imageFormat);
            
            if ("raw".equals(imageFormat)) {
                logger.debug("Attempting to create BufferedImage from raw pixel data");
                // This might be raw pixel data - try to determine dimensions
                // Common sizes for 240000 bytes: 400x200x3 (RGB), 200x200x6, etc.
                int[] possibleDimensions = {
                    400, 200,  // 400x200x3 = 240000
                    200, 400,  // 200x400x3 = 240000
                };
                
                for (int i = 0; i < possibleDimensions.length; i += 2) {
                    int width = possibleDimensions[i];
                    int height = possibleDimensions[i + 1];
                    if (width * height * 3 == imageBytes.length) {
                        try {
                            BufferedImage image = createImageFromRawBytes(imageBytes, width, height);
                            if (image != null) {
                                logger.debug("Successfully created BufferedImage from raw bytes: {}x{}", width, height);
                                return image;
                            }
                        } catch (Exception e) {
                            logger.debug("Failed to create image from raw bytes {}x{}: {}", width, height, e.getMessage());
                        }
                    }
                }
            }
            
            logger.warn("All BufferedImage creation methods failed for image bytes");
            return null;
            
        } catch (Exception e) {
            logger.warn("Error creating BufferedImage from bytes: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Detect image format from byte header
     */
    private String detectImageFormat(byte[] bytes) {
        if (bytes.length < 4) return "unknown";
        
        // Check for common image format signatures
        if (bytes[0] == (byte)0xFF && bytes[1] == (byte)0xD8) return "jpeg";
        if (bytes[0] == (byte)0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47) return "png";
        if (bytes[0] == 0x47 && bytes[1] == 0x49 && bytes[2] == 0x46) return "gif";
        if (bytes[0] == 0x42 && bytes[1] == 0x4D) return "bmp";
        
        return "raw"; // Likely raw pixel data
    }
    
    /**
     * Create BufferedImage from raw RGB bytes
     */
    private BufferedImage createImageFromRawBytes(byte[] bytes, int width, int height) {
        try {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            
            int index = 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (index + 2 < bytes.length) {
                        int r = bytes[index++] & 0xFF;
                        int g = bytes[index++] & 0xFF;
                        int b = bytes[index++] & 0xFF;
                        int rgb = (r << 16) | (g << 8) | b;
                        image.setRGB(x, y, rgb);
                    }
                }
            }
            
            return image;
        } catch (Exception e) {
            logger.debug("Failed to create image from raw bytes: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Perform OCR on a BufferedImage using Tesseract with proper error handling
     */
    private String performTesseractOCR(BufferedImage image, int pageNumber, String imageName) {
        if (!tesseractInitialized) {
            logger.debug("Tesseract not initialized, skipping OCR for image '{}'", imageName);
            return null;
        }
        
        tesseractLock.lock();
        try {
            if (image.getWidth() < 1 || image.getHeight() < 1) {
                logger.warn("Invalid image dimensions for OCR: {}x{} on page {}", 
                           image.getWidth(), image.getHeight(), pageNumber);
                return null;
            }
            
            // Skip very small images that are unlikely to contain readable text
            if (image.getWidth() < 50 || image.getHeight() < 20) {
                logger.debug("Skipping very small image '{}' on page {}: {}x{}", 
                           imageName, pageNumber, image.getWidth(), image.getHeight());
                return null;
            }
            
            // Create a new Tesseract instance for each OCR operation to avoid memory issues
            Tesseract tesseract = createTesseractInstance();
            
            // Perform OCR using Tesseract
            String result = tesseract.doOCR(image);
            
            if (result != null && !result.trim().isEmpty()) {
                logger.debug("Tesseract OCR extracted text from image '{}' on page {}: {} characters", 
                           imageName, pageNumber, result.length());
                return result;
            }
            
        } catch (TesseractException e) {
            logger.warn("Tesseract OCR error for image '{}' on page {}: {}", 
                       imageName, pageNumber, e.getMessage());
        } catch (UnsatisfiedLinkError e) {
            logger.error("Tesseract native library error for image '{}' on page {}: {}", 
                        imageName, pageNumber, e.getMessage());
            // Mark as failed to prevent further attempts
            tesseractFailed = true;
            tesseractInitialized = false;
        } catch (Error e) {
            logger.error("Native error during OCR for image '{}' on page {}: {}", 
                        imageName, pageNumber, e.getMessage());
            // Mark Tesseract as failed if we get native errors
            tesseractFailed = true;
            tesseractInitialized = false;
        } catch (Exception e) {
            logger.error("Unexpected error during OCR for image '{}' on page {}: {}", 
                        imageName, pageNumber, e.getMessage());
        } finally {
            tesseractLock.unlock();
        }
        
        return null;
    }
    
    /**
     * Check if Tesseract OCR service is available and enabled
     */
    public boolean isOcrAvailable() {
        return ocrEnabled && !tesseractFailed && ensureTesseractInitialized();
    }
    
    /**
     * Get Tesseract OCR configuration info
     */
    public String getOcrInfo() {
        if (!ocrEnabled) {
            return "OCR service is disabled by configuration.";
        }
        
        if (tesseractFailed) {
            return "Tesseract OCR service failed to initialize or encountered native errors.";
        }
        
        if (!tesseractInitialized) {
            return "Tesseract OCR service not yet initialized (will initialize on first use).";
        }
        
        return String.format("Tesseract OCR service is enabled - Language: %s", tesseractLanguage);
    }
}