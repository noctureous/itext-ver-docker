package cerg.pdfanalyzer.service;

import cerg.pdfanalyzer.dto.FontInfo;
import cerg.pdfanalyzer.dto.MarginInfo;
import cerg.pdfanalyzer.dto.PageInfo;
import cerg.pdfanalyzer.dto.PdfAnalysisResult;
import cerg.pdfanalyzer.dto.SpacingInfo;
import com.itextpdf.kernel.pdf.*;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.canvas.parser.*;
import com.itextpdf.kernel.pdf.canvas.parser.data.*;
import com.itextpdf.kernel.pdf.canvas.parser.listener.*;
import com.itextpdf.pdfa.PdfADocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.itextpdf.pdfa.checker.PdfA1Checker;
import com.itextpdf.kernel.pdf.PdfAConformanceLevel;
import com.itextpdf.kernel.pdf.PdfCatalog;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfDictionary;
import com.itextpdf.kernel.pdf.PdfName;
import com.itextpdf.kernel.pdf.PdfStream;
import com.itextpdf.kernel.pdf.PdfObject;

@Service
public class PdfAnalysisService {
    private static final Logger logger = LoggerFactory.getLogger(PdfAnalysisService.class);
    private File inputDirectory;
    private final OcrService ocrService;

    @Value("${pdf.input-directory}")
    private String inputDirectoryPath;

    public PdfAnalysisService(OcrService ocrService) {
        this.ocrService = ocrService;
    }

    @PostConstruct
    public void init() {
        this.inputDirectory = new File(inputDirectoryPath);
        if (!inputDirectory.exists() || !inputDirectory.isDirectory()) {
            logger.warn("Input directory {} does not exist or is not a directory", inputDirectoryPath);
        }
    }

    public File getInputDirectory() {
        return inputDirectory;
    }

    public void setInputDirectory(File inputDirectory) {
        this.inputDirectory = inputDirectory;
    }

    public String getInputDirectoryPath() {
        return inputDirectory.getAbsolutePath();
    }

    public PdfAnalysisResult analyzePdf(File pdfFile) {
        if (pdfFile == null || !pdfFile.exists() || !pdfFile.isFile() || !pdfFile.canRead()) {
            throw new IllegalArgumentException("Invalid PDF file: " + pdfFile);
        }

        PdfAnalysisResult result = new PdfAnalysisResult();
        result.setFileName(pdfFile.getName());

        try (PdfReader reader = new PdfReader(pdfFile); PdfDocument pdfDoc = new PdfDocument(reader)) {
            Map<Integer, String> pageText = new HashMap<>();
            result.setFontInfo(analyzeFonts(pdfDoc, pageText));
            result.setMargins(analyzeMargins(pdfDoc));
            result.setPageSizes(analyzePageSizes(pdfDoc));
            result.setSpacing(analyzeSpacing(pdfDoc));
            result.setPageText(pageText);
            
            // Extract text from images using OCR
            Map<Integer, List<String>> imageText = ocrService.extractTextFromImages(pdfFile);
            result.setImageText(imageText);
            
            // Combine regular text with OCR text for analysis
            Map<Integer, String> combinedText = combineTextWithImageText(pageText, imageText);
            
            // Analyze fonts in OCR text as well
            analyzeFontsInImageText(result, combinedText);
            
            result.setPdfACompliant(checkPdfACompliance(pdfFile));
            result.setPdfXCompliant(checkPdfXCompliance(pdfFile));
            
        } catch (IOException e) {
            logger.error("Error analyzing PDF: " + e.getMessage(), e);
            throw new RuntimeException("Error analyzing PDF: " + e.getMessage(), e);
        }

        return result;
    }

    /**
     * Combine regular text with OCR-extracted image text
     */
    private Map<Integer, String> combineTextWithImageText(Map<Integer, String> pageText, Map<Integer, List<String>> imageText) {
        Map<Integer, String> combinedText = new HashMap<>(pageText);
        
        for (Map.Entry<Integer, List<String>> entry : imageText.entrySet()) {
            int pageNum = entry.getKey();
            List<String> imageTexts = entry.getValue();
            
            String existingText = combinedText.getOrDefault(pageNum, "");
            StringBuilder combined = new StringBuilder(existingText);
            
            if (!existingText.isEmpty()) {
                combined.append("\n\n--- Text from Images ---\n");
            }
            
            for (int i = 0; i < imageTexts.size(); i++) {
                if (i > 0) combined.append("\n\n");
                combined.append("Image ").append(i + 1).append(": ").append(imageTexts.get(i));
            }
            
            combinedText.put(pageNum, combined.toString());
        }
        
        return combinedText;
    }

    /**
     * Analyze fonts in OCR-extracted text (only for actual images)
     */
    private void analyzeFontsInImageText(PdfAnalysisResult result, Map<Integer, String> combinedText) {
        Map<Integer, List<String>> imageText = result.getImageText();
        if (imageText == null || imageText.isEmpty()) {
            return;
        }

        Map<Integer, List<FontInfo>> fontInfo = result.getFontInfo();
        if (fontInfo == null) {
            fontInfo = new HashMap<>();
            result.setFontInfo(fontInfo);
        }

        for (Map.Entry<Integer, List<String>> entry : imageText.entrySet()) {
            int pageNum = entry.getKey();
            List<String> pageImageTexts = entry.getValue();
            
            List<FontInfo> pageFonts = fontInfo.computeIfAbsent(pageNum, k -> new ArrayList<>());
            
            for (int i = 0; i < pageImageTexts.size(); i++) {
                String imageTextContent = pageImageTexts.get(i);
                
                // Create a FontInfo entry for ALL detected images (with or without text)
                FontInfo ocrFontInfo = new FontInfo();
                
                if (imageTextContent != null && !imageTextContent.trim().isEmpty() && 
                    !imageTextContent.equals("[IMAGE DETECTED - NO TEXT]")) {
                    // Image contains extractable text
                    String processedText = processSpecialCharacters(imageTextContent);
                    
                    // Truncate long text for display but indicate it's from an image
                    String displayText = processedText.length() > 100 ? 
                                      processedText.substring(0, 100) + "..." : processedText;
                    ocrFontInfo.setText("[IMAGE " + (i + 1) + " OCR]: " + displayText);
                    ocrFontInfo.setFontName("OCR-Extracted-From-Image");
                } else {
                    // Image detected but contains no extractable text
                    ocrFontInfo.setText("[IMAGE " + (i + 1) + " DETECTED]: No readable text content");
                    ocrFontInfo.setFontName("Image-Detected-No-Text");
                }
                
                ocrFontInfo.setFontSize(12.0f); // Default estimated size for image text
                ocrFontInfo.setEmbedded(false); // OCR text is not embedded
                
                pageFonts.add(ocrFontInfo);
            }
        }
    }

