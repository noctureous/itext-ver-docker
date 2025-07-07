import React, { useState } from 'react';
import { Box, Typography, TextField, Button, Paper } from '@mui/material';
import AnalysisResults from './AnalysisResults';

const DirectoryAnalyzer = () => {
  const [dirPath, setDirPath] = useState('');
  const [results, setResults] = useState([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const handleAnalyze = async () => {
    setError('');
    setLoading(true);
    setResults([]);
    try {
      const response = await fetch(`/api/pdf/analyze-directory?dir=${encodeURIComponent(dirPath)}`);
      if (!response.ok) throw new Error('Failed to analyze directory');
      const data = await response.json();
      setResults(data);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <Box sx={{ mt: 4 }}>

      {results.length > 0 && (
        <Box sx={{ mt: 4 }}>
          <Typography variant="subtitle1">Results:</Typography>
          {results.map((result, idx) => (
            <Paper sx={{ p: 2, mb: 2 }} key={result.fileName || idx}>
              <AnalysisResults result={result} />
            </Paper>
          ))}
        </Box>
      )}
    </Box>
  );
};

export default DirectoryAnalyzer;
