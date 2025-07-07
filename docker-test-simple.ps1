# Simple Docker Test Script
Write-Host "PDF Analyzer Docker Test" -ForegroundColor Green
Write-Host "========================" -ForegroundColor Green

# Check Docker
Write-Host "`nChecking Docker..." -ForegroundColor Yellow
try {
    docker --version
    Write-Host "✓ Docker is installed" -ForegroundColor Green
}
catch {
    Write-Host "✗ Docker not found" -ForegroundColor Red
    exit 1
}

# Check Docker daemon
Write-Host "`nTesting Docker daemon..." -ForegroundColor Yellow
try {
    docker ps | Out-Null
    Write-Host "✓ Docker daemon is running" -ForegroundColor Green
}
catch {
    Write-Host "✗ Docker daemon not running. Please start Docker Desktop first." -ForegroundColor Red
    Write-Host "After starting Docker Desktop, run this command:" -ForegroundColor Yellow
    Write-Host "docker build -f Dockerfile.simple -t pdf-analyzer-test ." -ForegroundColor White
    exit 1
}

# Run simple test
Write-Host "`nBuilding simple test image..." -ForegroundColor Cyan
docker build -f Dockerfile.simple -t pdf-analyzer-test .

if ($LASTEXITCODE -eq 0) {
    Write-Host "✓ Build successful!" -ForegroundColor Green
    Write-Host "Running test container..." -ForegroundColor Yellow
    docker run --rm pdf-analyzer-test
}
else {
    Write-Host "✗ Build failed" -ForegroundColor Red
}
