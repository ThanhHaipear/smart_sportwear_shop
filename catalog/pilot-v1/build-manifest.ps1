$ErrorActionPreference = 'Stop'

$catalogRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$assetRoot = Join-Path $catalogRoot 'assets'
$manifestPath = Join-Path $catalogRoot 'manifest.json'
$checksumPath = Join-Path $catalogRoot 'assets.sha256'

function New-PilotProduct {
    param(
        [string]$Code,
        [string]$Name,
        [string]$Slug,
        [string]$CategorySlug,
        [string]$ProductType,
        [string]$Gender,
        [string]$SportCode,
        [string]$ColorCode,
        [string[]]$Sizes,
        [int64]$Price,
        [int64]$CompareAtPrice,
        [int]$StockPerVariant,
        [string]$Material,
        [string]$Fit,
        [string]$ShortDescription
    )

    $images = 1..2 | ForEach-Object {
        $index = $_
        $fileName = '{0}-{1:d2}.png' -f $Code, $index
        [ordered]@{
            path = "assets/$fileName"
            sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $assetRoot $fileName)).Hash.ToLowerInvariant()
            altText = if ($index -eq 1) { "$Name - góc chính" } else { "$Name - góc sau" }
            view = if ($index -eq 1) { 'PRIMARY_THREE_QUARTER' } else { 'REAR_THREE_QUARTER' }
            sortOrder = $index - 1
            primary = $index -eq 1
        }
    }

    return [ordered]@{
        code = $Code
        name = $Name
        slug = $Slug
        categorySlug = $CategorySlug
        brandSlug = 'khong-thuong-hieu'
        productType = $ProductType
        gender = $Gender
        sportCode = $SportCode
        colorCode = $ColorCode
        sizes = $Sizes
        price = $Price
        compareAtPrice = $CompareAtPrice
        stockPerVariant = $StockPerVariant
        shortDescription = $ShortDescription
        description = "$ShortDescription Sản phẩm pilot hư cấu, ảnh synthetic không chứa logo, dùng cho demo và đánh giá tìm kiếm hình ảnh."
        attributes = [ordered]@{
            material = $Material
            fit = $Fit
            image_source = 'OPENAI_IMAGEGEN_SYNTHETIC'
            dataset_version = '1.0.0'
        }
        images = $images
    }
}

