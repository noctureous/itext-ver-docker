import React, { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Box, Typography, Paper, Button } from '@mui/material';
import { validationConfig, getMarginMinimum, isFontFamilyAllowed } from '../config/validationConfig';

// Storage utility functions
const storageUtils = {
  // Estimate the size of data in bytes
  estimateSize: (data) => {
    return new Blob([JSON.stringify(data)]).size;
  },
  
  // Clear all existing storage to free up space
  clearStorage: () => {
    try {
      const keys = Object.keys(sessionStorage);
      keys.forEach(key => {
        if (key.startsWith('pdfAnalysisResult')) {
          sessionStorage.removeItem(key);
        }
      });
    } catch (error) {
      console.warn('Error clearing storage:', error);
    }
  },
  
  // Try to store data with reasonable fallback strategies
  safeSetItem: (key, data) => {
    const originalSize = storageUtils.estimateSize(data);
    const sizeInMB = originalSize / (1024 * 1024);
    
    console.log(`Attempting to store data: ${sizeInMB.toFixed(2)}MB`);
    
    // Strategy 1: Try direct storage for reasonable sized data
    if (sizeInMB < 4) {
      try {
        const dataStr = JSON.stringify(data);
        sessionStorage.setItem(key, dataStr);
        sessionStorage.setItem(key + '_strategy', 'direct');
        return { success: true, strategy: 'direct', size: sizeInMB };
      } catch (error) {
        if (error.name === 'QuotaExceededError') {
          console.warn('Direct storage failed, creating summary');
        } else {
          return { success: false, error };
        }
      }
    }
    
    // Strategy 2: Create paragraph-level summary
    console.log('Creating paragraph-level summary...');
    let summary = storageUtils.createParagraphSummary(data);
    let summarySize = storageUtils.estimateSize(summary);
    
    try {
      storageUtils.clearStorage(); // Clear space first
      sessionStorage.setItem(key, JSON.stringify(summary));
      sessionStorage.setItem(key + '_strategy', 'summary');
      return { success: true, strategy: 'summary', size: summarySize / (1024 * 1024) };
    } catch (error) {
      console.warn('Summary storage failed, creating compact version');
      
      // Strategy 3: Create compact version as last resort
      const compact = storageUtils.createCompactSummary(data);
      try {
        storageUtils.clearStorage();
        sessionStorage.setItem(key, JSON.stringify(compact));
        sessionStorage.setItem(key + '_strategy', 'compact');
        return { success: true, strategy: 'compact', size: storageUtils.estimateSize(compact) / (1024 * 1024) };
      } catch (finalError) {
        console.error('All storage strategies failed:', finalError);
        return { success: false, error: finalError, strategy: 'none' };
      }
    }
  },
  
  // Create a paragraph-level summary (keeps good readability)
  createParagraphSummary: (data) => {
    if (Array.isArray(data)) {
      return data.map(item => storageUtils.createParagraphSummary(item));
    }
    
    return {
      fileName: data.fileName,
      pageCount: Object.keys(data.pageText || {}).length,
      hasImageText: data.imageText && Object.keys(data.imageText).length > 0,
      fontCount: data.fontInfo ? Object.values(data.fontInfo).flat().length : 0,
      // Keep paragraph-level text (500 chars per page - enough for a paragraph)
      pageTextSummary: data.pageText ? Object.fromEntries(
        Object.entries(data.pageText).map(([page, text]) => [
          page, 
          text ? text.substring(0, 500) + (text.length > 500 ? '...' : '') : ''
        ])
      ) : {},
      // Keep substantial font info (200 chars per font, max 30 fonts per page)
      fontInfo: data.fontInfo ? Object.fromEntries(
        Object.entries(data.fontInfo).map(([page, fonts]) => [
          page,
          fonts.slice(0, 30).map(font => ({
            ...font,
            text: font.text ? font.text.substring(0, 200) + (font.text.length > 200 ? '...' : '') : font.text
          }))
        ])
      ) : {},
      margins: data.margins,
      imageText: data.imageText,
      _strategy: 'summary'
    };
  },
  
  // Create compact version (only if paragraph summary fails)
  createCompactSummary: (data) => {
    if (Array.isArray(data)) {
      return data.slice(0, 10).map(item => storageUtils.createCompactSummary(item));
    }
    
    return {
      fileName: data.fileName,
      pageCount: Object.keys(data.pageText || {}).length,
      hasImageText: data.imageText && Object.keys(data.imageText).length > 0,
      fontCount: data.fontInfo ? Object.values(data.fontInfo).flat().length : 0,
      // Keep first 15 pages with 200 chars each (still readable)
      pageTextSummary: data.pageText ? Object.fromEntries(
        Object.entries(data.pageText).slice(0, 15).map(([page, text]) => [
          page, 
          text ? text.substring(0, 200) + '...' : ''
        ])
      ) : {},
      // Keep first 10 fonts per page, 100 chars each
      fontInfo: data.fontInfo ? Object.fromEntries(
        Object.entries(data.fontInfo).slice(0, 15).map(([page, fonts]) => [
          page,
          fonts.slice(0, 10).map(font => ({
            fontName: font.fontName,
            embedded: font.embedded,
            fontSize: font.fontSize,
            text: font.text ? font.text.substring(0, 100) + '...' : font.text
          }))
        ])
      ) : {},
      margins: data.margins ? Object.fromEntries(
        Object.entries(data.margins).slice(0, 15)
      ) : {},
      _strategy: 'compact'
    };
  },
  
  // Get data from storage
  getItem: (key) => {
    try {
      const data = sessionStorage.getItem(key);
      const strategy = sessionStorage.getItem(key + '_strategy') || 'unknown';
      
      if (data) {
        const parsed = JSON.parse(data);
        return { data: parsed, strategy };
      }
      return { data: null, strategy: 'none' };
    } catch (error) {
      console.error('Error parsing stored data:', error);
      storageUtils.clearStorage();
      return { data: null, strategy: 'error' };
    }
  }
};

