import React from 'react';
import { BrowserRouter as Router, Routes, Route } from 'react-router-dom';
import UploadForm from './components/UploadForm';
import AnalysisResults from './components/AnalysisResults';
import DirectoryAnalyzer from './components/DirectoryAnalyzer';
import { Container, Typography, Box } from '@mui/material';

function App() {
  return (
    <Router>
      <Container maxWidth="md">
        <Box sx={{ my: 4 }}>
          <Typography variant="h4" component="h1" gutterBottom>
            PDF Analyzer
          </Typography>
          <Routes>
            <Route path="/" element={<UploadForm />} />
            <Route path="/results" element={
              <>
                <AnalysisResults />
                <DirectoryAnalyzer />
              </>
            } />
          </Routes>
        </Box>
      </Container>
    </Router>
  );
}

export default App;