$products = @(
    New-PilotProduct 'PILOT-TOP-001' 'Áo chạy bộ nam Aero Navy' 'ao-chay-bo-nam-aero-navy' 'ao-the-thao' 'SPORTS_TOP' 'MEN' 'RUNNING' 'NAVY' @('S','M','L','XL','XXL') 429000 529000 16 'POLYESTER' 'ATHLETIC' 'Áo chạy bộ nam màu navy, vải lưới thoáng khí và thấm hút mồ hôi.'
    New-PilotProduct 'PILOT-TOP-002' 'Áo tank chạy bộ nữ Coral Flow' 'ao-tank-chay-bo-nu-coral-flow' 'ao-the-thao' 'SPORTS_TOP' 'WOMEN' 'RUNNING' 'PINK' @('XS','S','M','L','XL') 389000 479000 14 'POLYESTER_ELASTANE' 'SLIM' 'Áo tank chạy bộ nữ màu coral, nhẹ và thoáng khí.'
    New-PilotProduct 'PILOT-TOP-003' 'Áo chạy bộ dài tay Uni Cloud' 'ao-chay-bo-dai-tay-uni-cloud' 'ao-the-thao' 'SPORTS_TOP' 'UNISEX' 'RUNNING' 'WHITE' @('XS','S','M','L','XL','XXL') 499000 599000 14 'POLYESTER' 'REGULAR' 'Áo chạy bộ dài tay unisex màu trắng, phù hợp thời tiết mát.'
    New-PilotProduct 'PILOT-TOP-004' 'Áo tập bóng đá nam Blaze Red' 'ao-tap-bong-da-nam-blaze-red' 'ao-the-thao' 'SPORTS_TOP' 'MEN' 'FOOTBALL' 'RED' @('S','M','L','XL','XXL') 459000 559000 18 'POLYESTER_MESH' 'ATHLETIC' 'Áo tập bóng đá nam màu đỏ với các mảng lưới thoáng khí.'
    New-PilotProduct 'PILOT-TOP-005' 'Áo tập bóng đá nữ Royal Motion' 'ao-tap-bong-da-nu-royal-motion' 'ao-the-thao' 'SPORTS_TOP' 'WOMEN' 'FOOTBALL' 'BLUE' @('XS','S','M','L','XL') 459000 559000 16 'POLYESTER_MESH' 'ATHLETIC' 'Áo tập bóng đá nữ màu xanh dương, phom thể thao gọn.'
    New-PilotProduct 'PILOT-TOP-006' 'Áo giữ nhiệt bóng đá Uni Core' 'ao-giu-nhiet-bong-da-uni-core' 'ao-the-thao' 'SPORTS_TOP' 'UNISEX' 'FOOTBALL' 'BLACK' @('XS','S','M','L','XL','XXL') 579000 699000 12 'THERMAL_POLYESTER' 'SLIM' 'Áo giữ nhiệt bóng đá unisex màu đen, thiết kế dài tay.'
    New-PilotProduct 'PILOT-TOP-007' 'Áo khoác chạy bộ nữ Violet Air' 'ao-khoac-chay-bo-nu-violet-air' 'ao-the-thao' 'SPORTS_TOP' 'WOMEN' 'RUNNING' 'PURPLE' @('XS','S','M','L','XL') 749000 899000 10 'LIGHTWEIGHT_SHELL' 'SLIM' 'Áo khoác chạy bộ nữ màu tím, nhẹ và cản gió.'
    New-PilotProduct 'PILOT-TOP-008' 'Áo gió chạy bộ nam Orange Pace' 'ao-gio-chay-bo-nam-orange-pace' 'ao-the-thao' 'SPORTS_TOP' 'MEN' 'RUNNING' 'ORANGE' @('S','M','L','XL','XXL') 789000 949000 10 'WATER_RESISTANT_SHELL' 'REGULAR' 'Áo gió chạy bộ nam màu cam, có lưng thông khí.'

    New-PilotProduct 'PILOT-BOTTOM-001' 'Quần short chạy bộ nam Swift Black' 'quan-short-chay-bo-nam-swift-black' 'quan-the-thao' 'SPORTS_BOTTOM' 'MEN' 'RUNNING' 'BLACK' @('S','M','L','XL','XXL') 359000 449000 18 'WOVEN_POLYESTER' 'ATHLETIC' 'Quần short chạy bộ nam màu đen, nhẹ và linh hoạt.'
    New-PilotProduct 'PILOT-BOTTOM-002' 'Quần legging chạy bộ nữ Navy Flex' 'quan-legging-chay-bo-nu-navy-flex' 'quan-the-thao' 'SPORTS_BOTTOM' 'WOMEN' 'RUNNING' 'NAVY' @('XS','S','M','L','XL') 529000 649000 14 'POLYESTER_ELASTANE' 'COMPRESSION' 'Quần legging chạy bộ nữ màu navy, cạp cao và co giãn.'
    New-PilotProduct 'PILOT-BOTTOM-003' 'Quần jogger thể thao Uni Motion' 'quan-jogger-the-thao-uni-motion' 'quan-the-thao' 'SPORTS_BOTTOM' 'UNISEX' 'RUNNING' 'GRAY' @('XS','S','M','L','XL','XXL') 549000 679000 14 'TECHNICAL_KNIT' 'TAPERED' 'Quần jogger thể thao unisex màu xám, phom thuôn gọn.'
    New-PilotProduct 'PILOT-BOTTOM-004' 'Quần bóng đá nam Royal Kick' 'quan-bong-da-nam-royal-kick' 'quan-the-thao' 'SPORTS_BOTTOM' 'MEN' 'FOOTBALL' 'BLUE' @('S','M','L','XL','XXL') 329000 419000 18 'POLYESTER_MESH' 'ATHLETIC' 'Quần bóng đá nam màu xanh dương, vải lưới nhanh khô.'
    New-PilotProduct 'PILOT-BOTTOM-005' 'Quần tập bóng đá nữ Black Drive' 'quan-tap-bong-da-nu-black-drive' 'quan-the-thao' 'SPORTS_BOTTOM' 'WOMEN' 'FOOTBALL' 'BLACK' @('XS','S','M','L','XL') 349000 439000 16 'STRETCH_WOVEN' 'ATHLETIC' 'Quần tập bóng đá nữ màu đen, co giãn và thoáng khí.'
    New-PilotProduct 'PILOT-BOTTOM-006' 'Quần track chạy bộ Uni Forest' 'quan-track-chay-bo-uni-forest' 'quan-the-thao' 'SPORTS_BOTTOM' 'UNISEX' 'RUNNING' 'GREEN' @('XS','S','M','L','XL','XXL') 599000 729000 12 'WATER_RESISTANT_WOVEN' 'TAPERED' 'Quần track chạy bộ unisex màu xanh lá, có khóa cổ chân.'

    New-PilotProduct 'PILOT-SHOE-001' 'Giày chạy bộ nam Cobalt Ride' 'giay-chay-bo-nam-cobalt-ride' 'giay-the-thao' 'SPORTS_SHOES' 'MEN' 'RUNNING' 'BLUE' @('40','41','42','43','44','45') 1399000 1699000 10 'ENGINEERED_MESH' 'NEUTRAL' 'Giày chạy bộ nam màu cobalt, đệm êm cho luyện tập hằng ngày.'
    New-PilotProduct 'PILOT-SHOE-002' 'Giày chạy bộ nữ Coral Cloud' 'giay-chay-bo-nu-coral-cloud' 'giay-the-thao' 'SPORTS_SHOES' 'WOMEN' 'RUNNING' 'PINK' @('36','37','38','39','40','41') 1399000 1699000 10 'ENGINEERED_MESH' 'NEUTRAL' 'Giày chạy bộ nữ màu coral với thân lưới nhẹ.'
    New-PilotProduct 'PILOT-SHOE-003' 'Giày chạy bộ Uni Pure Step' 'giay-chay-bo-uni-pure-step' 'giay-the-thao' 'SPORTS_SHOES' 'UNISEX' 'RUNNING' 'WHITE' @('38','39','40','41','42','43','44') 1499000 1799000 9 'KNIT_MESH' 'STABLE' 'Giày chạy bộ unisex màu trắng, đế ổn định và êm.'
    New-PilotProduct 'PILOT-SHOE-004' 'Giày đá bóng nam Turf Shadow' 'giay-da-bong-nam-turf-shadow' 'giay-the-thao' 'SPORTS_SHOES' 'MEN' 'FOOTBALL' 'BLACK' @('40','41','42','43','44','45') 1199000 1499000 10 'SYNTHETIC_UPPER' 'TURF' 'Giày đá bóng sân cỏ nhân tạo nam màu đen, đế đinh thấp.'
    New-PilotProduct 'PILOT-SHOE-005' 'Giày đá bóng nữ Turf Spark' 'giay-da-bong-nu-turf-spark' 'giay-the-thao' 'SPORTS_SHOES' 'WOMEN' 'FOOTBALL' 'YELLOW' @('36','37','38','39','40','41') 1199000 1499000 10 'SYNTHETIC_UPPER' 'TURF' 'Giày đá bóng sân cỏ nhân tạo nữ màu vàng nổi bật.'
    New-PilotProduct 'PILOT-SHOE-006' 'Giày futsal Uni Red Court' 'giay-futsal-uni-red-court' 'giay-the-thao' 'SPORTS_SHOES' 'UNISEX' 'FOOTBALL' 'RED' @('38','39','40','41','42','43','44') 1099000 1399000 10 'SYNTHETIC_UPPER' 'INDOOR' 'Giày futsal unisex màu đỏ với đế cao su bám sân.'

    New-PilotProduct 'PILOT-UNIFORM-001' 'Bộ bóng đá nam Crimson Unity' 'bo-bong-da-nam-crimson-unity' 'dong-phuc-the-thao' 'SPORTS_UNIFORM' 'MEN' 'FOOTBALL' 'RED' @('S','M','L','XL','XXL') 699000 849000 14 'POLYESTER_MESH' 'ATHLETIC' 'Bộ bóng đá nam màu đỏ gồm áo và quần đồng bộ.'
    New-PilotProduct 'PILOT-UNIFORM-002' 'Bộ bóng đá nữ Royal Wave' 'bo-bong-da-nu-royal-wave' 'dong-phuc-the-thao' 'SPORTS_UNIFORM' 'WOMEN' 'FOOTBALL' 'BLUE' @('XS','S','M','L','XL') 699000 849000 14 'POLYESTER_MESH' 'ATHLETIC' 'Bộ bóng đá nữ xanh dương và navy gồm áo và quần.'
    New-PilotProduct 'PILOT-UNIFORM-003' 'Bộ bóng đá Uni Forest Line' 'bo-bong-da-uni-forest-line' 'dong-phuc-the-thao' 'SPORTS_UNIFORM' 'UNISEX' 'FOOTBALL' 'GREEN' @('XS','S','M','L','XL','XXL') 729000 879000 12 'POLYESTER_MESH' 'REGULAR' 'Bộ bóng đá unisex xanh lá gồm áo và quần đồng bộ.'
    New-PilotProduct 'PILOT-UNIFORM-004' 'Bộ bóng đá nam Black Gold' 'bo-bong-da-nam-black-gold' 'dong-phuc-the-thao' 'SPORTS_UNIFORM' 'MEN' 'FOOTBALL' 'BLACK' @('S','M','L','XL','XXL') 759000 929000 12 'POLYESTER_MESH' 'ATHLETIC' 'Bộ bóng đá nam màu đen viền vàng gồm áo và quần.'
    New-PilotProduct 'PILOT-UNIFORM-005' 'Bộ bóng đá nữ Violet Star' 'bo-bong-da-nu-violet-star' 'dong-phuc-the-thao' 'SPORTS_UNIFORM' 'WOMEN' 'FOOTBALL' 'PURPLE' @('XS','S','M','L','XL') 729000 879000 12 'POLYESTER_MESH' 'ATHLETIC' 'Bộ bóng đá nữ màu tím viền trắng gồm áo và quần.'
    New-PilotProduct 'PILOT-UNIFORM-006' 'Bộ bóng đá Uni Orange Pulse' 'bo-bong-da-uni-orange-pulse' 'dong-phuc-the-thao' 'SPORTS_UNIFORM' 'UNISEX' 'FOOTBALL' 'ORANGE' @('XS','S','M','L','XL','XXL') 729000 879000 12 'POLYESTER_MESH' 'REGULAR' 'Bộ bóng đá unisex cam và đen gồm áo và quần đồng bộ.'

    New-PilotProduct 'PILOT-ACCESSORY-001' 'Bình nước thể thao Black Flow 750ml' 'binh-nuoc-the-thao-black-flow-750ml' 'phu-kien-the-thao' 'SPORTS_ACCESSORY' 'UNISEX' 'RUNNING' 'BLACK' @('ONE_SIZE') 219000 279000 30 'BPA_FREE_PLASTIC' 'ONE_SIZE' 'Bình nước thể thao màu đen dung tích 750 ml, nắp bật.'
    New-PilotProduct 'PILOT-ACCESSORY-002' 'Băng tay thể thao Royal Pair' 'bang-tay-the-thao-royal-pair' 'phu-kien-the-thao' 'SPORTS_ACCESSORY' 'UNISEX' 'RUNNING' 'BLUE' @('ONE_SIZE') 129000 169000 35 'COTTON_ELASTANE' 'ONE_SIZE' 'Cặp băng cổ tay thể thao màu xanh dương, thấm hút tốt.'
    New-PilotProduct 'PILOT-ACCESSORY-003' 'Tất bóng đá White Match' 'tat-bong-da-white-match' 'phu-kien-the-thao' 'SPORTS_ACCESSORY' 'UNISEX' 'FOOTBALL' 'WHITE' @('ONE_SIZE') 159000 199000 32 'POLYESTER_ELASTANE' 'ONE_SIZE' 'Cặp tất bóng đá cao cổ màu trắng, dệt gân và có đệm chân.'
    New-PilotProduct 'PILOT-ACCESSORY-004' 'Ốp bảo vệ ống đồng Red Guard' 'op-bao-ve-ong-dong-red-guard' 'phu-kien-the-thao' 'SPORTS_ACCESSORY' 'UNISEX' 'FOOTBALL' 'RED' @('ONE_SIZE') 249000 319000 28 'POLYPROPYLENE_EVA' 'ONE_SIZE' 'Cặp bảo vệ ống đồng bóng đá màu đỏ với lớp đệm đen.'
)

