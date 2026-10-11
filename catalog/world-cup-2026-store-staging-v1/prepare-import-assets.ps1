[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$catalogRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$sourcePath = Join-Path $catalogRoot 'sources.json'
$sourceAssets = Join-Path $catalogRoot 'assets'
$importAssets = Join-Path $catalogRoot 'import-assets'
$manifestPath = Join-Path $catalogRoot 'manifest.json'
$canvasSize = 1200

if (-not (Test-Path -LiteralPath $sourcePath)) {
    throw "Missing sources.json"
}
New-Item -ItemType Directory -Force -Path $importAssets | Out-Null

function Convert-ToCatalogJpeg {
    param([string]$InputPath, [string]$OutputPath)

    $source = [System.Drawing.Image]::FromFile($InputPath)
    try {
        $canvas = New-Object System.Drawing.Bitmap $canvasSize, $canvasSize
        try {
            $canvas.SetResolution(96, 96)
            $graphics = [System.Drawing.Graphics]::FromImage($canvas)
            try {
                $graphics.Clear([System.Drawing.Color]::White)
                $graphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
                $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
                $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
                $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
                $scale = [Math]::Min($canvasSize / $source.Width, $canvasSize / $source.Height)
                $width = [Math]::Max(1, [int][Math]::Round($source.Width * $scale))
                $height = [Math]::Max(1, [int][Math]::Round($source.Height * $scale))
                $x = [int](($canvasSize - $width) / 2)
                $y = [int](($canvasSize - $height) / 2)
                $graphics.DrawImage($source, $x, $y, $width, $height)
            } finally {
                $graphics.Dispose()
            }

            $codec = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() |
                Where-Object MimeType -eq 'image/jpeg'
            $parameters = New-Object System.Drawing.Imaging.EncoderParameters 1
            $parameters.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter(
                [System.Drawing.Imaging.Encoder]::Quality, [long]92)
            try {
                $canvas.Save($OutputPath, $codec, $parameters)
            } finally {
                $parameters.Dispose()
            }
        } finally {
            $canvas.Dispose()
        }
    } finally {
        $source.Dispose()
    }
}

$catalogRules = @{
    'WC26-ADIDAS-ARG-JM8396' = @{ Slug='ao-dau-argentina-2026-home-jm8396'; DisplayName='Argentina 2026 Home Jersey'; Brand='adidas'; Gender='MEN'; Color='WHITE'; Price=2490000; Team='ARGENTINA' }
    'WC26-ADIDAS-BEL-JM8381' = @{ Slug='ao-dau-bi-2026-home-jm8381'; DisplayName='Belgium 2026 Home Jersey'; Brand='adidas'; Gender='MEN'; Color='RED'; Price=2290000; Team='BELGIUM' }
    'WC26-ADIDAS-GER-JZ4559' = @{ Slug='ao-dau-duc-nu-2026-home-jz4559'; DisplayName='Germany Women 2026 Home Jersey'; Brand='adidas'; Gender='WOMEN'; Color='WHITE'; Price=2290000; Team='GERMANY' }
    'WC26-NIKE-BRA-IF7054-724' = @{ Slug='ao-dau-brazil-2026-home-if7054-724'; DisplayName='Brazil 2026 Stadium Home Jersey'; Brand='nike'; Gender='MEN'; Color='YELLOW'; Price=2490000; Team='BRAZIL' }
    'WC26-NIKE-FRA-IB5300-480' = @{ Slug='ao-dau-phap-2026-home-ib5300-480'; DisplayName='France 2026 Stadium Home Jersey'; Brand='nike'; Gender='MEN'; Color='BLUE'; Price=2490000; Team='FRANCE' }
    'WC26-NIKE-ENG-IB5290-100' = @{ Slug='ao-dau-anh-2026-home-ib5290-100'; DisplayName='England 2026 Stadium Home Jersey'; Brand='nike'; Gender='MEN'; Color='WHITE'; Price=2490000; Team='ENGLAND' }
}

$sources = Get-Content -Raw -LiteralPath $sourcePath | ConvertFrom-Json
$products = @()
foreach ($sourceProduct in $sources.products) {
    $rule = $catalogRules[$sourceProduct.code]
    if ($null -eq $rule) { throw "No catalog rule for $($sourceProduct.code)" }
    $images = @()
    for ($index = 0; $index -lt $sourceProduct.images.Count; $index++) {
        $sourceImage = $sourceProduct.images[$index]
        $inputPath = Join-Path $sourceAssets $sourceImage.file
        if (-not (Test-Path -LiteralPath $inputPath)) { throw "Missing source image $inputPath" }
        $outputName = [IO.Path]::GetFileNameWithoutExtension($sourceImage.file) + '.jpg'
        $outputPath = Join-Path $importAssets $outputName
        Convert-ToCatalogJpeg -InputPath $inputPath -OutputPath $outputPath
        $file = Get-Item -LiteralPath $outputPath
        if ($file.Length -gt 5MB) { throw "Prepared image exceeds 5MB: $outputName" }
        $images += [ordered]@{
            path = "import-assets/$outputName"
            sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $outputPath).Hash.ToLowerInvariant()
            altText = "$($rule.DisplayName) - product view $($index + 1)"
            view = if ($index -eq 0) { 'PRIMARY' } else { 'ALTERNATE' }
            sortOrder = $index
            primary = ($index -eq 0)
            sourceUri = $sourceImage.sourceUrl
        }
    }
    $description = "$($rule.DisplayName), brand product code $($sourceProduct.brandProductCode). Official-store reference data for a private non-commercial student project; this does not represent an authorized retailer relationship."
    $products += [ordered]@{
        code = $sourceProduct.code
        name = $rule.DisplayName
        slug = $rule.Slug
        categorySlug = 'ao-the-thao'
        brandSlug = $rule.Brand
        productType = 'SPORTS_TOP'
        gender = $rule.Gender
        sportCode = 'FOOTBALL'
        colorCode = $rule.Color
        sizes = @('S', 'M', 'L', 'XL', 'XXL')
        price = $rule.Price
        compareAtPrice = $rule.Price
        stockPerVariant = 8
        shortDescription = "$($rule.DisplayName), 2026 national-team football shirt."
        description = $description
        attributes = [ordered]@{
            team = $rule.Team
            kit_type = 'HOME'
            brand_product_code = $sourceProduct.brandProductCode
            official_source_page = $sourceProduct.sourcePage
            image_usage = 'PERSONAL_EDUCATIONAL_REFERENCE'
            price_basis = 'PROJECT_DEMO_VND'
            inventory_basis = 'PROJECT_DEMO_STOCK'
        }
        images = $images
    }
}

