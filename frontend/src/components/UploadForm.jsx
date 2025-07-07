import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, Box, Typography, IconButton, TextField, Chip, Alert, Snackbar, CircularProgress } from '@mui/material';
import CloudUploadIcon from '@mui/icons-material/CloudUpload';
import FolderIcon from '@mui/icons-material/Folder';
import DeleteIcon from '@mui/icons-material/Delete';
import SecurityIcon from '@mui/icons-material/Security';
import axios from 'axios';

const UploadForm = () => {
  const [selectedFiles, setSelectedFiles] = useState([]);
  const [dirPath, setDirPath] = useState('');
  const [dirLoading, setDirLoading] = useState(false);
  const [uploadLoading, setUploadLoading] = useState(false);
  const [dragOver, setDragOver] = useState(false);
  const [uploadError, setUploadError] = useState(null);
  const [uploadSuccess, setUploadSuccess] = useState(false);
  const [virusDetected, setVirusDetected] = useState(null);
  const navigate = useNavigate();

  const handleFileSelect = (event) => {
    const files = Array.from(event.target.files);
    setSelectedFiles(files);
    setUploadError(null);
    setVirusDetected(null);
  };

  const handleFolderSelect = (event) => {
    const files = Array.from(event.target.files).filter(file => 
      file.type === 'application/pdf' || file.name.toLowerCase().endsWith('.pdf')
    );
    setSelectedFiles(files);
    setUploadError(null);
    setVirusDetected(null);
  };

  const handleDragOver = (event) => {
    event.preventDefault();
    setDragOver(true);
  };

  const handleDragLeave = (event) => {
    event.preventDefault();
    setDragOver(false);
  };

  const handleDrop = (event) => {
    event.preventDefault();
    setDragOver(false);
    
    const items = Array.from(event.dataTransfer.items);
    const files = [];
    
    // Handle dropped files
    for (const item of items) {
      if (item.kind === 'file') {
        const file = item.getAsFile();
        if (file.type === 'application/pdf' || file.name.toLowerCase().endsWith('.pdf')) {
          files.push(file);
        }
      }
    }
    
    setSelectedFiles(files);
    setUploadError(null);
    setVirusDetected(null);
  };

  const removeFile = (index) => {
    setSelectedFiles(selectedFiles.filter((_, i) => i !== index));
  };

  const handleSubmit = async (event) => {
    event.preventDefault();
    if (selectedFiles.length === 0) {
      setUploadError('Please select files first');
      return;
    }

    setUploadLoading(true);
    setUploadError(null);
    setVirusDetected(null);

    const formData = new FormData();
    selectedFiles.forEach(file => {
      formData.append('files', file);
    });

    try {
      const response = await axios.post('http://localhost:8080/api/pdf/upload-multiple', formData, {
        headers: {
          'Content-Type': 'multipart/form-data',
        },
      });

      // Check if there were any virus detections in the response
      if (response.data.errors && response.data.errors.length > 0) {
        const virusErrors = response.data.errors.filter(error => error.error === 'VIRUS_DETECTED');
        if (virusErrors.length > 0) {
          setVirusDetected({
            count: virusErrors.length,
            files: virusErrors.map(error => ({
              fileName: error.fileName,
              threat: error.threat,
              scanEngine: error.scanEngine
            }))
          });
        }
      }

      // If we have any successful results, navigate to results page
      if (response.data.results && response.data.results.length > 0) {
        setUploadSuccess(true);
        navigate('/results', { state: { result: response.data } });
      } else if (response.data.errors && response.data.errors.length > 0) {
        // All files failed, show error summary
        const errorSummary = response.data.errors.map(error => 
          `${error.fileName}: ${error.error === 'VIRUS_DETECTED' ? 'Virus detected' : error.message || error.error}`
        ).join('\n');
        setUploadError(`All files failed to process:\n${errorSummary}`);
      }
    } catch (error) {
      console.error('Error uploading files:', error);
      
      // Handle specific error responses
      if (error.response && error.response.status === 400 && error.response.data) {
        const errorData = error.response.data;
        if (errorData.error === 'VIRUS_DETECTED') {
          setVirusDetected({
            count: 1,
            files: [{
              fileName: errorData.fileName,
              threat: errorData.threat,
              scanEngine: errorData.scanEngine
            }]
          });
          setUploadError(`Security threat detected in ${errorData.fileName}`);
        } else if (errorData.error === 'FILE_SIZE_EXCEEDED') {
          setUploadError(`File size too large: ${errorData.message} Maximum allowed: ${errorData.maxSize || '50GB'}`);
        } else {
          setUploadError(errorData.message || 'Error uploading files. Please try again.');
        }
      } else {
        setUploadError('Error uploading files. Please try again.');
      }
    } finally {
      setUploadLoading(false);
    }
  };

  const handleDirectoryAnalyze = async (event) => {
    event.preventDefault();
    if (!dirPath) {
      setUploadError('Please enter a directory path');
      return;
    }

    setDirLoading(true);
    setUploadError(null);
    setVirusDetected(null);

    try {
      const response = await axios.get('http://localhost:8080/api/pdf/analyze-directory', {
        params: { dir: dirPath }
      });
      
      // If the directory is empty, show a message and do not navigate
      if (Array.isArray(response.data) && response.data.length === 0) {
        setUploadError('The directory is empty or contains no PDF files.');
      } else {
        setUploadSuccess(true);
        navigate('/results', { state: { result: response.data } });
      }
    } catch (error) {
      console.error('Error analyzing directory:', error);
      setUploadError('Error analyzing directory. Please check the path and try again.');
    } finally {
      setDirLoading(false);
    }
  };

  return (
    <>
      {/* Error Alert */}
      {uploadError && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setUploadError(null)}>
          {uploadError}
        </Alert>
      )}

      {/* Virus Detection Alert */}
      {virusDetected && (
        <Alert 
          severity="warning" 
          sx={{ mb: 2 }} 
          onClose={() => setVirusDetected(null)}
          icon={<SecurityIcon />}
        >
          <Typography variant="subtitle2" sx={{ fontWeight: 'bold', mb: 1 }}>
            Security Threat Detected ({virusDetected.count} file{virusDetected.count > 1 ? 's' : ''})
          </Typography>
          {virusDetected.files.map((file, index) => (
            <Typography key={index} variant="body2" sx={{ mb: 0.5 }}>
              • <strong>{file.fileName}</strong>: {file.threat} (detected by {file.scanEngine})
            </Typography>
          ))}
          <Typography variant="body2" sx={{ mt: 1, fontStyle: 'italic' }}>
            These files have been blocked from processing for security reasons.
          </Typography>
        </Alert>
      )}

      {/* Success Snackbar */}
      <Snackbar
        open={uploadSuccess}
        autoHideDuration={3000}
        onClose={() => setUploadSuccess(false)}
      >
        <Alert severity="success" onClose={() => setUploadSuccess(false)}>
          Files processed successfully! Antivirus scan passed.
        </Alert>
      </Snackbar>

      {/* Multiple File Upload */}
      <Box component="form" onSubmit={handleSubmit} sx={{ mt: 4 }}>
        <Box
          onDragOver={handleDragOver}
          onDragLeave={handleDragLeave}
          onDrop={handleDrop}
          sx={{
            border: 2,
            borderColor: dragOver ? 'secondary.main' : 'primary.main',
            borderRadius: 2,
            p: 3,
            textAlign: 'center',
            backgroundColor: dragOver ? 'action.hover' : 'transparent',
            transition: 'all 0.3s ease',
            '&:hover': {
              borderColor: 'secondary.main',
              backgroundColor: 'action.hover',
            },
          }}
        >
          <Box sx={{ display: 'flex', justifyContent: 'center', gap: 2, mb: 2 }}>
            <IconButton
              color="primary"
              aria-label="upload files"
              component="label"
            >
              <input
                hidden
                accept="application/pdf"
                type="file"
                multiple
                onChange={handleFileSelect}
              />
              <CloudUploadIcon sx={{ fontSize: 60 }} />
            </IconButton>
            <IconButton
              color="primary"
              aria-label="upload folder"
              component="label"
            >
              <input
                hidden
                type="file"
                webkitdirectory=""
                onChange={handleFolderSelect}
              />
              <FolderIcon sx={{ fontSize: 60 }} />
            </IconButton>
          </Box>
          <Typography variant="h6" gutterBottom>
            Drag and drop PDF files here, click to upload files, or select a folder
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
            Only PDF files are accepted. Multiple files supported.
          </Typography>
          <Typography variant="caption" color="text.secondary" sx={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 0.5 }}>
            <SecurityIcon fontSize="small" /> 
            All files are automatically scanned for security threats
          </Typography>
        </Box>

        {/* Display selected files */}
        {selectedFiles.length > 0 && (
          <Box sx={{ mt: 2 }}>
            <Typography variant="subtitle1" gutterBottom>
              Selected Files ({selectedFiles.length}):
            </Typography>
            <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1 }}>
              {selectedFiles.map((file, index) => (
                <Chip
                  key={index}
                  label={file.name}
                  onDelete={() => removeFile(index)}
                  deleteIcon={<DeleteIcon />}
                  variant="outlined"
                  size="small"
                />
              ))}
            </Box>
          </Box>
        )}

        <Box sx={{ mt: 4, textAlign: 'center' }}>
          <Button
            variant="contained"
            color="primary"
            size="large"
            type="submit"
            disabled={selectedFiles.length === 0 || uploadLoading}
            startIcon={uploadLoading ? <CircularProgress size={20} color="inherit" /> : null}
            sx={{
              minWidth: 200,
              position: 'relative'
            }}
          >
            {uploadLoading ? 'Analyzing PDF Files...' : `Analyze PDF${selectedFiles.length > 1 ? 's' : ''}`}
          </Button>
        </Box>
      </Box>
      
      {/* Directory Analyzer - Hidden */}
      {false && (
        <Box component="form" onSubmit={handleDirectoryAnalyze} sx={{ mt: 6, textAlign: 'center' }}>
          <Typography variant="h6" gutterBottom>
            Analyze All PDFs in Directory
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 0.5 }}>
            <SecurityIcon fontSize="small" /> 
            Files with security threats will be automatically skipped
          </Typography>
          <TextField
            label="Directory Path"
            value={dirPath}
            onChange={(e) => setDirPath(e.target.value)}
            variant="outlined"
            sx={{ width: 400, maxWidth: '90%', mb: 2 }}
            disabled={uploadLoading || dirLoading}
          />
          <Button
            variant="contained"
            color="primary"
            size="large"
            type="submit"
            startIcon={dirLoading ? <CircularProgress size={20} color="inherit" /> : null}
            sx={{
              ml: 2,
              px: 4,
              py: 1.5,
              fontWeight: 'bold',
              fontSize: '1.1rem',
              boxShadow: 2,
              borderRadius: 2,
              textTransform: 'none',
              letterSpacing: 1,
              minWidth: 200
            }}
            disabled={dirLoading || uploadLoading}
          >
            {dirLoading ? 'Analyzing...' : 'Analyze Directory'}
          </Button>
        </Box>
      )}
    </>
  );
};

export default UploadForm;