$manifest = [ordered]@{
    schemaVersion = 1
    dataset = [ordered]@{
        name = 'smart-sportswear-pilot'
        version = '1.0.0'
        dataSource = 'DEMO'
        licenseCode = 'AI_GENERATED_PROJECT_USE'
        sourceDescription = '30 fictional sportswear products and 60 synthetic product images generated for project demo, image retrieval and evaluation.'
    }
    imagePolicy = [ordered]@{
        sourceKind = 'SYNTHETIC'
        generator = 'OpenAI built-in imagegen'
        originalFormat = 'png'
        requiredWidth = 1254
        requiredHeight = 1254
        minBytes = 100000
        maxBytes = 5242880
        colorSpace = 'sRGB'
        embeddingSource = 'VERSIONED_ORIGINAL_ONLY'
        uiDelivery = 'Cloudinary/Next Image responsive delivery; square container; object-contain; never upscale above original'
    }
    products = $products
}

$manifest | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $manifestPath -Encoding utf8

$hashLines = Get-ChildItem -LiteralPath $assetRoot -File -Filter '*.png' |
    Sort-Object Name |
    ForEach-Object {
        $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLowerInvariant()
        "$hash  assets/$($_.Name)"
    }
$hashLines | Set-Content -LiteralPath $checksumPath -Encoding utf8

Write-Host "Wrote $manifestPath with $($products.Count) products."
Write-Host "Wrote $checksumPath with $($hashLines.Count) image checksums."