const AnalysisResults = (props) => {
  const location = useLocation();
  const navigate = useNavigate();
  const [result, setResult] = useState(null);
  const [storageStrategy, setStorageStrategy] = useState('none');
  const [isLoading, setIsLoading] = useState(true);
  
  // Only show Back button at the top level
  const isTopLevel = !props.result;

  // Helper functions for validation (moved to top to fix hoisting issue)
  const validateSpacing = (spacingInfo) => {
    const issues = [];
    
    // Check if spacing is acceptable using configuration
    if (spacingInfo.lineSpacing < validationConfig.spacing.minimumLineGap) {
      issues.push(`Line spacing too tight: ${spacingInfo.lineSpacing?.toFixed(1)} pts < ${validationConfig.spacing.minimumLineGap} pts minimum`);
    }
    
    // Only flag as issue if there are paragraphs with unacceptable spacing
    if (spacingInfo.paragraphDetails && spacingInfo.paragraphDetails.length > 0) {
      const unacceptableParagraphs = spacingInfo.paragraphDetails.filter(p => !p.acceptable);
      if (unacceptableParagraphs.length > 0) {
        issues.push(`${unacceptableParagraphs.length} paragraph(s) have spacing < ${validationConfig.spacing.acceptableThreshold} pts`);
      }
    }
    
    // Check for spacing type issues indicated by backend
    if (spacingInfo.spacingType && spacingInfo.spacingType.includes('Invalid Spacing')) {
      issues.push(`Invalid spacing detected: ${spacingInfo.spacingType}`);
    }
    
    return issues;
  };

  const validateFont = (font) => {
    const issues = [];
    
    // Check font family using configuration
    const isAllowedFont = isFontFamilyAllowed(font.fontName);
    if (!isAllowedFont && font.fontName !== 'OCR-Extracted-From-Image') {
      issues.push('Font not Times New Roman');
    }
    
    // Check font size using configuration
    if (font.fontSize && font.fontSize < validationConfig.fonts.minimumSize) {
      issues.push(`Font size ${font.fontSize}pts < ${validationConfig.fonts.minimumSize}pts`);
    }
    
    return issues;
  };

  const validateMargin = (marginValue, marginType) => {
    const issues = [];
    const minimumMargin = getMarginMinimum(marginType.toLowerCase());
    if (marginValue && marginValue < minimumMargin) {
      issues.push(`${marginType} margin ${marginValue.toFixed(2)}cm < ${minimumMargin}cm`);
    }
    return issues;
  };

  const getValidationStyle = (issues) => {
    if (issues.length > 0) {
      return {
        backgroundColor: '#ffebee',
        border: '2px solid #f44336',
        borderLeft: '4px solid #f44336'
      };
    }
    return {};
  };

  const getValidationBadges = (issues) => {
    if (issues.length === 0) return null;
    
    return (
      <Box sx={{ mt: 1 }}>
        {issues.map((issue, idx) => (
          <Typography
            key={idx}
            component="span"
            sx={{
              display: 'inline-block',
              mr: 1,
              mb: 0.5,
              px: 1,
              py: 0.5,
              backgroundColor: 'error.main',
              color: 'white',
              borderRadius: 1,
              fontSize: '0.7rem',
              fontWeight: 'bold'
            }}
          >
            ⚠️ {issue}
          </Typography>
        ))}
      </Box>
    );
  };

  const evaluateOverallValidation = (result) => {
    if (!result) return { isValid: false, issues: ['No analysis data available'] };

    const issues = [];
    let totalValidationErrors = 0;

    // Check each page for validation issues
    const pages = Object.keys(result.pageText || result.pageTextSummary || {});
    
    pages.forEach(pageNumStr => {
      const pageNum = parseInt(pageNumStr, 10);
      const fonts = result.fontInfo?.[pageNum] || [];
      const marginInfo = result.margins?.[pageNum];
      const spacingInfo = result.spacing?.[pageNum];

      // Validate fonts on this page
      fonts.forEach((font, fontIdx) => {
        const fontIssues = validateFont(font);
        if (fontIssues.length > 0) {
          totalValidationErrors += fontIssues.length;
          issues.push(`Page ${pageNum}, Font ${fontIdx + 1}: ${fontIssues.join(', ')}`);
        }
      });

      // Validate margins on this page
      if (marginInfo) {
        const leftMarginIssues = validateMargin(marginInfo.leftMargin, 'Left');
        const topMarginIssues = validateMargin(marginInfo.topMargin, 'Top');
        
        if (leftMarginIssues.length > 0) {
          totalValidationErrors += leftMarginIssues.length;
          issues.push(`Page ${pageNum}: ${leftMarginIssues.join(', ')}`);
        }
        
        if (topMarginIssues.length > 0) {
          totalValidationErrors += topMarginIssues.length;
          issues.push(`Page ${pageNum}: ${topMarginIssues.join(', ')}`);
        }
      }

      // Validate line spacing on this page
      if (spacingInfo) {
        const spacingIssues = validateSpacing(spacingInfo);
        if (spacingIssues.length > 0) {
          totalValidationErrors += spacingIssues.length;
          issues.push(`Page ${pageNum}: ${spacingIssues.join(', ')}`);
        }
      }
    });

    return {
      isValid: totalValidationErrors === 0 && issues.length === 0,
      issues: issues,
      totalErrors: totalValidationErrors,
      pagesAnalyzed: pages.length
    };
  };

  const ValidationStatusBadge = ({ validation, fileName }) => {
    if (validation.isValid) {
      return (
        <Box sx={{ 
          display: 'inline-flex', 
          alignItems: 'center', 
          ml: 2,
          px: 1.5,
          py: 0.5,
          backgroundColor: 'success.main',
          color: 'white',
          borderRadius: 2,
          fontSize: '0.9rem',
          fontWeight: 'bold',
          boxShadow: 2
        }}>
          <Typography component="span" sx={{ mr: 0.5, fontSize: '1.1rem' }}>✓</Typography>
          <Typography component="span">OK</Typography>
        </Box>
      );
    } else {
      return (
        <Box sx={{ 
          display: 'inline-flex', 
          alignItems: 'center', 
          ml: 2,
          px: 1.5,
          py: 0.5,
          backgroundColor: 'error.main',
          color: 'white',
          borderRadius: 2,
          fontSize: '0.9rem',
          fontWeight: 'bold',
          boxShadow: 2
        }}>
          <Typography component="span" sx={{ mr: 0.5, fontSize: '1.1rem' }}>⚠️</Typography>
          <Typography component="span">{validation.totalErrors} ISSUE{validation.totalErrors !== 1 ? 'S' : ''}</Typography>
        </Box>
      );
    }
  };

  const getFontInfoForPage = (pageNum) => {
    if (!result.fontInfo) return null;
    const fontEntry = Object.entries(result.fontInfo).find(([key]) => key.endsWith(`_Page${pageNum}`) || key === Object.keys(result.fontInfo)[pageNum-1]);
    return fontEntry ? fontEntry[1] : null;
  };

  const containsChineseText = (text) => {
    if (!text) return false;
    return /[\u4e00-\u9fff]/.test(text);
  };

  const renderSpecialText = (text) => {
    if (!text) return text;
    return text.replace(/(WingdingsSymbolCode)/g, '🔤');
  };

  const estimateLineSpacing = (fonts) => {
    const fontGroups = {};
    fonts.forEach(font => {
      const key = `${font.fontName}_${font.fontSize}`;
      if (!fontGroups[key]) fontGroups[key] = [];
      fontGroups[key].push(font);
    });
    
    for (const [key, group] of Object.entries(fontGroups)) {
      if (group.length > 5) {
        const avgTextLength = group.reduce((sum, f) => sum + (f.text?.length || 0), 0) / group.length;
        if (avgTextLength < 20) {
          return 'Possibly tight spacing';
        }
      }
    }
    return null;
  };

  useEffect(() => {
    setIsLoading(true);
    
    let analysisResult = props.result || location.state?.result;
    
    if (analysisResult) {
      const storageResult = storageUtils.safeSetItem('pdfAnalysisResult', analysisResult);
      if (storageResult.success) {
        setResult(analysisResult);
        setStorageStrategy(storageResult.strategy);
        console.log(`Data stored using strategy: ${storageResult.strategy}, size: ${storageResult.size?.toFixed(2)}MB`);
      } else {
        console.error('Failed to store analysis result:', storageResult.error);
        setResult(analysisResult);
        setStorageStrategy('none');
      }
      setIsLoading(false);
    } else {
      const { data: stored, strategy } = storageUtils.getItem('pdfAnalysisResult');
      if (stored) {
        setResult(stored);
        setStorageStrategy(strategy);
        setIsLoading(false);
      } else {
        setTimeout(() => {
          setIsLoading(false);
        }, 1000);
      }
    }
  }, [props.result, location.state?.result]);

  const handleBackClick = () => {
    storageUtils.clearStorage();
    navigate(-1);
  };

  const getStrategyMessage = () => {
    switch (storageStrategy) {
      case 'direct':
        return null;
      case 'summary':
        return '⚠️ Showing paragraph-level summary (500 chars per page, 200 chars per font) due to storage size.';
      case 'compact':
        return '⚠️ Showing compact data (first 15 pages, 200 chars per page) due to storage limitations.';
      case 'none':
        return '❌ Storage failed - data will not persist on page refresh.';
      default:
        return null;
    }
  };

  // Handle the response from multi-file uploads
  if (result && result.results && Array.isArray(result.results)) {
    const { results, errors, totalFiles, successfulScans, failedScans } = result;
    
    return (
      <Box sx={{ mt: 4 }}>
        {isTopLevel && (
          <Button variant="outlined" sx={{ mb: 2 }} onClick={handleBackClick}>
            Back
          </Button>
        )}
        
        <Paper sx={{ p: 3, mb: 3, backgroundColor: 'primary.light', color: 'primary.contrastText' }}>
          <Typography variant="h5" gutterBottom sx={{ fontWeight: 'bold' }}>
            📄 Multiple File Analysis Results
          </Typography>
          <Typography variant="h6">
            Summary: {successfulScans} of {totalFiles} files processed successfully
          </Typography>
        </Paper>
        
        {errors && errors.length > 0 && (
          <Paper sx={{ p: 2, mb: 2, backgroundColor: 'error.light' }}>
            <Typography variant="subtitle1" color="error.dark" sx={{ fontWeight: 'bold', mb: 1 }}>
              ⚠️ {failedScans} Failed Files:
            </Typography>
            {errors.map((error, idx) => (
              <Typography key={idx} variant="body2" color="error.dark" sx={{ mb: 0.5 }}>
                • <strong>{error.fileName}</strong>: {error.message || error.error}
              </Typography>
            ))}
          </Paper>
        )}

        {/* Render successful results */}
        {results.map((res, idx) => {
          const fileValidation = evaluateOverallValidation(res);
          return (
            <Paper sx={{ p: 2, mb: 2 }} key={res.fileName || idx}>
              <Box sx={{ 
                display: 'flex', 
                alignItems: 'center', 
                mb: 2, 
                pb: 1, 
                borderBottom: '1px solid #ddd' 
              }}>
                <Typography variant="h6" sx={{ 
                  fontWeight: 'bold',
                  wordBreak: 'break-word',
                  fontFamily: 'Roboto, Helvetica, Arial, sans-serif'
                }}>
                  📄 {res.fileName || `File ${idx + 1}`}
                </Typography>
                <ValidationStatusBadge validation={fileValidation} fileName={res.fileName} />
              </Box>
              {/* Render individual file analysis without recursion */}
              {renderSingleFileAnalysis(res)}
            </Paper>
          );
        })}
      </Box>
    );
  }

  // If result is an array (directory analysis), render each result
  if (Array.isArray(result)) {
    return (
      <Box sx={{ mt: 4 }}>
        {isTopLevel && (
          <Button variant="outlined" sx={{ mb: 2 }} onClick={handleBackClick}>
            Back
          </Button>
        )}
        {getStrategyMessage() && (
          <Paper sx={{ p: 2, mb: 2, backgroundColor: 
            storageStrategy === 'none' ? 'error.light' : 'warning.light' 
          }}>
            <Typography variant="body2" color={
              storageStrategy === 'none' ? 'error.dark' : 'warning.dark'
            }>
              {getStrategyMessage()}
            </Typography>
          </Paper>
        )}
        
        <Paper sx={{ p: 3, mb: 3, backgroundColor: 'primary.light', color: 'primary.contrastText' }}>
          <Typography variant="h5" gutterBottom sx={{ fontWeight: 'bold' }}>
            📁 Directory Analysis Results
          </Typography>
          <Typography variant="h6">
            {result.length} files analyzed
          </Typography>
        </Paper>
        
        {result.length === 0 && (
          <Typography color="error">No PDF files found in directory.</Typography>
        )}
        {result.map((res, idx) => {
          const fileValidation = evaluateOverallValidation(res);
          return (
            <Paper sx={{ p: 2, mb: 2 }} key={res.fileName || idx}>
              <Box sx={{ 
                display: 'flex', 
                alignItems: 'center', 
                mb: 2, 
                pb: 1, 
                borderBottom: '1px solid #ddd' 
              }}>
                <Typography variant="h6" sx={{ 
                  fontWeight: 'bold',
                  wordBreak: 'break-word',
                  fontFamily: 'Roboto, Helvetica, Arial, sans-serif'
                }}>
                  📄 {res.fileName || `File ${idx + 1}`}
                </Typography>
                <ValidationStatusBadge validation={fileValidation} fileName={res.fileName} />
              </Box>
              {/* Render individual file analysis without recursion */}
              {renderSingleFileAnalysis(res)}
            </Paper>
          );
        })}
      </Box>
    );
  }

  if (!result) {
    // Show loading state while waiting for results
    if (isLoading) {
      return (
        <Box sx={{ mt: 4, p: 2 }}>
          {isTopLevel && (
            <Button variant="outlined" sx={{ mb: 2 }} onClick={handleBackClick}>
              Back to Previous Page
            </Button>
          )}
          <Paper sx={{ p: 4, textAlign: 'center', backgroundColor: '#f5f5f5' }}>
            <Typography variant="h5" color="primary.main" gutterBottom>
              🔄 Loading Analysis Results...
            </Typography>
            <Typography variant="body1" color="text.secondary">
              Please wait while we retrieve your PDF analysis data.
            </Typography>
          </Paper>
        </Box>
      );
    }
    
    // Only show "no results" after loading is complete
    return (
      <Box sx={{ mt: 4, p: 2 }}>
        {isTopLevel && (
          <Button variant="outlined" sx={{ mb: 2 }} onClick={handleBackClick}>
            Back to Previous Page
          </Button>
        )}
        <Paper sx={{ p: 4, textAlign: 'center', backgroundColor: '#f5f5f5' }}>
          <Typography variant="h4" color="warning.main" gutterBottom>
            ⚠️ No Analysis Results
          </Typography>
          <Typography variant="h6" color="error" gutterBottom>
            No PDF analysis data found
          </Typography>
          <Typography variant="body1" color="text.secondary" sx={{ mb: 3 }}>
            To view analysis results, you need to first upload and analyze a PDF file.
          </Typography>
          <Box sx={{ display: 'flex', gap: 2, justifyContent: 'center', flexWrap: 'wrap' }}>
            <Button variant="contained" color="primary" onClick={() => navigate('/')}>
              📤 Upload New PDF
            </Button>
            <Button variant="outlined" color="secondary" onClick={() => {
              const { data: stored, strategy } = storageUtils.getItem('pdfAnalysisResult');
              const sizeInfo = stored ? `${(storageUtils.estimateSize(stored) / 1024).toFixed(1)}KB` : 'No data';
              console.log('SessionStorage content:', stored);
              alert(`SessionStorage: ${stored ? 'Has data' : 'Empty'} (${sizeInfo})\nStrategy: ${strategy}\nLocation state: ${location.state ? 'Has data' : 'Empty'}`);
            }}>
              🔍 Debug Info
            </Button>
          </Box>
          <Typography variant="caption" color="text.disabled" sx={{ mt: 3, display: 'block' }}>
            Tip: Analysis results are only available after uploading and processing a PDF file
          </Typography>
        </Paper>
      </Box>
    );
  }

  // Single file result - render directly
  return (
    <Box sx={{ mt: 4 }}>
      {isTopLevel && (
        <>
          <Button variant="outlined" sx={{ mb: 2 }} onClick={handleBackClick}>
            Back
          </Button>
          {getStrategyMessage() && (
            <Paper sx={{ p: 2, mb: 2, backgroundColor: 
              storageStrategy === 'none' ? 'error.light' : 'warning.light' 
            }}>
              <Typography variant="body2" color={
                storageStrategy === 'none' ? 'error.dark' : 'warning.dark'
              }>
                {getStrategyMessage()}
                {storageStrategy !== 'direct' && storageStrategy !== 'none' && (
                  <Typography component="span" sx={{ display: 'block', mt: 1, fontSize: '0.8rem' }}>
                    💡 Tip: For full data, try uploading smaller PDFs or analyzing fewer pages at once.
                  </Typography>
                )}
              </Typography>
            </Paper>
          )}
          <Paper sx={{ p: 3, mb: 3, backgroundColor: 'primary.light', color: 'primary.contrastText' }}>
            <Typography variant="h5" gutterBottom sx={{ fontWeight: 'bold' }}>
              📄 Analysis Results
            </Typography>
            <Box sx={{ 
              display: 'flex', 
              alignItems: 'center', 
              flexWrap: 'wrap',
              gap: 1
            }}>
              <Typography variant="h6" sx={{ 
                wordBreak: 'break-word',
                backgroundColor: 'rgba(255,255,255,0.1)',
                padding: 1,
                borderRadius: 1,
                fontFamily: 'Roboto, Helvetica, Arial, sans-serif'
              }}>
                File: {result.fileName || 'Unknown'}
              </Typography>
              <ValidationStatusBadge validation={evaluateOverallValidation(result)} fileName={result.fileName} />
            </Box>
            {result._strategy && (
              <Typography variant="caption" sx={{ 
                display: 'block', 
                mt: 1, 
                opacity: 0.8,
                fontStyle: 'italic'
              }}>
                Data level: {result._strategy}
              </Typography>
            )}
          </Paper>
        </>
      )}
      
      {renderSingleFileAnalysis(result)}
    </Box>
  );

  // Helper function to render analysis for a single file (prevents recursion)
  function renderSingleFileAnalysis(fileResult) {
    // Add debug logging for image data
    console.log('=== FRONTEND IMAGE DEBUG ===');
    console.log('FileResult imageText:', fileResult.imageText);
    console.log('Type of imageText:', typeof fileResult.imageText);
    console.log('ImageText keys:', fileResult.imageText ? Object.keys(fileResult.imageText) : 'null');
    
    if (fileResult.imageText) {
      for (const [pageNum, images] of Object.entries(fileResult.imageText)) {
        console.log(`Page ${pageNum} images:`, images);
        console.log(`Page ${pageNum} image count:`, images ? images.length : 0);
      }
    }
    
    // Add font debug logging for OCR fonts
    console.log('=== FRONTEND FONT DEBUG ===');
    if (fileResult.fontInfo) {
      for (const [pageNum, fonts] of Object.entries(fileResult.fontInfo)) {
        const ocrFonts = fonts.filter(f => f.fontName && (f.fontName.includes('OCR') || f.fontName.includes('Image')));
        if (ocrFonts.length > 0) {
          console.log(`Page ${pageNum} OCR/Image fonts:`, ocrFonts);
        }
      }
    }
    
    if (storageStrategy === 'ultra_minimal') {
      return (
        <Paper sx={{ p: 3, mb: 2, backgroundColor: '#f8f9fa' }}>
          <Typography variant="h6" gutterBottom color="primary">
            📊 Document Overview (Metadata Only)
          </Typography>
          <Typography><strong>Total Pages:</strong> {fileResult.pageCount}</Typography>
          <Typography><strong>Total Fonts:</strong> {fileResult.fontCount}</Typography>
          <Typography><strong>Has Image Text:</strong> {fileResult.hasImageText ? 'Yes' : 'No'}</Typography>
          {fileResult.firstPagePreview && (
            <Typography sx={{ mt: 2 }}>
              <strong>First Page Preview:</strong><br />
              <Box component="span" sx={{ 
                fontFamily: 'monospace', 
                backgroundColor: '#e9ecef', 
                p: 1, 
                borderRadius: 1,
                display: 'block',
                mt: 1
              }}>
                {fileResult.firstPagePreview}
              </Box>
            </Typography>
          )}
          {fileResult.fontNames && fileResult.fontNames.length > 0 && (
            <Typography sx={{ mt: 2 }}>
              <strong>Font Names Found:</strong><br />
              {fileResult.fontNames.map((fontName, idx) => (
                <Box key={idx} component="span" sx={{ 
                  display: 'inline-block',
                  backgroundColor: 'primary.light', 
                  color: 'white',
                  px: 1, 
                  py: 0.5, 
                  borderRadius: 1, 
                  fontSize: '0.8rem',
                  mr: 1,
                  mb: 1
                }}>
                  {fontName.split('+').pop()}
                </Box>
              ))}
            </Typography>
          )}
        </Paper>
      );
    }

    // Get all pages from multiple sources to handle pure image PDFs
    const textPages = Object.keys(fileResult.pageText || {});
    const summaryPages = Object.keys(fileResult.pageTextSummary || {});
    const imagePages = Object.keys(fileResult.imageText || {});
    const marginPages = Object.keys(fileResult.margins || {});
    const fontPages = Object.keys(fileResult.fontInfo || {});
    
    // Combine all page numbers and ensure we show pages even if they only have images
    const allPageNumbers = new Set([
      ...textPages.map(p => parseInt(p, 10)),
      ...summaryPages.map(p => parseInt(p, 10)),
      ...imagePages.map(p => parseInt(p, 10)),
      ...marginPages.map(p => parseInt(p, 10)),
      ...fontPages.map(p => parseInt(p, 10))
    ]);
    
    // If no pages found in any data structure, default to page 1
    if (allPageNumbers.size === 0) {
      allPageNumbers.add(1);
    }
    
    const pages = Array.from(allPageNumbers).sort((a, b) => a - b);
    
    return (
      <>
        {pages.map((pageNum) => {
          const fonts = fileResult.fontInfo?.[pageNum] || [];
          const marginInfo = fileResult.margins?.[pageNum];
          const spacingInfo = fileResult.spacing?.[pageNum];
          const text = fileResult.pageText?.[pageNum] || fileResult.pageTextSummary?.[pageNum];
          const imageTexts = fileResult.imageText?.[pageNum] || [];
          
          // Check if this page has only images (no regular text content)
          const hasRegularText = text && text.trim().length > 0;
          const hasImages = imageTexts.length > 0;
          const hasFonts = fonts.length > 0;
          const hasMargins = marginInfo != null;
          const hasAnyContent = hasRegularText || hasImages || hasFonts || hasMargins;
          const isPureImagePage = hasImages && !hasRegularText;
          
          // Always show pages that have any content, including pure image pages
          if (!hasAnyContent) {
            // If absolutely no content, show a minimal page with explanation
            return (
              <Paper sx={{ p: 2, mb: 2 }} key={pageNum}>
                <Typography variant="subtitle1" gutterBottom>
                  Page {pageNum} Analysis:
                </Typography>
                <Box sx={{ 
                  p: 2, 
                  backgroundColor: 'grey.100',
                  borderRadius: 1,
                  textAlign: 'center'
                }}>
                  <Typography variant="body2" sx={{ color: 'text.secondary', fontStyle: 'italic' }}>
                    No content detected on this page. This may indicate a blank page or processing limitations.
                  </Typography>
                </Box>
              </Paper>
            );
          }
          
          return (
            <Paper sx={{ p: 2, mb: 2 }} key={pageNum}>
              <Typography variant="subtitle1" gutterBottom>
                Page {pageNum} Analysis:
                {isPureImagePage && (
                  <Typography component="span" sx={{ 
                    ml: 2, 
                    px: 1.5, 
                    py: 0.5, 
                    backgroundColor: 'info.main', 
                    color: 'white', 
                    borderRadius: 1, 
                    fontSize: '0.8rem',
                    fontWeight: 'bold'
                  }}>
                    📷 PURE IMAGE PAGE
                  </Typography>
                )}
              </Typography>
              
              {/* PROMINENT IMAGE CONTENT SECTION - Always show if images detected */}
              {imageTexts.length > 0 && (
                <Box sx={{ 
                  mb: 3, 
                  p: 3, 
                  backgroundColor: 'primary.light',
                  borderRadius: 2,
                  border: '3px solid',
                  borderColor: 'primary.main',
                  boxShadow: 3
                }}>
                  <Typography variant="h6" gutterBottom sx={{ 
                    color: 'primary.dark', 
                    fontWeight: 'bold',
                    display: 'flex',
                    alignItems: 'center',
                    gap: 1,
                    mb: 2
                  }}>
                    <Box component="span" sx={{ 
                      backgroundColor: 'primary.main',
                      color: 'white',
                      borderRadius: '50%',
                      width: 40,
                      height: 40,
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      fontSize: '1.5rem'
                    }}>
                      📷
                    </Box>
                    🔍 IMAGE CONTENT DETECTED
                  </Typography>
                  
                  <Box sx={{ 
                    mb: 2, 
                    p: 2, 
                    backgroundColor: 'rgba(255,255,255,0.8)',
                    borderRadius: 1
                  }}>
                    <Typography variant="body1" sx={{ color: 'primary.dark', fontWeight: 'bold', mb: 1 }}>
                      📊 This page contains {imageTexts.length} image{imageTexts.length > 1 ? 's' : ''}:
                    </Typography>
                    <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1 }}>
                      <Box sx={{ 
                        px: 2, 
                        py: 1, 
                        backgroundColor: 'success.main', 
                        color: 'white', 
                        borderRadius: 1, 
                        fontWeight: 'bold'
                      }}>
                        📝 {imageTexts.filter(img => img && img !== '[IMAGE DETECTED - NO TEXT]' && img.trim().length > 0).length} with text
                      </Box>
                      <Box sx={{ 
                        px: 2, 
                        py: 1, 
                        backgroundColor: 'warning.main', 
                        color: 'white', 
                        borderRadius: 1, 
                        fontWeight: 'bold'
                      }}>
                        🖼️ {imageTexts.filter(img => img === '[IMAGE DETECTED - NO TEXT]').length} graphics only
                      </Box>
                      {imageTexts.some(img => containsChineseText(img)) && (
                        <Box sx={{ 
                          px: 2, 
                          py: 1, 
                          backgroundColor: 'info.main', 
                          color: 'white', 
                          borderRadius: 1, 
                          fontWeight: 'bold'
                        }}>
                          🇨🇳 Chinese text found
                        </Box>
                      )}
                    </Box>
                  </Box>

                  {/* Show extracted text content prominently */}
                  {imageTexts.map((imageText, idx) => {
                    const isNoTextDetected = imageText === '[IMAGE DETECTED - NO TEXT]';
                    const hasText = imageText && imageText !== '[IMAGE DETECTED - NO TEXT]' && imageText.trim().length > 0;
                    
                    return (
                      <Box key={idx} sx={{ 
                        mb: 2, 
                        p: 2, 
                        backgroundColor: hasText ? 'success.light' : 'warning.light',
                        borderRadius: 1,
                        border: '2px solid',
                        borderColor: hasText ? 'success.main' : 'warning.main'
                      }}>
                        <Typography variant="subtitle2" sx={{ 
                          fontWeight: 'bold', 
                          mb: 1,
                          color: hasText ? 'success.dark' : 'warning.dark'
                        }}>
                          🖼️ Image {idx + 1} Content:
                        </Typography>
                        
                        {hasText ? (
                          <Box sx={{ 
                            p: 1.5, 
                            backgroundColor: 'rgba(255,255,255,0.9)',
                            borderRadius: 1,
                            fontFamily: 'monospace',
                            fontSize: '1rem',
                            lineHeight: 1.4,
                            border: '1px solid #ddd'
                          }}>
                            <Typography component="pre" sx={{ margin: 0, whiteSpace: 'pre-wrap' }}>
                              {imageText}
                            </Typography>
                          </Box>
                        ) : (
                          <Typography sx={{ 
                            fontStyle: 'italic', 
                            color: 'warning.dark',
                            backgroundColor: 'rgba(255,255,255,0.9)',
                            p: 1.5,
                            borderRadius: 1
                          }}>
                            📄 This image contains graphics, charts, or diagrams but no readable text content.
                          </Typography>
                        )}
                      </Box>
                    );
                  })}
                </Box>
              )}
              
              {/* Show special notice for pure image pages */}
              {isPureImagePage && (
                <Box sx={{ 
                  p: 2, 
                  mb: 2, 
                  backgroundColor: 'info.light',
                  borderRadius: 1,
                  border: '2px solid',
                  borderColor: 'info.main'
                }}>
                  <Typography variant="body2" sx={{ fontWeight: 'bold', color: 'info.dark', mb: 1 }}>
                    📷 Pure Image Page Detected
                  </Typography>
                  <Typography variant="body2" sx={{ color: 'info.dark' }}>
                    This page contains only images with no regular text content. 
                    All {imageTexts.length} image{imageTexts.length > 1 ? 's' : ''} have been detected and processed for OCR analysis.
                    {marginInfo ? ' Margins have been calculated based on image placement.' : ' Margin analysis may be limited without text content.'}
                  </Typography>
                </Box>
              )}
              
              {/* Font Analysis */}
              <Typography variant="subtitle2" gutterBottom sx={{ mt: 2 }}>
                Fonts ({fonts.length}):
              </Typography>
              {fonts.length > 0 ? (
                fonts.map((font, idx) => {
                  const fontIssues = validateFont(font);
                  const validationStyle = getValidationStyle(fontIssues);
                  
                  return (
                    <Box key={idx} sx={{ 
                      mb: 1, 
                      p: 1,
                      border: '1px dashed #ccc',
                      backgroundColor: font.fontName === 'OCR-Extracted-From-Image' ? '#e3f2fd' : 
                                     font.fontName?.includes('Image-Detected-No-Text') ? '#fff3e0' :
                                     font.fontName?.includes('Wingdings') ? '#fff3e0' : 'transparent',
                      ...validationStyle
                    }}>
                      <Typography>
                        <strong>Text:</strong> {renderSpecialText(font.text) || '-'}
                      </Typography>
                      <Typography>
                        <strong>Font Name:</strong> {font.fontName ? font.fontName.split('+').pop() : '-'}
                        {font.fontName === 'OCR-Extracted-From-Image' && (
                          <Typography component="span" sx={{ 
                            ml: 1, 
                            px: 1, 
                            py: 0.5, 
                            backgroundColor: 'info.main', 
                            color: 'white', 
                            borderRadius: 1, 
                            fontSize: '0.7rem' 
                          }}>
                            📷 FROM IMAGE
                          </Typography>
                        )}
                        {font.fontName?.includes('Image-Detected-No-Text') && (
                          <Typography component="span" sx={{ 
                            ml: 1, 
                            px: 1, 
                            py: 0.5, 
                            backgroundColor: 'warning.main', 
                            color: 'white', 
                            borderRadius: 1, 
                            fontSize: '0.7rem' 
                          }}>
                            📷 IMAGE DETECTED
                          </Typography>
                        )}
                        {font.fontName?.includes('Wingdings') && (
                          <Typography component="span" sx={{ 
                            ml: 1, 
                            px: 1, 
                            py: 0.5, 
                            backgroundColor: 'warning.main', 
                            color: 'white', 
                            borderRadius: 1, 
                            fontSize: '0.7rem' 
                          }}>
                            🔣 SYMBOLS
                          </Typography>
                        )}
                        {containsChineseText(font.text) && (
                          <Typography component="span" sx={{ 
                            ml: 1, 
                            px: 1, 
                            py: 0.5, 
                            backgroundColor: 'success.main', 
                            color: 'white', 
                            borderRadius: 1, 
                            fontSize: '0.7rem' 
                          }}>
                            🇨🇳 CHINESE
                          </Typography>
                        )}
                      </Typography>
                      <Typography>
                        <strong>Is Embedded:</strong> {font.embedded !== undefined ? String(font.embedded) : '-'}
                      </Typography>
                      <Typography>
                        <strong>Font Size:</strong> {font.fontSize ? `${font.fontSize} pts` : '-'}
                      </Typography>
                      {getValidationBadges(fontIssues)}
                    </Box>
                  );
                })
              ) : isPureImagePage ? (
                <Box sx={{ 
                  p: 2, 
                  backgroundColor: 'warning.light',
                  borderRadius: 1,
                  border: '1px solid',
                  borderColor: 'warning.main'
                }}>
                  <Typography variant="body2" sx={{ color: 'warning.dark', fontWeight: 'bold' }}>
                    ℹ️ No Font Analysis Available
                  </Typography>
                  <Typography variant="body2" sx={{ color: 'warning.dark', mt: 0.5 }}>
                    This page contains only images. Font analysis is not applicable as there is no text content with fonts to analyze.
                    See the Image Analysis section below for details about the detected images.
                  </Typography>
                </Box>
              ) : (
                <Typography>No fonts found on this page.</Typography>
              )}
              
              {/* Display extracted image texts separately with enhanced formatting */}
              {imageTexts.length > 0 && (
                <Box sx={{ mt: 3 }}>
                  <Typography variant="subtitle2" gutterBottom sx={{ 
                    color: 'primary.main', 
                    fontWeight: 'bold',
                    display: 'flex',
                    alignItems: 'center',
                    gap: 1
                  }}>
                    <Box component="span" sx={{ 
                      backgroundColor: 'primary.main',
                      color: 'white',
                      borderRadius: '50%',
                      width: 32,
                      height: 32,
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      fontSize: '1.2rem'
                    }}>
                      📷
                    </Box>
                    Image Analysis Results ({imageTexts.length} image{imageTexts.length > 1 ? 's' : ''} detected)
                  </Typography>
                  
                  {/* Quick Overview Card */}
                  <Box sx={{ 
                    mb: 2, 
                    p: 2, 
                    backgroundColor: 'primary.light',
                    borderRadius: 2,
                    border: '2px solid',
                    borderColor: 'primary.main'
                  }}>
                    <Typography variant="body2" sx={{ color: 'primary.dark', fontWeight: 'bold', mb: 1 }}>
                      📊 Quick Overview
                    </Typography>
                    <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: 2 }}>
                      <Box>
                        <Typography variant="body2" sx={{ color: 'primary.dark' }}>
                          <strong>Total Images:</strong> {imageTexts.length}
                        </Typography>
                      </Box>
                      <Box>
                        <Typography variant="body2" sx={{ color: 'primary.dark' }}>
                          <strong>With Text:</strong> {imageTexts.filter(img => img && img !== '[IMAGE DETECTED - NO TEXT]' && img.trim().length > 0).length}
                        </Typography>
                      </Box>
                      <Box>
                        <Typography variant="body2" sx={{ color: 'primary.dark' }}>
                          <strong>Graphics Only:</strong> {imageTexts.filter(img => img === '[IMAGE DETECTED - NO TEXT]').length}
                        </Typography>
                      </Box>
                      <Box>
                        <Typography variant="body2" sx={{ color: 'primary.dark' }}>
                          <strong>OCR Status:</strong> {imageTexts.some(img => img && img !== '[IMAGE DETECTED - NO TEXT]' && img.trim().length > 0) ? 'Success' : 'No Text Found'}
                        </Typography>
                      </Box>
                    </Box>
                  </Box>

                  {/* Individual Image Results */}
                  {imageTexts.map((imageText, idx) => {
                    const isNoTextDetected = imageText === '[IMAGE DETECTED - NO TEXT]';
                    const isEmpty = !imageText || imageText.trim().length === 0;
                    const hasChineseText = containsChineseText(imageText);
                    const textLength = imageText ? imageText.length : 0;
                    
                    return (
                      <Paper key={idx} sx={{ 
                        p: 2, 
                        mb: 2, 
                        backgroundColor: isNoTextDetected ? '#fff3e0' : '#f8f9fa',
                        border: '2px solid',
                        borderColor: isNoTextDetected ? '#ff9800' : '#007bff',
                        borderLeft: '4px solid',
                        borderLeftColor: isNoTextDetected ? '#ff9800' : '#007bff'
                      }}>
                        <Typography variant="body2" sx={{ 
                          fontFamily: 'monospace',
                          whiteSpace: 'pre-wrap',
                          wordBreak: 'break-word'
                        }}>
                          <Box sx={{ display: 'flex', alignItems: 'center', mb: 1, flexWrap: 'wrap', gap: 1 }}>
                            <Typography component="span" sx={{ 
                              fontWeight: 'bold', 
                              fontSize: '1.1rem',
                              color: isNoTextDetected ? 'warning.dark' : 'primary.dark'
                            }}>
                              🖼️ Image {idx + 1}
                            </Typography>
                            
                            {/* Status Badge */}
                            {isNoTextDetected && (
                              <Typography component="span" sx={{ 
                                px: 1, 
                                py: 0.5, 
                                backgroundColor: 'warning.main', 
                                color: 'white', 
                                borderRadius: 1, 
                                fontSize: '0.7rem',
                                fontWeight: 'bold'
                              }}>
                                📷 NO TEXT CONTENT
                              </Typography>
                            )}
                            {!isNoTextDetected && !isEmpty && (
                              <Typography component="span" sx={{ 
                                px: 1, 
                                py: 0.5, 
                                backgroundColor: 'success.main', 
                                color: 'white', 
                                borderRadius: 1, 
                                fontSize: '0.7rem',
                                fontWeight: 'bold'
                              }}>
                                ✓ TEXT EXTRACTED
                              </Typography>
                            )}
                            
                            {/* Language Badge */}
                            {hasChineseText && (
                              <Typography component="span" sx={{ 
                                px: 1, 
                                py: 0.5, 
                                backgroundColor: 'info.main', 
                                color: 'white', 
                                borderRadius: 1, 
                                fontSize: '0.7rem',
                                fontWeight: 'bold'
                              }}>
                                🇨🇳 CHINESE
                              </Typography>
                            )}
                            
                            {/* Text Length Badge */}
                            {textLength > 0 && !isNoTextDetected && (
                              <Typography component="span" sx={{ 
                                px: 1, 
                                py: 0.5, 
                                backgroundColor: 'secondary.main', 
                                color: 'white', 
                                borderRadius: 1, 
                                fontSize: '0.7rem',
                                fontWeight: 'bold'
                              }}>
                                📝 {textLength} chars
                              </Typography>
                            )}
                          </Box>
                          
                          {/* Image Content */}
                          <Box sx={{ 
                            p: 1.5, 
                            backgroundColor: 'rgba(0,0,0,0.05)',
                            borderRadius: 1,
                            border: '1px solid #ddd'
                          }}>
                            {isNoTextDetected ? (
                              <Typography component="span" sx={{ 
                                color: 'warning.dark',
                                fontStyle: 'italic'
                              }}>
                                🖼️ This image was detected and processed, but contains no extractable text content. 
                                It may be a purely graphical image, diagram, chart, or photo without readable text.
                              </Typography>
                            ) : (
                              <Typography component="span" sx={{ 
                                fontFamily: 'monospace',
                                fontSize: '0.9rem',
                                lineHeight: 1.4
                              }}>
                                {imageText || 'No text detected in this image'}
                              </Typography>
                            )}
                          </Box>
                        </Typography>
                      </Paper>
                    );
                  })}
                  
                  {/* Enhanced Summary of image detection */}
                  <Box sx={{ 
                    mt: 2, 
                    p: 2, 
                    backgroundColor: 'info.light',
                    borderRadius: 2,
                    border: '1px solid',
                    borderColor: 'info.main'
                  }}>
                    <Typography variant="body1" sx={{ color: 'info.dark', fontWeight: 'bold', mb: 1 }}>
                      📊 Image Analysis Summary
                    </Typography>
                    <Typography variant="body2" sx={{ color: 'info.dark', mb: 1 }}>
                      • <strong>Total images detected:</strong> {imageTexts.length}
                    </Typography>
                    <Typography variant="body2" sx={{ color: 'info.dark', mb: 1 }}>
                      • <strong>Images with extractable text:</strong> {imageTexts.filter(img => img && img !== '[IMAGE DETECTED - NO TEXT]' && img.trim().length > 0).length}
                    </Typography>
                    <Typography variant="body2" sx={{ color: 'info.dark', mb: 1 }}>
                      • <strong>Graphical images (no text):</strong> {imageTexts.filter(img => img === '[IMAGE DETECTED - NO TEXT]').length}
                    </Typography>
                    <Typography variant="body2" sx={{ color: 'info.dark', mb: 1 }}>
                      • <strong>Chinese text detected:</strong> {imageTexts.filter(img => containsChineseText(img)).length > 0 ? 'Yes' : 'No'}
                    </Typography>
                    <Typography variant="body2" sx={{ color: 'info.dark', fontWeight: 'bold', mt: 2 }}>
                      🔍 Analysis Note: All images have been processed and analyzed using OCR technology, regardless of whether they contain readable text.
                    </Typography>
                  </Box>
                </Box>
              )}
              
              {/* Margin Information with enhanced display */}
              {marginInfo && (
                <Box sx={{ mt: 2, mb: 2 }}>
                  <Typography variant="subtitle2" gutterBottom>
                    Page Margins:
                  </Typography>
                  <Box sx={{ 
                    p: 2,
                    backgroundColor: isPureImagePage ? 'warning.light' : 'background.paper',
                    borderRadius: 1,
                    border: '1px solid #ddd'
                  }}>
                    {isPureImagePage && (
                      <Typography variant="caption" sx={{ 
                        display: 'block', 
                        mb: 1, 
                        color: 'warning.dark',
                        fontStyle: 'italic'
                      }}>
                        Note: Margins calculated based on image placement for pure image page
                      </Typography>
                    )}
                    
                    <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: 2 }}>
                      {[
                        { label: 'Left', value: marginInfo.leftMargin, validate: true },
                        { label: 'Top', value: marginInfo.topMargin, validate: true },
                        { label: 'Right', value: marginInfo.rightMargin, validate: false },
                        { label: 'Bottom', value: marginInfo.bottomMargin, validate: false }
                      ].map(({ label, value, validate }) => {
                        const marginIssues = validate ? validateMargin(value, label) : [];
                        const marginStyle = getValidationStyle(marginIssues);
                        
                        return (
                          <Box key={label} sx={{ 
                            p: 1,
                            borderRadius: 1,
                            border: '1px solid #ddd',
                            ...marginStyle
                          }}>
                            <Typography component="span" sx={{ fontWeight: 'bold' }}>
                              {label} Margin:
                            </Typography>
                            <Typography component="span" sx={{ ml: 1 }}>
                              {value !== undefined && value !== null ? `${value.toFixed(2)} cm` : 'N/A'}
                            </Typography>
                            {getValidationBadges(marginIssues)}
                          </Box>
                        );
                      })}
                    </Box>
                  </Box>
                </Box>
              )}
              
              {/* Show message if no margin info available */}
              {!marginInfo && isPureImagePage && (
                <Box sx={{ mt: 2, mb: 2 }}>
                  <Typography variant="subtitle2" gutterBottom>
                    Page Margins:
                  </Typography>
                  <Box sx={{ 
                    p: 2, 
                    backgroundColor: 'warning.light',
                    borderRadius: 1,
                    border: '2px solid',
                    borderColor: 'warning.main'
                  }}>
                    <Typography variant="body2" sx={{ color: 'warning.dark', fontWeight: 'bold' }}>
                      ⚠️ Margin Analysis Unavailable
                    </Typography>
                    <Typography variant="body2" sx={{ color: 'warning.dark', mt: 0.5 }}>
                      This page contains only images without text content. 
                      Margin analysis requires text boundaries for accurate calculation.
                    </Typography>
                  </Box>
                </Box>
              )}
              
              {/* Line Spacing Information */}
              {spacingInfo && (
                <Box sx={{ mt: 2, mb: 2 }}>
                  <Typography variant="subtitle2" gutterBottom>
                    Line Spacing Analysis:
                  </Typography>
                  {(() => {
                    const spacingIssues = validateSpacing(spacingInfo);
                    const spacingStyle = getValidationStyle(spacingIssues);
                    
                    return (
                      <Box sx={{ 
                        p: 2,
                        borderRadius: 1,
                        border: '1px solid #ddd',
                        ...spacingStyle
                      }}>
                        <Typography variant="body1" sx={{ fontWeight: 'bold', mb: 1 }}>
                          Overall Result: {spacingInfo.spacingType}
                          {spacingInfo.isSingleLineSpacing ? (
                            <Typography component="span" sx={{ 
                              ml: 1, 
                              px: 1, 
                              py: 0.5, 
                              backgroundColor: 'success.main', 
                              color: 'white', 
                              borderRadius: 1, 
                              fontSize: '0.8rem',
                              fontWeight: 'bold'
                            }}>
                              ✓ ALL PARAGRAPHS SINGLE-LINE
                            </Typography>
                          ) : (
                            <Typography component="span" sx={{ 
                              ml: 1, 
                              px: 1, 
                              py: 0.5, 
                              backgroundColor: 'error.main', 
                              color: 'white', 
                              borderRadius: 1, 
                              fontSize: '0.8rem',
                              fontWeight: 'bold'
                            }}>
                              ⚠️ SOME PARAGRAPHS NON-SINGLE-LINE
                            </Typography>
                          )}
                        </Typography>
                        
                        <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: 1, mt: 1 }}>
                          <Typography variant="body2">
                            <strong>Average Spacing Ratio:</strong> {spacingInfo.spacingRatio?.toFixed(2)}x
                          </Typography>
                          <Typography variant="body2">
                            <strong>Average Line Gap:</strong> {spacingInfo.lineSpacing?.toFixed(1)} pts
                          </Typography>
                        </Box>
                        
                        {spacingInfo.spacingType?.includes('Invalid Spacing') && spacingInfo.paragraphDetails && (
                          <Box sx={{ 
                            mt: 2, 
                            p: 2, 
                            backgroundColor: 'error.light',
                            borderRadius: 1,
                            borderColor: 'error.main'
                          }}>
                            <Typography variant="body2" sx={{ fontWeight: 'bold', color: 'error.dark', mb: 2 }}>
                              📋 Problematic Paragraphs Details:
                            </Typography>
                            
                            {spacingInfo.paragraphDetails.map((paragraph, idx) => (
                              <Box 
                                key={idx} 
                                sx={{ 
                                  mb: 2, 
                                  p: 1.5,
                                  borderRadius: 1,
                                  backgroundColor: paragraph.acceptable ? 'success.light' : 'error.light',
                                  borderColor: paragraph.acceptable ? 'success.main' : 'error.main'
                                }}
                              >
                                <Typography variant="subtitle2" sx={{ 
                                  fontWeight: 'bold', 
                                  color: paragraph.acceptable ? 'success.dark' : 'error.dark',
                                  mb: 1
                                }}>
                                  Paragraph #{paragraph.paragraphNumber}
                                  {paragraph.acceptable ? (
                                    <Typography component="span" sx={{ 
                                      ml: 1, 
                                      px: 1, 
                                      py: 0.5, 
                                      backgroundColor: 'success.main', 
                                      color: 'white', 
                                      borderRadius: 1, 
                                      fontSize: '0.7rem' 
                                    }}>
                                      ✓ OK
                                    </Typography>
                                  ) : (
                                    <Typography component="span" sx={{ 
                                      ml: 1, 
                                      px: 1, 
                                      py: 0.5, 
                                      backgroundColor: 'error.main', 
                                      color: 'white', 
                                      borderRadius: 1, 
                                      fontSize: '0.7rem' 
                                    }}>
                                      ⚠️ TOO TIGHT
                                    </Typography>
                                  )}
                                </Typography>
                                
                                <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: 1, mb: 1 }}>
                                  <Typography variant="body2" sx={{ color: paragraph.acceptable ? 'success.dark' : 'error.dark' }}>
                                    <strong>Line Gap:</strong> {paragraph.lineGap?.toFixed(1)} pts
                                  </Typography>
                                  <Typography variant="body2" sx={{ color: paragraph.acceptable ? 'success.dark' : 'error.dark' }}>
                                    <strong>Lines:</strong> {paragraph.lineCount}
                                  </Typography>
                                  <Typography variant="body2" sx={{ color: paragraph.acceptable ? 'success.dark' : 'error.dark' }}>
                                    <strong>Ratio:</strong> {paragraph.spacingRatio?.toFixed(2)}x
                                  </Typography>
                                </Box>
                                
                                <Typography variant="body2" sx={{ 
                                  fontFamily: 'monospace',
                                  backgroundColor: 'rgba(0,0,0,0.05)',
                                  p: 1,
                                  borderRadius: 0.5,
                                  color: paragraph.acceptable ? 'success.dark' : 'error.dark'
                                }}>
                                  <strong>Sample Text:</strong> {paragraph.sampleText || 'No text available'}
                                </Typography>
                              </Box>
                            ))}
                            
                            <Typography variant="caption" sx={{ color: 'error.dark', mt: 1, display: 'block' }}>
                              📏 Requirement: All paragraphs must have line gaps ≥ 12 points to pass validation.
                            </Typography>
                          </Box>
                        )}
                        
                        {spacingInfo.spacingType?.includes('Mixed Spacing') && (
                          <Box sx={{ 
                            mt: 2, 
                            p: 1, 
                            backgroundColor: 'warning.light',
                            borderRadius: 1,
                            borderColor: 'warning.main'
                          }}>
                            <Typography variant="body2" sx={{ fontWeight: 'bold', color: 'warning.dark' }}>
                              📋 Paragraph Analysis Details:
                            </Typography>
                            <Typography variant="body2" sx={{ color: 'warning.dark', mt: 0.5 }}>
                              {spacingInfo.spacingType}
                            </Typography>
                            <Typography variant="caption" sx={{ color: 'warning.dark', mt: 0.5, display: 'block' }}>
                              Each paragraph within this page was analyzed separately for line spacing compliance.
                              All paragraphs must use single-line spacing (≤1.2x font size) to pass validation.
                            </Typography>
                          </Box>
                        )}
                        
                        {getValidationBadges(spacingIssues)}
                      </Box>
                    );
                  })()}
                </Box>
              )}
              
              {/* Show message if no spacing info available for pure image page */}
              {!spacingInfo && isPureImagePage && (
                <Box sx={{ mt: 2, mb: 2 }}>
                  <Typography variant="subtitle2" gutterBottom>
                    Line Spacing Analysis:
                  </Typography>
                  <Box sx={{ 
                    p: 2, 
                    backgroundColor: 'info.light',
                    borderRadius: 1,
                    border: '2px solid',
                    borderColor: 'info.main'
                  }}>
                    <Typography variant="body2" sx={{ color: 'info.dark', fontWeight: 'bold' }}>
                      ℹ️ Line Spacing Analysis Not Applicable
                    </Typography>
                    <Typography variant="body2" sx={{ color: 'info.dark', mt: 0.5 }}>
                      This page contains only images without text content. 
                      Line spacing analysis is only applicable to pages with text content.
                    </Typography>
                  </Box>
                </Box>
              )}
            </Paper>
          );
        })}
      </>
    );
  }
};

export default AnalysisResults;
