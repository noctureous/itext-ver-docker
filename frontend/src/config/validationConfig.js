// Configuration for PDF validation rules
export const validationConfig = {
  // Margin requirements (in cm)
  margins: {
    minimum: 2.5, // Minimum margin requirement in cm
    left: {
      minimum: 2.5
    },
    top: {
      minimum: 2.5
    },
    right: {
      minimum: 2.5
    },
    bottom: {
      minimum: 2.5
    }
  },
  
  // Font requirements
  fonts: {
    allowedFamilies: ['TimesNewRoman', 'Times-Roman', 'Times New Roman'],
    minimumSize: 12, // Minimum font size in points
    excludeFromValidation: ['OCR-Extracted-From-Image'] // Fonts to skip validation
  },
  
  // Line spacing requirements
  spacing: {
    minimumLineGap: 12.0, // Minimum line gap in points
    singleLineMax: 1.2, // Maximum ratio for single-line spacing
    acceptableThreshold: 12.0 // Threshold for acceptable spacing
  }
};

// Helper function to get margin minimum for a specific side
export const getMarginMinimum = (side = null) => {
  if (side && validationConfig.margins[side]) {
    return validationConfig.margins[side].minimum;
  }
  return validationConfig.margins.minimum;
};

// Helper function to check if font family is allowed
export const isFontFamilyAllowed = (fontName) => {
  if (!fontName) return false;
  
  // Skip validation for excluded fonts
  if (validationConfig.fonts.excludeFromValidation.includes(fontName)) {
    return true;
  }
  
  return validationConfig.fonts.allowedFamilies.some(allowed => 
    fontName.includes(allowed)
  );
};

export default validationConfig;