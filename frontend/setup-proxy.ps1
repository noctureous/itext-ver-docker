$env:NPM_CONFIG_PROXY = "http://10.106.103.40:8080"
$env:NPM_CONFIG_HTTPS_PROXY = "http://10.106.103.40:8080"
$env:NPM_CONFIG_STRICT_SSL = $false
$env:NPM_CONFIG_REGISTRY = "https://registry.npmjs.org/"

npm config set proxy http://10.106.103.40:8080
npm config set https-proxy http://10.106.103.40:8080
npm config set strict-ssl false
