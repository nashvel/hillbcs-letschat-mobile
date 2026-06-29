# Generates Talipapa-branded PNG launcher assets for Android + web logo.
# Requires Windows PowerShell with System.Drawing (desktop .NET).

Add-Type -AssemblyName System.Drawing

function New-TalipapaBitmap {
    param([int]$PixelSize)

    $bmp = New-Object System.Drawing.Bitmap $PixelSize, $PixelSize
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear([System.Drawing.Color]::FromArgb(255, 13, 148, 136))

    $s = $PixelSize / 108.0
    $orangeBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 249, 115, 22))
    $awning = @(
        [System.Drawing.PointF]::new(24 * $s, 18 * $s),
        [System.Drawing.PointF]::new(84 * $s, 18 * $s),
        [System.Drawing.PointF]::new(80 * $s, 32 * $s),
        [System.Drawing.PointF]::new(28 * $s, 32 * $s)
    )
    $g.FillPolygon($orangeBrush, $awning)

    $whiteBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::White)
    $topW = 44 * $s
    $topH = 12 * $s
    $topX = 54 * $s - $topW / 2
    $topY = 40 * $s
    $g.FillRectangle($whiteBrush, $topX, $topY, $topW, $topH)

    $stemW = 16 * $s
    $stemH = 36 * $s
    $stemX = 54 * $s - $stemW / 2
    $stemY = 40 * $s + $topH
    $g.FillRectangle($whiteBrush, $stemX, $stemY, $stemW, $stemH)

    $orangeBrush.Dispose()
    $whiteBrush.Dispose()
    $g.Dispose()
    return $bmp
}

function Save-Png([System.Drawing.Bitmap]$Bmp, [string]$Path) {
    $dir = Split-Path -Parent $Path
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    $Bmp.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
}

$root = Split-Path -Parent $PSScriptRoot
$res = Join-Path $root "android\app\src\main\res"
$imgs = Join-Path $root "src\assets\imgs"

$mipmapMap = @{
    "mipmap-mdpi"    = @{ launcher = 48;  fg = 108 }
    "mipmap-hdpi"    = @{ launcher = 72;  fg = 162 }
    "mipmap-xhdpi"   = @{ launcher = 96;  fg = 216 }
    "mipmap-xxhdpi"  = @{ launcher = 144; fg = 324 }
    "mipmap-xxxhdpi" = @{ launcher = 192; fg = 432 }
}

foreach ($folder in $mipmapMap.Keys) {
    $sizes = $mipmapMap[$folder]
    $dir = Join-Path $res $folder

    $b1 = New-TalipapaBitmap -PixelSize $sizes.launcher
    Save-Png -Bmp $b1 -Path (Join-Path $dir "ic_launcher.png")
    $b1.Dispose()

    $b2 = New-TalipapaBitmap -PixelSize $sizes.launcher
    Save-Png -Bmp $b2 -Path (Join-Path $dir "ic_launcher_round.png")
    $b2.Dispose()

    $b3 = New-TalipapaBitmap -PixelSize $sizes.fg
    Save-Png -Bmp $b3 -Path (Join-Path $dir "ic_launcher_foreground.png")
    $b3.Dispose()
}

$logo512 = New-TalipapaBitmap -PixelSize 512
Save-Png -Bmp $logo512 -Path (Join-Path $imgs "logo.png")
$logo512.Dispose()

Write-Host "Talipapa launcher icons and src/assets/imgs/logo.png generated."
