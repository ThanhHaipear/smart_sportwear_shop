$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$sourcePath = Join-Path $root 'sources.json'
$assetDirectory = Join-Path $root 'assets'
$manifestPath = Join-Path $root 'assets-manifest.json'
$allowedHosts = @('assets.adidas.com', 'static.nike.com')

$source = Get-Content -LiteralPath $sourcePath -Raw | ConvertFrom-Json
New-Item -ItemType Directory -Force -Path $assetDirectory | Out-Null

$seenHashes = @{}
$manifestProducts = @()

foreach ($product in $source.products) {
    if ($product.images.Count -lt 2 -or $product.images.Count -gt 4) {
        throw "Product $($product.code) must have 2-4 images."
    }

    $primaryCount = @($product.images | Where-Object role -eq 'PRIMARY').Count
    if ($primaryCount -ne 1) {
        throw "Product $($product.code) must have exactly one PRIMARY image."
    }

    $manifestImages = @()
    foreach ($image in $product.images) {
        $uri = [Uri]$image.sourceUrl
        if ($uri.Scheme -ne 'https' -or $allowedHosts -notcontains $uri.Host) {
            throw "Blocked image source: $($image.sourceUrl)"
        }

        $destination = Join-Path $assetDirectory $image.file
        Invoke-WebRequest -Uri $image.sourceUrl -OutFile $destination -MaximumRedirection 3

        $bytes = [System.IO.File]::ReadAllBytes($destination)
        $sha = [System.Security.Cryptography.SHA256]::Create()
        try {
            $hash = ([System.BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
        }
        finally {
            $sha.Dispose()
        }

        if ($seenHashes.ContainsKey($hash)) {
            throw "Duplicate image content: $($image.file) and $($seenHashes[$hash])."
        }
        $seenHashes[$hash] = $image.file

        Add-Type -AssemblyName System.Drawing
        $bitmap = [System.Drawing.Image]::FromFile($destination)
        try {
            $width = $bitmap.Width
            $height = $bitmap.Height
        }
        finally {
            $bitmap.Dispose()
        }

        if ([Math]::Max($width, $height) -lt 936) {
            throw "Image $($image.file) is too small: ${width}x${height}."
        }

        $manifestImages += [ordered]@{
            file = $image.file
            role = $image.role
            sourceUrl = $image.sourceUrl
            sha256 = $hash
            bytes = $bytes.Length
            width = $width
            height = $height
            mediaType = if ($image.file.EndsWith('.png')) { 'image/png' } else { 'image/jpeg' }
            reviewStatus = 'PENDING_RIGHTS_APPROVAL'
        }
    }

    $manifestProducts += [ordered]@{
        code = $product.code
        brandProductCode = $product.brandProductCode
        images = $manifestImages
    }
}

$manifest = [ordered]@{
    schemaVersion = 1
    dataset = $source.dataset
    generatedAt = [DateTimeOffset]::UtcNow.ToString('o')
    productCount = $manifestProducts.Count
    imageCount = @($manifestProducts.images).Count
    products = $manifestProducts
}

$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
Write-Output "Downloaded $($manifest.imageCount) images for $($manifest.productCount) products."
Write-Output "Manifest: $manifestPath"