    /**
     * Process special characters and symbols for better display
     */
    private String processSpecialCharacters(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        
        StringBuilder processed = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (isSpecialSymbol(c)) {
                // Convert special symbols to readable description
                processed.append(getSymbolDescription(c));
            } else if (isChineseCharacter(c)) {
                // Keep Chinese characters as-is but mark them
                processed.append(c);
            } else if (Character.isISOControl(c)) {
                // Replace control characters with space
                processed.append(" ");
            } else {
                processed.append(c);
            }
        }
        
        return processed.toString().trim();
    }

    /**
     * Check if character is a special symbol (like Wingdings)
     */
    private boolean isSpecialSymbol(char c) {
        // Check for common symbol ranges
        return (c >= 0x2700 && c <= 0x27BF) || // Dingbats
               (c >= 0x2600 && c <= 0x26FF) || // Miscellaneous Symbols
               (c >= 0x2190 && c <= 0x21FF) || // Arrows
               (c >= 0x25A0 && c <= 0x25FF) || // Geometric Shapes
               (c == 0x25CF) || // Black circle ⚫
               (c >= 0xF000 && c <= 0xF0FF);   // Private Use Area (Wingdings)
    }

    /**
     * Check if character is Chinese/CJK
     */
    private boolean isChineseCharacter(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || // CJK Unified Ideographs
               (c >= 0x3400 && c <= 0x4DBF) || // CJK Extension A
               (c >= 0x20000 && c <= 0x2A6DF); // CJK Extension B
    }

    /**
     * Get description for special symbols
     */
    private String getSymbolDescription(char c) {
        switch (c) {
            case 0x25CF: return "[BULLET]";
            case 0x25A0: return "[SQUARE]";
            case 0x25B2: return "[TRIANGLE]";
            case 0x2713: return "[CHECKMARK]";
            case 0x2717: return "[X-MARK]";
            case 0x2192: return "[RIGHT-ARROW]";
            case 0x2190: return "[LEFT-ARROW]";
            default:
                if (c >= 0xF000 && c <= 0xF0FF) {
                    return "[WINGDING-" + Integer.toHexString(c).toUpperCase() + "]";
                }
                return "[SYMBOL-" + Integer.toHexString(c).toUpperCase() + "]";
        }
    }

    private Map<Integer, List<FontInfo>> analyzeFonts(PdfDocument pdfDoc, Map<Integer, String> pageTexts) {
        Map<Integer, List<FontInfo>> fontsPerPage = new HashMap<>();
        for (int i = 1; i <= pdfDoc.getNumberOfPages(); i++) {
            PdfPage page = pdfDoc.getPage(i);
            FontCollector fontCollector = new FontCollector();
            LocationTextExtractionStrategy textStrategy = new LocationTextExtractionStrategy();
            MultiListener multiListener = new MultiListener(fontCollector, textStrategy);
            PdfCanvasProcessor processor = new PdfCanvasProcessor(multiListener);
            processor.processPageContent(page);

            String extractedText = textStrategy.getResultantText(); // Do not trim yet
            // Always put the extracted text, even if empty or whitespace, to ensure all paragraphs are captured
            pageTexts.put(i, extractedText != null ? extractedText : "");

            List<FontInfo> fontInfos = fontCollector.getFonts();
            if (fontInfos.isEmpty()) {
                logger.debug("No fonts found on page {}", i);
            }
            fontsPerPage.put(i, fontInfos);
        }
        return fontsPerPage;
    }

    private Map<Integer, MarginInfo> analyzeMargins(PdfDocument pdfDoc) {
        Map<Integer, MarginInfo> margins = new HashMap<>();
        for (int i = 1; i <= pdfDoc.getNumberOfPages(); i++) {
            PdfPage page = pdfDoc.getPage(i);
            Rectangle pageSize = page.getPageSizeWithRotation();
            MarginAnalyzer marginAnalyzer = new MarginAnalyzer(pageSize);
            
            try {
                PdfCanvasProcessor processor = new PdfCanvasProcessor(marginAnalyzer);
                processor.processPageContent(page);
                MarginInfo marginInfo = marginAnalyzer.getMarginInfo();
                margins.put(i, marginInfo);
            } catch (Exception e) {
                logger.error("Error processing margins for page {}: {}", i, e.getMessage());
                MarginInfo marginInfo = new MarginInfo();
                marginInfo.setLeftMargin(-1f);
                marginInfo.setTopMargin(-1f);
                marginInfo.setRightMargin(-1f);
                marginInfo.setBottomMargin(-1f);
                margins.put(i, marginInfo);
            }
        }
        return margins;
    }

    private Map<Integer, PageInfo> analyzePageSizes(PdfDocument pdfDoc) {
        Map<Integer, PageInfo> pageSizes = new HashMap<>();
        for (int i = 1; i <= pdfDoc.getNumberOfPages(); i++) {
            PdfPage page = pdfDoc.getPage(i);
            Rectangle pageSize = page.getPageSizeWithRotation();
            PageInfo pageInfo = new PageInfo();
            pageInfo.setWidth(pageSize.getWidth());
            pageInfo.setHeight(pageSize.getHeight());
            pageInfo.setPageSize(pageSize.toString());
            pageSizes.put(i, pageInfo);
        }
        return pageSizes;
    }

    private Map<Integer, SpacingInfo> analyzeSpacing(PdfDocument pdfDoc) {
        Map<Integer, SpacingInfo> spacingPerPage = new HashMap<>();
        for (int i = 1; i <= pdfDoc.getNumberOfPages(); i++) {
            PdfPage page = pdfDoc.getPage(i);
            SpacingAnalyzer spacingAnalyzer = new SpacingAnalyzer();
            PdfCanvasProcessor processor = new PdfCanvasProcessor(spacingAnalyzer);
            processor.processPageContent(page);
            SpacingInfo spacingInfo = spacingAnalyzer.getSpacingInfo();
            spacingPerPage.put(i, spacingInfo);
        }
        return spacingPerPage;
    }

    public boolean checkPdfACompliance(File pdfFile) {
        PdfDocument pdf = null;
        try (PdfReader reader = new PdfReader(pdfFile)) {
            pdf = new PdfDocument(reader);
            try {
                PdfA1Checker pdfA1Checker = new PdfA1Checker(PdfAConformanceLevel.PDF_A_1B);
                PdfCatalog pdfCatalog = pdf.getCatalog();
                pdfA1Checker.checkDocument(pdfCatalog);
                logger.debug("PDF is PDF/A compliant!");
                return true;
            } catch (Exception e) {
                logger.debug("PDF is NOT compliant: {}", e.getMessage());
                return false;
            }
        } catch (IOException e) {
            logger.error("Error checking PDF/A compliance: {}", e.getMessage());
            return false;
        } finally {
            if (pdf != null) {
                try {
                    pdf.close();
                } catch (Exception e) {
                    logger.error("Error closing PdfDocument: {}", e.getMessage());
                }
            }
        }
    }

    public boolean checkPdfXCompliance(File pdfFile) {
        try (PdfReader reader = new PdfReader(pdfFile); PdfDocument pdfDoc = new PdfDocument(reader)) {
            PdfDictionary catalog = pdfDoc.getCatalog().getPdfObject();
            PdfStream metadata = catalog.getAsStream(PdfName.Metadata);
            if (metadata == null) {
                logger.debug("No XMP metadata found in PDF");
                return false;
            }
            byte[] metadataBytes = metadata.getBytes();
            String metadataString = new String(metadataBytes, "UTF-8");
            boolean hasConformance = metadataString.contains("PDF/X") || metadataString.contains("pdfxid:part");
            boolean allFontsEmbedded = areAllFontsEmbedded(pdfDoc);
            boolean colorSpacesOk = areAllColorSpacesCmykOrGray(pdfDoc);
            if (hasConformance && allFontsEmbedded && colorSpacesOk) {
                logger.debug("PDF is PDF/X compliant!");
                return true;
            } else {
                String reason = "";
                if (!hasConformance) reason += "Missing PDF/X metadata; ";
                if (!allFontsEmbedded) reason += "Not all fonts are embedded; ";
                if (!colorSpacesOk) reason += "Invalid color spaces found; ";
                logger.debug("PDF is NOT PDF/X compliant: {}", reason.trim());
                return false;
            }
        } catch (Exception e) {
            logger.error("Error checking PDF/X compliance: {}", e.getMessage());
            return false;
        }
    }

    private boolean areAllFontsEmbedded(PdfDocument pdfDoc) {
        for (int i = 1; i <= pdfDoc.getNumberOfPages(); i++) {
            PdfPage page = pdfDoc.getPage(i);
            PdfDictionary resources = page.getResources().getPdfObject();
            PdfDictionary fontDict = resources.getAsDictionary(PdfName.Font);
            if (fontDict == null) continue;
            for (PdfName fontName : fontDict.keySet()) {
                PdfDictionary font = fontDict.getAsDictionary(fontName);
                if (font == null) continue;
                PdfObject fontFile = font.get(PdfName.FontFile);
                PdfObject fontFile2 = font.get(PdfName.FontFile2);
                PdfObject fontFile3 = font.get(PdfName.FontFile3);
                if (fontFile == null && fontFile2 == null && fontFile3 == null) {
                    logger.debug("Font not embedded on page {}: {}", i, fontName);
                    return false;
                }
            }
        }
        return true;
    }

    private boolean areAllColorSpacesCmykOrGray(PdfDocument pdfDoc) {
        for (int i = 1; i <= pdfDoc.getNumberOfPages(); i++) {
            PdfPage page = pdfDoc.getPage(i);
            PdfDictionary resources = page.getResources().getPdfObject();
            PdfDictionary colorSpaces = resources.getAsDictionary(PdfName.ColorSpace);
            if (colorSpaces == null) continue;
            for (PdfName csName : colorSpaces.keySet()) {
                PdfObject csObj = colorSpaces.get(csName);
                String csStr = csObj.toString();
                if (!(csStr.contains("DeviceCMYK") || csStr.contains("DeviceGray"))) {
                    logger.debug("Non-CMYK/Gray color space on page {}: {}", i, csStr);
                    return false;
                }
            }
        }
        return true;
    }

    private static class FontAnalyzer implements IEventListener {
        private float fontSize = 0;
        private String fontName = null;

        @Override
        public void eventOccurred(IEventData data, EventType type) {
            if (type == EventType.RENDER_TEXT && data instanceof TextRenderInfo) {
                TextRenderInfo renderInfo = (TextRenderInfo) data;
                fontSize = Math.max(fontSize, renderInfo.getFontSize());
                if (fontName == null) {
                    PdfFont font = renderInfo.getFont();
                    if (font != null) {
                        fontName = font.getFontProgram().getFontNames().getFontName();
                    }
                }
            }
        }

        @Override
        public Set<EventType> getSupportedEvents() {
            return Collections.singleton(EventType.RENDER_TEXT);
        }

        public void reset() {
            fontSize = 0;
            fontName = null;
        }

        public FontInfo getFontInfo() {
            FontInfo info = new FontInfo();
            info.setFontSize(fontSize);
            info.setFontName(fontName != null ? fontName : "");
            return info;
        }
    }

    private static class MarginAnalyzer implements IEventListener {
        private float minX = Float.MAX_VALUE;
        private float maxX = Float.MIN_VALUE;
        private float minY = Float.MAX_VALUE; // Track lowest Y for bottom content
        private float maxY = Float.MIN_VALUE; // Track highest Y for top content
        private float maxYWithFontHeight = Float.MIN_VALUE; // Track highest Y including font height
        private final Rectangle pageSize;
        private List<Float> yPositions = new ArrayList<>();
        private List<Float> fontSizes = new ArrayList<>();

        public MarginAnalyzer(Rectangle pageSize) {
            this.pageSize = pageSize;
        }

        @Override
        public void eventOccurred(IEventData data, EventType type) {
            if (type == EventType.RENDER_TEXT && data instanceof TextRenderInfo) {
                TextRenderInfo renderInfo = (TextRenderInfo) data;
                com.itextpdf.kernel.geom.Vector startPoint = renderInfo.getBaseline().getStartPoint();
                com.itextpdf.kernel.geom.Vector endPoint = renderInfo.getBaseline().getEndPoint();
                
                float x = startPoint.get(0);
                float y = startPoint.get(1);
                float endX = endPoint.get(0);
                float fontSize = renderInfo.getFontSize();
                
                // Track all boundaries of text content
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, endX);
                minY = Math.min(minY, y); // Lowest Y (bottom of page content)
                maxY = Math.max(maxY, y); // Highest Y (top of page content)
                
                // Calculate text boundary using actual font metrics if available
                PdfFont font = renderInfo.getFont();
                float fontAscent = fontSize; // Default: use font size as conservative estimate
                
                if (font != null && font.getFontProgram() != null) {
                    try {
                        // Try to get actual font metrics
                        com.itextpdf.io.font.FontMetrics metrics = font.getFontProgram().getFontMetrics();
                        if (metrics != null) {
                            // Use actual ascent from font metrics
                            int unitsPerEm = metrics.getUnitsPerEm();
                            int ascent = metrics.getTypoAscender();
                            if (unitsPerEm > 0 && ascent > 0) {
                                fontAscent = (fontSize * ascent) / unitsPerEm;
                            }
                        }
                    } catch (Exception e) {
                        logger.error("Error getting font metrics for {}: {}", 
                               font.getFontProgram().getFontNames().getFontName(), e.getMessage());
                        // If font metrics unavailable, use conservative estimate
                        logger.debug("Could not get font metrics for {}, using conservative estimate", 
                                   font.getFontProgram().getFontNames().getFontName());
                    }
                }
                
                // For text boundary calculation, use the actual rendered height
                // which should include any spacing applied by the PDF renderer
                float totalTextTop = y + fontAscent;
                maxYWithFontHeight = Math.max(maxYWithFontHeight, totalTextTop);
                
                // Store Y position and font size for content analysis
                yPositions.add(y);
                fontSizes.add(fontSize);
                
                // logger.debug("Text '{}' at Y={:.1f}, fontSize={:.1f}, calculatedAscent={:.1f}, totalTop={:.1f}", 
                //            renderInfo.getText().trim(), y, fontSize, fontAscent, totalTextTop);
            }
        }

        @Override
        public Set<EventType> getSupportedEvents() {
            return Collections.singleton(EventType.RENDER_TEXT);
        }

        public void reset() {
            minX = Float.MAX_VALUE;
            maxX = Float.MIN_VALUE;
            minY = Float.MAX_VALUE;
            maxY = Float.MIN_VALUE;
        }

        public float getMinX() {
            return minX;
        }

        public float getMaxX() {
            return maxX;
        }

        public float getMinY() {
            return minY;
        }

        public float getMaxY() {
            return maxY;
        }

        public MarginInfo getMarginInfo() {
            MarginInfo marginInfo = new MarginInfo();
            
            // Convert points to centimeters
            float conversionFactor = 0.03528f;
            
            if (minX == Float.MAX_VALUE || maxY == Float.MIN_VALUE) {
                // No text found, use zero margins
                marginInfo.setLeftMargin(0f);
                marginInfo.setTopMargin(0f);
                marginInfo.setRightMargin(0f);
                marginInfo.setBottomMargin(0f);
            } else {
                // Calculate margins based on actual text boundaries
                float leftMargin = minX * conversionFactor;
                float rightMargin = (pageSize.getWidth() - maxX) * conversionFactor;
                
                // Smart top margin calculation including font height
                float topMargin = calculateSmartTopMarginWithFontHeight(conversionFactor);
                
                // Bottom margin: distance from lowest text to bottom of page
                float bottomMargin = minY * conversionFactor;
                
                // Ensure margins are not negative
                leftMargin = Math.max(0f, leftMargin);
                rightMargin = Math.max(0f, rightMargin);
                topMargin = Math.max(0f, topMargin);
                bottomMargin = Math.max(0f, bottomMargin);
                
                marginInfo.setLeftMargin(leftMargin);
                marginInfo.setTopMargin(topMargin);
                marginInfo.setRightMargin(rightMargin);
                marginInfo.setBottomMargin(bottomMargin);
                
                // logger.debug("Page margins calculated - Left: {:.2f}cm, Top: {:.2f}cm, Right: {:.2f}cm, Bottom: {:.2f}cm", 
                //            leftMargin, topMargin, rightMargin, bottomMargin);
                // logger.debug("Text boundaries - minX: {:.1f}, maxX: {:.1f}, minY: {:.1f}, maxY: {:.1f}, maxYWithFont: {:.1f}, pageHeight: {:.1f}", 
                //            minX, maxX, minY, maxY, maxYWithFontHeight, pageSize.getHeight());
            }
            
            return marginInfo;
        }
        
        private float calculateSmartTopMarginWithFontHeight(float conversionFactor) {
            if (yPositions.isEmpty()) {
                return 0f;
            }
            
            // Find the main content area considering font heights
            float mainContentTop = findMainContentTopWithFontHeight();
            
            // Calculate top margin from main content area (including font height)
            float topMarginPoints = pageSize.getHeight() - mainContentTop;
            float topMargin = topMarginPoints * conversionFactor;
            
            // Sanity check: ensure margin is reasonable (between 0 and 10cm)
            if (topMargin < 0 || topMargin > 10.0f) {
                logger.warn("Calculated top margin {:.2f}cm seems unreasonable, using fallback calculation", topMargin);
                // Fallback: use simple calculation from highest content including font height
                topMargin = (pageSize.getHeight() - maxYWithFontHeight) * conversionFactor;
                topMargin = Math.max(0f, Math.min(topMargin, 10.0f));
            }
            
            logger.debug("Smart top margin with font height - Main content top: {:.1f}, Page height: {:.1f}, Calculated margin: {:.2f}cm", 
                       mainContentTop, pageSize.getHeight(), topMargin);
            
            return topMargin;
        }
        
        private float findMainContentTopWithFontHeight() {
            if (yPositions.isEmpty()) {
                return maxYWithFontHeight;
            }
            
            // Create list of text top positions using actual calculated ascents
            List<Float> textTops = new ArrayList<>();
            for (int i = 0; i < yPositions.size(); i++) {
                float y = yPositions.get(i);
                float fontSize = fontSizes.get(i);
                
                // Use the same font ascent calculation as in eventOccurred
                float fontAscent = fontSize; // Default conservative estimate
                
                // This matches the actual calculation used in the main method
                // No hard-coded ratios
                textTops.add(y + fontAscent);
            }
            
            // Sort by highest text top first
            textTops.sort(Collections.reverseOrder());
            
            // Apply the same content analysis logic but to text tops
            return findMainContentTopSimpleWithTops(textTops);
        }
        
        private float findMainContentTopSimpleWithTops(List<Float> sortedTextTops) {
            if (sortedTextTops.size() <= 1) {
                return sortedTextTops.get(0);
            }
            
            // Count content frequency at each approximate text top level
            Map<Integer, Integer> topLevelCount = new HashMap<>();
            int groupingSize = 10; // Group text tops within 10 points
            
            for (float textTop : sortedTextTops) {
                int topLevel = Math.round(textTop / groupingSize) * groupingSize;
                topLevelCount.put(topLevel, topLevelCount.getOrDefault(topLevel, 0) + 1);
            }
            
            // Find the highest text top level with significant content
            int maxCount = topLevelCount.values().stream().mapToInt(Integer::intValue).max().orElse(1);
            int significantThreshold = Math.max(2, maxCount / 4); // At least 2, or 1/4 of max
            
            // Go through sorted text tops and find first one with significant content
            for (float textTop : sortedTextTops) {
                int topLevel = Math.round(textTop / groupingSize) * groupingSize;
                if (topLevelCount.get(topLevel) >= significantThreshold) {
                    logger.debug("Found significant content at text top level {} (count: {})", topLevel, topLevelCount.get(topLevel));
                    return textTop;
                }
            }
            
            // Fallback: use position that's not in the very top 10% of the page
            float pageTop10Percent = pageSize.getHeight() * 0.9f; // Top 10% threshold
            for (float textTop : sortedTextTops) {
                if (textTop <= pageTop10Percent) {
                    logger.debug("Using fallback: content below top 10% at text top: {:.1f}", textTop);
                    return textTop;
                }
            }
            
            // Final fallback: use highest content
            return sortedTextTops.get(0);
        }


        private float findMainContentTopByDensity(List<Float> uniqueY, float medianGap) {
            if (uniqueY.size() <= 2) {
                return uniqueY.get(0); // Not enough data, use highest position
            }
            
            // Calculate all gaps between consecutive content lines
            List<Float> gaps = new ArrayList<>();
            for (int i = 0; i < uniqueY.size() - 1; i++) {
                float gap = uniqueY.get(i) - uniqueY.get(i + 1);
                gaps.add(gap);
            }
            
            // Sort gaps to find statistical outliers
            gaps.sort(Float::compareTo);
            
            // Use statistical approach: find gaps that are significantly larger than normal
            // Calculate Q3 (75th percentile) to identify unusually large gaps
            int q3Index = (int) Math.ceil(gaps.size() * 0.75) - 1;
            float q3Gap = gaps.get(Math.max(0, q3Index));
            
            // Use Q3 as threshold - any gap larger than Q3 is considered significant
            float gapThreshold = q3Gap;
            
            // logger.debug("Gap analysis - median gap: {:.1f}, Q3 gap: {:.1f}, threshold: {:.1f}", 
            //            medianGap, q3Gap, gapThreshold);
            
            // Find first gap from top that exceeds the Q3 threshold
            for (int i = 0; i < uniqueY.size() - 1; i++) {
                float gap = uniqueY.get(i) - uniqueY.get(i + 1);
                
                if (gap > gapThreshold) {
                    logger.debug("Found significant gap of {:.1f} points between Y {:.1f} and {:.1f}", 
                               gap, uniqueY.get(i), uniqueY.get(i + 1));
                    return uniqueY.get(i + 1); // Return position after the gap
                }
            }
            
            // Alternative approach: if no significant gaps found, look for content distribution
            return findContentByDistribution(uniqueY);
        }
        
        private float findContentByDistribution(List<Float> uniqueY) {
            // If all gaps are similar, find where the majority of content lies
            // by looking at the distribution of Y positions
            
            Map<Integer, Integer> yDistribution = new HashMap<>();
            int bucketSize = 20; // Group positions into 20-point buckets
            
            for (float y : yPositions) {
                int bucket = (int) (y / bucketSize);
                yDistribution.put(bucket, yDistribution.getOrDefault(bucket, 0) + 1);
            }
            
            // Find the bucket with the most content
            int maxCount = yDistribution.values().stream().mapToInt(Integer::intValue).max().orElse(1);
            
            // Find the highest Y position in a bucket that has significant content density
            int densityThreshold = Math.max(1, maxCount / 2); // At least half of max density
            
            for (float y : uniqueY) {
                int bucket = (int) (y / bucketSize);
                if (yDistribution.getOrDefault(bucket, 0) >= densityThreshold) {
                    logger.debug("Found dense content bucket {} with {} elements at Y: {:.1f}", 
                               bucket, yDistribution.get(bucket), y);
                    return y;
                }
            }
            
            // Final fallback: use highest content
            return uniqueY.get(0);
        }
    }

    private static class MultiListener implements IEventListener {
        private final List<IEventListener> listeners;

        public MultiListener(IEventListener... listeners) {
            this.listeners = (listeners != null && listeners.length > 0) 
                ? new ArrayList<>(Arrays.asList(listeners))
                : new ArrayList<>();
        }

        @Override
        public void eventOccurred(IEventData data, EventType type) {
            for (IEventListener listener : listeners) {
                if (listener != null) {
                    listener.eventOccurred(data, type);
                }
            }
        }

        @Override
        public Set<EventType> getSupportedEvents() {
            Set<EventType> supportedEvents = new HashSet<>();
            for (IEventListener listener : listeners) {
                if (listener != null && listener.getSupportedEvents() != null) {
                    supportedEvents.addAll(listener.getSupportedEvents());
                }
            }
            return supportedEvents;
        }
    }

    private static class FontCollector implements IEventListener {
        private final List<TextElement> textElements = new ArrayList<>();
        
        private static class TextElement {
            String text;
            String fontName;
            float fontSize;
            boolean isEmbedded;
            float x;
            float y;
            float width;
            
            TextElement(String text, String fontName, float fontSize, boolean isEmbedded, float x, float y, float width) {
                this.text = text;
                this.fontName = fontName;
                this.fontSize = fontSize;
                this.isEmbedded = isEmbedded;
                this.x = x;
                this.y = y;
                this.width = width;
            }
        }

        @Override
        public void eventOccurred(IEventData data, EventType type) {
            if (type == EventType.RENDER_TEXT && data instanceof TextRenderInfo) {
                TextRenderInfo renderInfo = (TextRenderInfo) data;
                PdfFont font = renderInfo.getFont();
                if (font != null) {
                    String fontName = font.getFontProgram().getFontNames().getFontName();
                    String text = renderInfo.getText();
                    float fontSize = renderInfo.getFontSize();
                    boolean isEmbedded = font.isEmbedded();
                    
                    // Get position and width
                    com.itextpdf.kernel.geom.Vector startPoint = renderInfo.getBaseline().getStartPoint();
                    float x = startPoint.get(0);
                    float y = startPoint.get(1);
                    float width = renderInfo.getBaseline().getLength();
                    
                    if (text != null && !text.trim().isEmpty()) {
                        textElements.add(new TextElement(text, fontName, fontSize, isEmbedded, x, y, width));
                    }
                }
            }
        }

        @Override
        public Set<EventType> getSupportedEvents() {
            return Collections.singleton(EventType.RENDER_TEXT);
        }

        public List<FontInfo> getFonts() {
            if (textElements.isEmpty()) {
                return new ArrayList<>();
            }
            
            // Sort text elements by position (top to bottom, left to right)
            textElements.sort((a, b) -> {
                // First by Y position (top to bottom, higher Y first in PDF coordinates)
                int yCompare = Float.compare(b.y, a.y);
                if (Math.abs(a.y - b.y) > 2.0f) { // Different lines - smaller threshold
                    return yCompare;
                }
                // Same line, sort by X position (left to right)
                return Float.compare(a.x, b.x);
            });
            
            // Group consecutive text elements with EXACTLY the same font properties
            List<FontInfo> fontInfoList = new ArrayList<>();
            StringBuilder currentText = new StringBuilder();
            String currentFontKey = null;
            TextElement currentElement = null;
            float lastEndX = 0;
            
            for (int i = 0; i < textElements.size(); i++) {
                TextElement element = textElements.get(i);
                // Create exact font key - no rounding for font size
                String fontKey = element.fontName + "_" + element.fontSize + "_" + element.isEmbedded;
                
                // Only start new group if font properties are different
                boolean startNewGroup = currentElement == null || !fontKey.equals(currentFontKey);
                
                if (startNewGroup) {
                    // Finalize previous group
                    if (currentText.length() > 0 && currentElement != null) {
                        addFontInfo(fontInfoList, currentElement, currentText.toString());
                    }
                    
                    // Start new group
                    currentText = new StringBuilder();
                    currentFontKey = fontKey;
                    currentElement = element;
                    lastEndX = element.x + element.width;
                } else {
                    // Same font - add space if there's a reasonable gap
                    if (currentText.length() > 0 && shouldAddSpace(currentElement, element, lastEndX)) {
                        currentText.append(" ");
                    }
                    lastEndX = element.x + element.width;
                }
                
                // Add text to current group
                currentText.append(element.text);
                currentElement = element;
            }
            
            // Finalize last group
            if (currentText.length() > 0 && currentElement != null) {
                addFontInfo(fontInfoList, currentElement, currentText.toString());
            }
            
            return fontInfoList;
        }
        
        private boolean shouldAddSpace(TextElement prev, TextElement current, float lastEndX) {
            // Don't add space if text already has it
            if (prev.text.endsWith(" ") || current.text.startsWith(" ")) {
                return false;
            }
            
            // Check vertical gap - different lines
            float verticalGap = Math.abs(prev.y - current.y);
            if (verticalGap > 3.0f) { // Increased threshold for line detection
                return true; // New line
            }
            
            // For same line, be very conservative about adding spaces
            // Only add space for very large horizontal gaps
            float horizontalGap = current.x - lastEndX;
            
            // Get font size for context
            float avgFontSize = (prev.fontSize + current.fontSize) / 2;
            
            // Only add space if gap is larger than half the font size
            // This is very conservative - most character spacing should be much smaller
            float largeGapThreshold = avgFontSize / 2;
            
            // Also check if this looks like punctuation that should have space
            boolean isPunctuation = prev.text.matches(".*[.,;:!?]$") || 
                                  current.text.matches("^[.,;:!?].*");
            
            // For punctuation, use smaller threshold
            if (isPunctuation) {
                return horizontalGap > avgFontSize / 4;
            }
            
            // For regular text, only add space for very large gaps
            return horizontalGap > largeGapThreshold;
        }
        
        private float calculateWordSpaceThreshold(float fontSize) {
            // This method is now only used as backup, main logic is in shouldAddSpace
            return fontSize / 2; // Conservative estimate
        }
        
        private void addFontInfo(List<FontInfo> list, TextElement element, String text) {
            FontInfo info = new FontInfo();
            info.setFontName(element.fontName);
            info.setFontSize(element.fontSize);
            info.setEmbedded(element.isEmbedded);
            
            // Clean and normalize the text - remove extra spaces but preserve single spaces
            String cleanText = text.trim().replaceAll("\\s+", " ");
            
            // Apply the same text fixing algorithm used in SpacingAnalyzer
            // String fixedText = fixSpacedCharacters(cleanText);
            String fixedText = cleanText;
            
            info.setText(fixedText);
            
            list.add(info);
        }
        
        /**
         * Fix text where individual characters are separated by spaces
         * This is the same algorithm used in SpacingAnalyzer
         */
        private String fixSpacedCharacters(String text) {
            if (text == null || text.trim().isEmpty()) {
                return text;
            }
            
            // Split into tokens and analyze each sequence
            String[] tokens = text.split("\\s+");
            List<String> fixedTokens = new ArrayList<>();
            
            int i = 0;
            while (i < tokens.length) {
                String token = tokens[i];
                
                // Check if this token looks like it could be part of a spaced word
                if (isLikelySpacedCharacter(token)) {
                    // Look ahead to find all consecutive spaced characters
                    StringBuilder wordBuilder = new StringBuilder();
                    int j = i;
                    
                    // Collect all consecutive single characters and short tokens that look spaced
                    while (j < tokens.length && isPartOfSpacedWord(tokens[j], j == i)) {
                        wordBuilder.append(tokens[j]);
                        j++;
                    }
                    
                    String combinedWord = wordBuilder.toString();
                    
                    // Only combine if we got more than one token (actually fixed something)
                    if (j > i + 1 && isLikelyRealWord(combinedWord)) {
                        fixedTokens.add(combinedWord);
                        i = j; // Skip all the tokens we just combined
                    } else {
                        // Not a spaced word, keep original token
                        fixedTokens.add(token);
                        i++;
                    }
                } else {
                    // Regular token, keep as-is
                    fixedTokens.add(token);
                    i++;
                }
            }
            
            return String.join(" ", fixedTokens);
        }
        
        /**
         * Check if a token looks like it could be a spaced character
         */
        private boolean isLikelySpacedCharacter(String token) {
            if (token == null || token.isEmpty()) return false;
            
            // Single characters are very likely spaced
            if (token.length() == 1) {
                char c = token.charAt(0);
                return Character.isLetterOrDigit(c) || ".,;:!?-/()".indexOf(c) >= 0;
            }
            
            // Short tokens (2-3 chars) might be spaced if they look fragmented
            if (token.length() <= 3) {
                // Check if it's all letters/digits (likely part of a word)
                return token.matches("[a-zA-Z0-9.,;:!?\\-/()]+");
            }
            
            return false;
        }
        
        /**
         * Check if a token should be part of a spaced word we're building
         */
        private boolean isPartOfSpacedWord(String token, boolean isFirst) {
            if (token == null || token.isEmpty()) return false;
            
            // Always include single characters (letters, digits, basic punctuation)
            if (token.length() == 1) {
                char c = token.charAt(0);
                return Character.isLetterOrDigit(c) || ".,;:!?-/()".indexOf(c) >= 0;
            }
            
            // Include short tokens that look like word fragments
            if (token.length() <= 3) {
                // Must be alphanumeric or basic punctuation
                if (!token.matches("[a-zA-Z0-9.,;:!?\\-/()]+")) {
                    return false;
                }
                
                // If it's the first token, be more permissive
                if (isFirst) {
                    return true;
                }
                
                // For subsequent tokens, check if it looks like a continuation
                // (starts with lowercase, or is all digits, or is punctuation)
                char firstChar = token.charAt(0);
                return Character.isLowerCase(firstChar) || 
                       Character.isDigit(firstChar) || 
                       ".,;:!?-/()".indexOf(firstChar) >= 0 ||
                       token.matches("\\d+"); // All digits
            }
            
            // Longer tokens - only include if they look like obvious fragments
            if (token.length() <= 6) {
                // Check for patterns like "tion", "ing", "ed", etc. (word endings)
                if (token.matches("(tion|ing|ed|er|ly|al|ic|ous|ful|less|ment|ness|able|ible)")) {
                    return true;
                }
                
                // Check for patterns like "pre", "pro", "con", etc. (word beginnings)
                if (isFirst && token.matches("(pre|pro|con|dis|mis|over|under|anti|auto|bio|geo|photo|tele)")) {
                    return true;
                }
            }
            
            return false;
        }
        
        /**
         * Check if the combined word looks like a real word (basic heuristics)
         */
        private boolean isLikelyRealWord(String word) {
            if (word == null || word.length() < 2) return false;
            
            // Remove punctuation for analysis
            String cleanWord = word.replaceAll("[^a-zA-Z0-9]", "");
            if (cleanWord.length() < 2) return false;
            
            // Must have at least one vowel for words longer than 2 characters
            if (cleanWord.length() > 2) {
                boolean hasVowel = cleanWord.toLowerCase().matches(".*[aeiou].*");
                if (!hasVowel) {
                    // Exception for common patterns without vowels
                    if (!cleanWord.toLowerCase().matches(".*(th|ch|sh|ph|gh|ck|ng|st|nd|rd|ly).*")) {
                        return false;
                    }
                }
            }
            
            // Check for reasonable letter patterns
            // Reject words with too many consecutive consonants or vowels
            if (cleanWord.toLowerCase().matches(".*[bcdfghjklmnpqrstvwxyz]{5,}.*")) {
                return false; // Too many consecutive consonants
            }
            
            if (cleanWord.toLowerCase().matches(".*[aeiou]{4,}.*")) {
                return false; // Too many consecutive vowels
            }
            
            // Accept if it looks reasonable
            return true;
        }
    }

    /**
     * Helper class to track spacing information for a single paragraph
     */
    private static class ParagraphSpacing {
        float averageLineGap;
        float averageFontSize;
        float spacingRatio;
        int lineCount;
        boolean isSingleLineSpacing;
        String sampleText;
    }
    
    /**
     * Helper class to track information about a single line of text
     */
    private static class LineInfo {
        List<Float> yPositions = new ArrayList<>();
        List<Float> fontSizes = new ArrayList<>();
        List<String> textContents = new ArrayList<>();
        float avgY;
        float avgFontSize;
        String combinedText;
    }

    private static class SpacingAnalyzer implements IEventListener {
        private List<Float> yPositions = new ArrayList<>();
        private List<Float> fontSizes = new ArrayList<>();
        private List<String> fontNames = new ArrayList<>();
        private List<String> textContent = new ArrayList<>();

        @Override
        public void eventOccurred(IEventData data, EventType type) {
            if (type == EventType.RENDER_TEXT && data instanceof TextRenderInfo) {
                TextRenderInfo renderInfo = (TextRenderInfo) data;
                PdfFont font = renderInfo.getFont();
                if (font != null) {
                    float y = renderInfo.getBaseline().getStartPoint().get(1);
                    float fontSize = renderInfo.getFontSize();
                    String fontName = font.getFontProgram().getFontNames().getFontName();
                    String text = renderInfo.getText();
                    
                    yPositions.add(y);
                    fontSizes.add(fontSize);
                    fontNames.add(fontName);
                    textContent.add(text);
                }
            }
        }

        @Override
        public Set<EventType> getSupportedEvents() {
            return Collections.singleton(EventType.RENDER_TEXT);
        }

        public SpacingInfo getSpacingInfo() {
            SpacingInfo spacingInfo = new SpacingInfo();
            
            if (yPositions.size() < 2) {
                spacingInfo.setLineSpacing(0f);
                spacingInfo.setSpacingType("Unknown");
                spacingInfo.setSpacingRatio(0f);
                spacingInfo.setSingleLineSpacing(true);
                spacingInfo.setParagraphDetails(new ArrayList<>());
                return spacingInfo;
            }
            
            // Analyze paragraph-level spacing
            List<ParagraphSpacing> paragraphSpacings = analyzeParagraphSpacing();
            
            if (paragraphSpacings.isEmpty()) {
                spacingInfo.setLineSpacing(0f);
                spacingInfo.setSpacingType("Single Line");
                spacingInfo.setSpacingRatio(1.0f);
                spacingInfo.setSingleLineSpacing(true);
                spacingInfo.setParagraphDetails(new ArrayList<>());
                return spacingInfo;
            }
            
            // Calculate overall spacing metrics
            float avgLineGap = (float) paragraphSpacings.stream()
                .mapToDouble(ps -> ps.averageLineGap)
                .average()
                .orElse(0.0);
            
            float totalSpacingRatio = 0f;
            int validParagraphs = 0;
            
            // Check if ALL paragraphs have acceptable spacing (line gap >= 12 points)
            boolean allParagraphsAcceptable = true;
            
            // Check if the majority of paragraphs are single-line spaced (12-18 pts)
            int singleLineCount = 0;
            int totalParagraphs = paragraphSpacings.size();
            
            List<SpacingInfo.ParagraphSpacingDetail> paragraphDetails = new ArrayList<>();
            
            for (int i = 0; i < paragraphSpacings.size(); i++) {
                ParagraphSpacing ps = paragraphSpacings.get(i);
                boolean isAcceptable = ps.averageLineGap >= 12.0f;
                boolean isSingleLine = ps.averageLineGap >= 12.0f && ps.averageLineGap < 18.0f;
                
                if (!isAcceptable) {
                    allParagraphsAcceptable = false;
                }
                
                if (isSingleLine) {
                    singleLineCount++;
                }
                
                // Create detailed paragraph information
                SpacingInfo.ParagraphSpacingDetail detail = new SpacingInfo.ParagraphSpacingDetail();
                detail.setParagraphNumber(i + 1);
                detail.setLineGap(ps.averageLineGap);
                detail.setSpacingRatio(ps.spacingRatio);
                detail.setAcceptable(isAcceptable);
                detail.setSampleText(ps.sampleText);
                detail.setLineCount(ps.lineCount);
                paragraphDetails.add(detail);
                
                totalSpacingRatio += ps.spacingRatio;
                validParagraphs++;
            }
            
            float avgSpacingRatio = validParagraphs > 0 ? totalSpacingRatio / validParagraphs : 1.0f;
            
            // Determine if this is predominantly single-line spacing
            // At least 50% of paragraphs should be in single-line range (12-18 pts)
            boolean isSingleLineSpacing = (singleLineCount >= totalParagraphs / 2.0) && allParagraphsAcceptable;
            
            // Determine overall spacing type based on line gap, not ratio
            String spacingType;
            if (allParagraphsAcceptable) {
                if (avgLineGap >= 12.0f && avgLineGap < 18.0f) {
                    spacingType = "Single Line (All Paragraphs - Acceptable)";
                } else if (avgLineGap >= 18.0f && avgLineGap < 24.0f) {
                    spacingType = "1.5x Line (All Paragraphs - Acceptable)";
                } else if (avgLineGap >= 24.0f) {
                    spacingType = "Double+ Line (All Paragraphs - Acceptable)";
                } else {
                    spacingType = String.format("Custom %.1f pts Line (All Paragraphs - Acceptable)", avgLineGap);
                }
            } else {
                // Count problematic paragraphs (those with line gap < 12 pts)
                long tooTightParagraphs = paragraphSpacings.stream()
                    .filter(ps -> ps.averageLineGap < 12.0f)
                    .count();
                spacingType = String.format("Invalid Spacing (%d/%d paragraphs gap < 12pts)", 
                    tooTightParagraphs, paragraphSpacings.size());
            }
            
            spacingInfo.setLineSpacing(avgLineGap);
            spacingInfo.setSpacingType(spacingType);
            spacingInfo.setSpacingRatio(avgSpacingRatio);
            spacingInfo.setSingleLineSpacing(isSingleLineSpacing);
            spacingInfo.setParagraphDetails(paragraphDetails);
            
            // logger.debug("Paragraph spacing analysis - {} paragraphs analyzed, Avg line gap: {:.1f}pts, Type: {}, Single-line: {}", 
            //            paragraphSpacings.size(), avgLineGap, spacingType, isSingleLineSpacing);
            
            return spacingInfo;
        }
        
        private List<ParagraphSpacing> analyzeParagraphSpacing() {
            List<ParagraphSpacing> paragraphSpacings = new ArrayList<>();
            
            // Group text elements by Y position to identify lines
            Map<Integer, LineInfo> lines = groupIntoLines();
            
            // Sort lines by Y position (top to bottom)
            List<LineInfo> sortedLines = lines.values().stream()
                .sorted((a, b) -> Float.compare(b.avgY, a.avgY)) // Higher Y first (PDF coordinates)
                .collect(Collectors.toList());
            
            if (sortedLines.size() < 2) {
                return paragraphSpacings;
            }
            
            // Identify paragraph breaks and analyze spacing within each paragraph
            List<List<LineInfo>> paragraphs = identifyParagraphs(sortedLines);
            
            for (List<LineInfo> paragraph : paragraphs) {
                if (paragraph.size() >= 2) { // Need at least 2 lines to measure spacing
                    ParagraphSpacing ps = analyzeSingleParagraphSpacing(paragraph);
                    if (ps != null) {
                        paragraphSpacings.add(ps);
                    }
                }
            }
            
            return paragraphSpacings;
        }
        
        private Map<Integer, LineInfo> groupIntoLines() {
            Map<Integer, LineInfo> lines = new HashMap<>();
            float tolerance = 2.0f; // Points tolerance for same line
            
            for (int i = 0; i < yPositions.size(); i++) {
                float y = yPositions.get(i);
                float fontSize = fontSizes.get(i);
                String text = textContent.get(i);
                
                int lineKey = Math.round(y / tolerance);
                
                LineInfo line = lines.computeIfAbsent(lineKey, k -> new LineInfo());
                line.yPositions.add(y);
                line.fontSizes.add(fontSize);
                line.textContents.add(text);
                // logger.debug(String.format("line testing : %s - Y: %.1f, Font Size: %.1f, Text: %s", 
                //          lineKey, y, fontSize, text));
            }
            
            // Calculate averages for each line
            for (LineInfo line : lines.values()) {
                line.avgY = (float) line.yPositions.stream().mapToDouble(Float::doubleValue).average().orElse(0);
                line.avgFontSize = (float) line.fontSizes.stream().mapToDouble(Float::doubleValue).average().orElse(12);
                line.combinedText = String.join("", line.textContents);
                        // .replaceAll("\\s+", " ") // Normalize spaces
                        // .trim(); // Remove leading/trailing spaces
                // logger.debug(String.format("Line Avg Y: %.1f, Font Size: %.1f, Combined Text: %s", 
                //         line.avgY, line.avgFontSize, line.combinedText));
            }

            return lines;
        }
        
        private List<List<LineInfo>> identifyParagraphs(List<LineInfo> sortedLines) {
            List<List<LineInfo>> paragraphs = new ArrayList<>();
            List<LineInfo> currentParagraph = new ArrayList<>();
            
            for (int i = 0; i < sortedLines.size(); i++) {
                LineInfo currentLine = sortedLines.get(i);
                currentParagraph.add(currentLine);
                
                // Check if this is the end of a paragraph
                boolean isEndOfParagraph = false;
                
                if (i < sortedLines.size() - 1) {
                    LineInfo nextLine = sortedLines.get(i + 1);
                    float gap = currentLine.avgY - nextLine.avgY;
                    float expectedLineSpacing = currentLine.avgFontSize; // Expected single-line spacing
                    
                    // If gap is significantly larger than expected single-line spacing,
                    // it's likely a paragraph break
                    if (gap > expectedLineSpacing * 1.8f) { // 1.8x threshold for paragraph break
                        isEndOfParagraph = true;
                    }
                    
                    // Also check for content-based paragraph indicators
                    if (currentLine.combinedText.endsWith(".") || 
                        currentLine.combinedText.endsWith(":") ||
                        currentLine.combinedText.matches(".*\\d+\\.\\s*$")) { // Numbered lists
                        if (gap > expectedLineSpacing * 1.3f) { // Lower threshold for sentences
                            isEndOfParagraph = true;
                        }
                    }
                } else {
                    // Last line - end of paragraph
                    isEndOfParagraph = true;
                }
                
                if (isEndOfParagraph && !currentParagraph.isEmpty()) {
                    paragraphs.add(new ArrayList<>(currentParagraph));
                    currentParagraph.clear();
                }
            }
            
            return paragraphs;
        }
        
        private ParagraphSpacing analyzeSingleParagraphSpacing(List<LineInfo> paragraphLines) {
            if (paragraphLines.size() < 2) return null;
            
            List<Float> lineGaps = new ArrayList<>();
            float totalFontSize = 0f;
            
            for (int i = 0; i < paragraphLines.size() - 1; i++) {
                LineInfo currentLine = paragraphLines.get(i);
                LineInfo nextLine = paragraphLines.get(i + 1);
                
                float gap = currentLine.avgY - nextLine.avgY;
                if (gap > 0 && gap < 100.0f) { // Reasonable gap range
                    lineGaps.add(gap);
                }
                totalFontSize += currentLine.avgFontSize;
            }
            totalFontSize += paragraphLines.get(paragraphLines.size() - 1).avgFontSize;
            
            if (lineGaps.isEmpty()) return null;
            
            // Calculate metrics for this paragraph
            float avgGap = (float) lineGaps.stream().mapToDouble(Float::doubleValue).average().orElse(0);
            float avgFontSize = totalFontSize / paragraphLines.size();
            float spacingRatio = avgGap / avgFontSize;
            
            ParagraphSpacing ps = new ParagraphSpacing();
            ps.averageLineGap = avgGap;
            ps.averageFontSize = avgFontSize;
            ps.spacingRatio = spacingRatio;
            ps.lineCount = paragraphLines.size();
            ps.isSingleLineSpacing = spacingRatio <= 1.2f;
            
            // Create sample text for identification - fix character spacing issue
            String combinedText = paragraphLines.stream()
                .map(line -> line.combinedText)
                .collect(Collectors.joining(" "));
            
            // Fix spaced characters issue: remove extra spaces between single characters
            // String cleanedText = fixSpacedCharacters(combinedText);
            String cleanedText = combinedText;
            
            ps.sampleText = cleanedText.length() > 100 ? cleanedText.substring(0, 100) + "..." : cleanedText;
            
            return ps;
        }
    }
}