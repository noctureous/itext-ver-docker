# Docker Test Script for PDF Analyzer
# This script provides different testing options for the PDF Analyzer application

param(
    [Parameter(Mandatory=$false)]
    [ValidateSet("simple", "full", "unit-tests", "all")]
    [string]$TestType = "simple"
)

Write-Host "PDF Analyzer Docker Test Script" -ForegroundColor Green
Write-Host "================================" -ForegroundColor Green

# Check if Docker is running
Write-Host "`nChecking Docker status..." -ForegroundColor Yellow
try {
    docker version | Out-Null
    Write-Host "✓ Docker is running" -ForegroundColor Green
} 
catch {
    Write-Host "✗ Docker is not running. Please start Docker Desktop first." -ForegroundColor Red
    exit 1
}

# Change to project directory
Set-Location $PSScriptRoot

switch ($TestType) {
    "simple" {
        Write-Host "`n1. Running Simple Docker Test..." -ForegroundColor Cyan
        Write-Host "Building simple test image..." -ForegroundColor Yellow
        docker build -f Dockerfile.simple -t pdf-analyzer-test .
        
        if ($LASTEXITCODE -eq 0) {
            Write-Host "✓ Simple test image built successfully" -ForegroundColor Green
            Write-Host "Running container test..." -ForegroundColor Yellow
            docker run --rm pdf-analyzer-test
        } 
        else {
            Write-Host "✗ Failed to build simple test image" -ForegroundColor Red
        }
    }
    
    "unit-tests" {
        Write-Host "`n2. Running Unit Tests in Docker..." -ForegroundColor Cyan
        docker run --rm -v "${PWD}:/app" -w /app maven:3.9.6-openjdk-17-slim mvn test
    }
    
    "full" {
        Write-Host "`n3. Running Full Application Stack..." -ForegroundColor Cyan
        Write-Host "This will start MySQL, PDF Analyzer, and Nginx (if production profile)" -ForegroundColor Yellow
        Write-Host "Building and starting services..." -ForegroundColor Yellow
        docker-compose up --build -d
        
        if ($LASTEXITCODE -eq 0) {
            Write-Host "✓ Services started successfully" -ForegroundColor Green
            Write-Host "`nServices are running:" -ForegroundColor Green
            Write-Host "- PDF Analyzer: http://localhost:8080" -ForegroundColor White
            Write-Host "- MySQL: localhost:3306" -ForegroundColor White
            Write-Host "`nTo stop services, run: docker-compose down" -ForegroundColor Yellow
            
            # Wait for application to be ready
            Write-Host "`nWaiting for application to be ready..." -ForegroundColor Yellow
            $maxAttempts = 30
            $attempt = 0
            do {
                $attempt++
                Start-Sleep -Seconds 2
                try {
                    $response = Invoke-WebRequest -Uri "http://localhost:8080/pdf-analyzer/" -TimeoutSec 5 -ErrorAction SilentlyContinue
                    if ($response.StatusCode -eq 200) {
                        Write-Host "✓ Application is ready!" -ForegroundColor Green
                        break
                    }
                } 
                catch {
                    # Continue waiting
                }
                Write-Host "Attempt $attempt/$maxAttempts..." -ForegroundColor Gray
            } while ($attempt -lt $maxAttempts)
            
            if ($attempt -eq $maxAttempts) {
                Write-Host "⚠ Application may still be starting. Check logs with: docker-compose logs" -ForegroundColor Yellow
            }
        } 
        else {
            Write-Host "✗ Failed to start services" -ForegroundColor Red
        }
    }
    
    "all" {
        Write-Host "`nRunning All Tests..." -ForegroundColor Cyan
        
        # Run unit tests first
        Write-Host "`n1. Unit Tests:" -ForegroundColor Yellow
        docker run --rm -v "${PWD}:/app" -w /app maven:3.9.6-openjdk-17-slim mvn test
        
        # Build simple test
        Write-Host "`n2. Simple Build Test:" -ForegroundColor Yellow
        docker build -f Dockerfile.simple -t pdf-analyzer-test .
        
        # Build full application
        Write-Host "`n3. Full Application Build:" -ForegroundColor Yellow
        docker build -t pdf-analyzer-full .
        
        Write-Host "`n✓ All tests completed" -ForegroundColor Green
    }
}

Write-Host "`nTest completed!" -ForegroundColor Green