$manifest = [ordered]@{
    schemaVersion = 1
    dataset = [ordered]@{
        name = 'world-cup-2026-official-store-reference'
        version = '1.0.0'
        dataSource = 'REAL'
        licenseCode = 'NONCOMMERCIAL_EDUCATIONAL_REFERENCE'
        sourceDescription = 'Official Nike/Adidas store product references normalized for a private, non-commercial student project; source URLs retained and no commercial licence is asserted.'
    }
    imagePolicy = [ordered]@{
        sourceKind = 'REFERENCE'
        generator = 'SOURCE_IMAGE_NORMALIZED_HIGH_QUALITY'
        originalFormat = 'jpg'
        requiredWidth = $canvasSize
        requiredHeight = $canvasSize
        minBytes = 30000
        maxBytes = 5242880
        colorSpace = 'sRGB'
        embeddingSource = 'VERSIONED_CATALOG_ORIGINAL_ONLY'
        uiDelivery = 'Cloudinary responsive delivery; square container; object-contain; never upscale above 1200px'
    }
    products = $products
}

$manifest | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $manifestPath -Encoding utf8
Write-Host "Prepared $($products.Count) products and $((Get-ChildItem -LiteralPath $importAssets -File).Count) images."
Write-Host "Manifest: $manifestPath"
