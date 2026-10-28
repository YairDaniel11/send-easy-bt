# יוצר את אייקון התוכנה (app.ico) ואת תמונות החבילה: ריבוע כחול מעוגל עם סמל בלוטוס לבן.
Add-Type -AssemblyName System.Drawing
$root = Split-Path $PSScriptRoot -Parent
$assets = Join-Path $root 'src\SendEasy.App\Assets'
New-Item -ItemType Directory -Force $assets | Out-Null

function New-IconBitmap([int]$size) {
    $bmp = New-Object System.Drawing.Bitmap $size, $size
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $g.Clear([System.Drawing.Color]::Transparent)
    $r = [single]($size * 0.22)
    $d = $r * 2
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $path.AddArc(0, 0, $d, $d, 180, 90)
    $path.AddArc($size - $d - 1, 0, $d, $d, 270, 90)
    $path.AddArc($size - $d - 1, $size - $d - 1, $d, $d, 0, 90)
    $path.AddArc(0, $size - $d - 1, $d, $d, 90, 90)
    $path.CloseFigure()
    $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush ([System.Drawing.Point]::new(0, 0)), ([System.Drawing.Point]::new($size, $size)), ([System.Drawing.Color]::FromArgb(0, 120, 215)), ([System.Drawing.Color]::FromArgb(0, 84, 166))
    $g.FillPath($brush, $path)
    $pen = New-Object System.Drawing.Pen ([System.Drawing.Color]::White), ([single]([math]::Max(1.2, $size * 0.075)))
    $pen.LineJoin = 'Round'; $pen.StartCap = 'Round'; $pen.EndCap = 'Round'
    function P([double]$x, [double]$y) { [System.Drawing.PointF]::new([single]($x * $size), [single]($y * $size)) }
    # סמל בלוטוס: קו אנכי ושני "משולשים"
    $g.DrawLine($pen, (P 0.5 0.16), (P 0.5 0.84))
    $g.DrawLines($pen, [System.Drawing.PointF[]]@((P 0.5 0.16), (P 0.73 0.36), (P 0.27 0.66)))
    $g.DrawLines($pen, [System.Drawing.PointF[]]@((P 0.5 0.84), (P 0.73 0.64), (P 0.27 0.34)))
    $g.Dispose()
    return $bmp
}

# קובץ ico עם תמונות PNG בכמה גדלים
$sizes = 16, 24, 32, 48, 64, 128, 256
$pngs = @()
foreach ($s in $sizes) {
    $bmp = New-IconBitmap $s
    if ($s -ge 64) {
        $ms = New-Object System.IO.MemoryStream
        $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
        $pngs += , $ms.ToArray()
    } else {
        # גדלים קטנים כ-DIB קלאסי: התפריטים והמערכת קוראים אותו בצורה אמינה יותר מ-PNG
        $ms = New-Object System.IO.MemoryStream
        $bw = New-Object System.IO.BinaryWriter $ms
        $bw.Write([uint32]40); $bw.Write([int32]$s); $bw.Write([int32]($s * 2)); $bw.Write([uint16]1); $bw.Write([uint16]32)
        $bw.Write([uint32]0); $bw.Write([uint32]($s * $s * 4)); $bw.Write([int32]0); $bw.Write([int32]0); $bw.Write([uint32]0); $bw.Write([uint32]0)
        for ($y = $s - 1; $y -ge 0; $y--) { for ($x = 0; $x -lt $s; $x++) { $c = $bmp.GetPixel($x, $y); $bw.Write([byte]$c.B); $bw.Write([byte]$c.G); $bw.Write([byte]$c.R); $bw.Write([byte]$c.A) } }
        $maskRow = [int]([math]::Ceiling($s / 32.0) * 4)
        $bw.Write((New-Object byte[] ($maskRow * $s)))
        $bw.Flush()
        $pngs += , $ms.ToArray()
    }
    $bmp.Dispose()
}
$out = New-Object System.IO.MemoryStream
$w = New-Object System.IO.BinaryWriter $out
$w.Write([uint16]0); $w.Write([uint16]1); $w.Write([uint16]$sizes.Count)
$offset = 6 + 16 * $sizes.Count
for ($i = 0; $i -lt $sizes.Count; $i++) {
    $s = $sizes[$i]
    $w.Write([byte]($(if ($s -ge 256) { 0 } else { $s })))
    $w.Write([byte]($(if ($s -ge 256) { 0 } else { $s })))
    $w.Write([byte]0); $w.Write([byte]0)
    $w.Write([uint16]1); $w.Write([uint16]32)
    $w.Write([uint32]$pngs[$i].Length); $w.Write([uint32]$offset)
    $offset += $pngs[$i].Length
}
foreach ($p in $pngs) { $w.Write($p) }
[IO.File]::WriteAllBytes((Join-Path $assets 'app.ico'), $out.ToArray())

# תמונות לחבילת ה-MSIX
foreach ($s in 44, 150) { $b = New-IconBitmap $s; $b.Save((Join-Path $PSScriptRoot "logo$s.png"), [System.Drawing.Imaging.ImageFormat]::Png); $b.Dispose() }
Write-Host "icon ready: $assets\app.ico"
