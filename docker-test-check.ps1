# Quick Docker Test Verification
# Run this after starting Docker Desktop

Write-Host "PDF Analyzer - Quick Docker Test" -ForegroundColor Green
Write-Host "===============================" -ForegroundColor Green

# Check Docker status
Write-Host "`nChecking Docker..." -ForegroundColor Yellow
try {
    $dockerVersion = docker --version
    Write-Host "✓ $dockerVersion" -ForegroundColor Green
} catch {
    Write-Host "✗ Docker not found. Please install Docker Desktop." -ForegroundColor Red
    exit 1
}

# Test Docker daemon
Write-Host "`nTesting Docker daemon..." -ForegroundColor Yellow
try {
    docker ps | Out-Null
    Write-Host "✓ Docker daemon is running" -ForegroundColor Green
} catch {
    Write-Host "✗ Docker daemon is not running. Please start Docker Desktop." -ForegroundColor Red
    Write-Host "After starting Docker Desktop, run this script again." -ForegroundColor Yellow
    exit 1
}

# Check if in correct directory
if (Test-Path "pom.xml") {
    Write-Host "✓ Found Maven project" -ForegroundColor Green
} else {
    Write-Host "✗ Not in project directory. Please navigate to the project root." -ForegroundColor Red
    exit 1
}

Write-Host "`n🚀 Ready to run Docker tests!" -ForegroundColor Green
Write-Host "`nAvailable test options:" -ForegroundColor Cyan
Write-Host "1. Simple test:     .\test-docker.ps1 -TestType simple" -ForegroundColor White
Write-Host "2. Unit tests:      .\test-docker.ps1 -TestType unit-tests" -ForegroundColor White
Write-Host "3. Full stack:      .\test-docker.ps1 -TestType full" -ForegroundColor White
Write-Host "4. All tests:       .\test-docker.ps1 -TestType all" -ForegroundColor White
Write-Host "`nOr run individual Docker commands:" -ForegroundColor Cyan
Write-Host "- Build simple:     docker build -f Dockerfile.simple -t pdf-analyzer-test ." -ForegroundColor Gray
Write-Host "- Run full stack:   docker-compose up --build" -ForegroundColor Gray
