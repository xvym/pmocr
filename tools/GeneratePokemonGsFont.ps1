param(
    [string]$FontDir = (Join-Path $PSScriptRoot "..\font"),
    [string]$JavaFile = (Join-Path $PSScriptRoot "..\src\main\java\PokemonGsFont.java"),
    [string]$BinaryFile = (Join-Path $PSScriptRoot "..\generated\pokemon_gs_font_1bpp.bin"),
    [string]$TextFile = (Join-Path $PSScriptRoot "..\generated\pokemon_gs_font_1bpp.txt")
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Add-Type -AssemblyName System.Drawing

function Test-ForegroundPixel {
    param([System.Drawing.Color]$Color)

    return $Color.A -gt 0 -and
        $Color.G -gt 80 -and
        $Color.G -gt ($Color.R * 1.4) -and
        $Color.G -gt ($Color.B * 1.4)
}

function Convert-ImageToMatrix {
    param([string]$Path)

    $bitmap = [System.Drawing.Bitmap]::FromFile($Path)
    try {
        $matrix = New-Object "byte[,]" 8, 8

        for ($row = 0; $row -lt 8; $row++) {
            $y0 = [int][Math]::Floor($row * $bitmap.Height / 8.0)
            $y1 = [int][Math]::Floor(($row + 1) * $bitmap.Height / 8.0) - 1

            for ($col = 0; $col -lt 8; $col++) {
                $x0 = [int][Math]::Floor($col * $bitmap.Width / 8.0)
                $x1 = [int][Math]::Floor(($col + 1) * $bitmap.Width / 8.0) - 1
                $foreground = 0
                $total = 0

                for ($y = $y0; $y -le $y1; $y++) {
                    for ($x = $x0; $x -le $x1; $x++) {
                        if (Test-ForegroundPixel $bitmap.GetPixel($x, $y)) {
                            $foreground++
                        }
                        $total++
                    }
                }

                if (($foreground / $total) -ge 0.25) {
                    $matrix[$row, $col] = 1
                }
            }
        }

        return ,$matrix
    }
    finally {
        $bitmap.Dispose()
    }
}

function Convert-MatrixToTile {
    param([byte[,]]$Matrix)

    $tile = New-Object byte[] 8
    for ($row = 0; $row -lt 8; $row++) {
        $value = 0
        for ($col = 0; $col -lt 8; $col++) {
            if ($Matrix[$row, $col] -ne 0) {
                $value = $value -bor (1 -shl (7 - $col))
            }
        }
        $tile[$row] = [byte]$value
    }
    return ,$tile
}

function Escape-JavaString {
    param([string]$Value)

    return $Value.Replace("\", "\\").Replace('"', '\"')
}

function Format-CodePoint {
    param([string]$Value)

    $codePoints = New-Object System.Collections.Generic.List[string]
    for ($i = 0; $i -lt $Value.Length; $i++) {
        $codePoint = [int][char]$Value[$i]
        if ([char]::IsHighSurrogate($Value[$i]) -and ($i + 1) -lt $Value.Length) {
            $codePoint = [char]::ConvertToUtf32($Value[$i], $Value[$i + 1])
            $i++
        }
        $codePoints.Add(("U+{0:X4}" -f $codePoint))
    }
    return [string]::Join(" ", $codePoints)
}

$fontDirPath = [System.IO.Path]::GetFullPath($FontDir)
$javaFilePath = [System.IO.Path]::GetFullPath($JavaFile)
$binaryFilePath = [System.IO.Path]::GetFullPath($BinaryFile)
$textFilePath = [System.IO.Path]::GetFullPath($TextFile)

$files = @([System.IO.Directory]::EnumerateFiles($fontDirPath, "*.png"))
[Array]::Sort($files, [StringComparer]::Ordinal)

$entries = New-Object System.Collections.Generic.List[object]
foreach ($file in $files) {
    $character = [System.IO.Path]::GetFileNameWithoutExtension($file)
    $matrix = Convert-ImageToMatrix $file
    $tile = Convert-MatrixToTile $matrix

    $entries.Add([PSCustomObject]@{
        Character = $character
        Matrix = $matrix
        Tile = $tile
    })
}

[System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($javaFilePath)) | Out-Null
[System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($binaryFilePath)) | Out-Null
[System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($textFilePath)) | Out-Null

$java = New-Object System.Text.StringBuilder
[void]$java.AppendLine("import java.util.Collections;")
[void]$java.AppendLine("import java.util.LinkedHashMap;")
[void]$java.AppendLine("import java.util.Map;")
[void]$java.AppendLine()
[void]$java.AppendLine("/**")
[void]$java.AppendLine(" * 8x8 dot matrices converted from font/*.png.")
[void]$java.AppendLine(" * 1bpp tile rows use bit 7 as the leftmost pixel.")
[void]$java.AppendLine(" */")
[void]$java.AppendLine("public final class PokemonGsFont {")
[void]$java.AppendLine("    public static final int WIDTH = 8;")
[void]$java.AppendLine("    public static final int HEIGHT = 8;")
[void]$java.AppendLine("    public static final int TILE_BYTES = 8;")
[void]$java.AppendLine("    public static final int CHARACTER_COUNT = $($entries.Count);")
[void]$java.AppendLine()
[void]$java.AppendLine("    public static final String[] CHARACTERS = {")
for ($i = 0; $i -lt $entries.Count; $i++) {
    $suffix = if ($i -eq $entries.Count - 1) { "" } else { "," }
    [void]$java.AppendLine(('        "{0}"{1}' -f (Escape-JavaString $entries[$i].Character), $suffix))
}
[void]$java.AppendLine("    };")
[void]$java.AppendLine()
[void]$java.AppendLine("    public static final byte[][][] DOT_MATRICES = {")
for ($i = 0; $i -lt $entries.Count; $i++) {
    $entry = $entries[$i]
    $matrix = $entry.Matrix
    [void]$java.AppendLine(('        {{ // {0}' -f (Escape-JavaString $entry.Character)))
    for ($row = 0; $row -lt 8; $row++) {
        $values = New-Object System.Collections.Generic.List[string]
        for ($col = 0; $col -lt 8; $col++) {
            $cell = $matrix[$row,$col]
            $values.Add([string]$cell)
        }
        $rowSuffix = if ($row -eq 7) { "" } else { "," }
        [void]$java.AppendLine(('            {{ {0} }}{1}' -f ([string]::Join(", ", $values)), $rowSuffix))
    }
    $entrySuffix = if ($i -eq $entries.Count - 1) { "" } else { "," }
    [void]$java.AppendLine(('        }}{0}' -f $entrySuffix))
}
[void]$java.AppendLine("    };")
[void]$java.AppendLine()
[void]$java.AppendLine("    public static final byte[][] TILE_1BPP = {")
for ($i = 0; $i -lt $entries.Count; $i++) {
    $entry = $entries[$i]
    $values = New-Object System.Collections.Generic.List[string]
    foreach ($value in $entry.Tile) {
        $values.Add(("(byte) 0x{0:X2}" -f $value))
    }
    $suffix = if ($i -eq $entries.Count - 1) { "" } else { "," }
    [void]$java.AppendLine(('        {{ {0} }}{1} // {2}' -f ([string]::Join(", ", $values)), $suffix, (Escape-JavaString $entry.Character)))
}
[void]$java.AppendLine("    };")
[void]$java.AppendLine()
[void]$java.AppendLine("    public static final Map<String, Integer> INDEX_BY_CHARACTER = createIndex();")
[void]$java.AppendLine()
[void]$java.AppendLine("    private PokemonGsFont() {")
[void]$java.AppendLine("    }")
[void]$java.AppendLine()
[void]$java.AppendLine("    public static byte[][] matrixOf(String character) {")
[void]$java.AppendLine("        return DOT_MATRICES[indexOf(character)];")
[void]$java.AppendLine("    }")
[void]$java.AppendLine()
[void]$java.AppendLine("    public static byte[] tileOf(String character) {")
[void]$java.AppendLine("        return TILE_1BPP[indexOf(character)];")
[void]$java.AppendLine("    }")
[void]$java.AppendLine()
[void]$java.AppendLine("    public static int indexOf(String character) {")
[void]$java.AppendLine("        Integer index = INDEX_BY_CHARACTER.get(character);")
[void]$java.AppendLine("        if (index == null) {")
[void]$java.AppendLine('            throw new IllegalArgumentException("Unsupported character: " + character);')
[void]$java.AppendLine("        }")
[void]$java.AppendLine("        return index;")
[void]$java.AppendLine("    }")
[void]$java.AppendLine()
[void]$java.AppendLine("    private static Map<String, Integer> createIndex() {")
[void]$java.AppendLine("        LinkedHashMap<String, Integer> index = new LinkedHashMap<>();")
[void]$java.AppendLine("        for (int i = 0; i < CHARACTERS.length; i++) {")
[void]$java.AppendLine("            index.put(CHARACTERS[i], i);")
[void]$java.AppendLine("        }")
[void]$java.AppendLine("        return Collections.unmodifiableMap(index);")
[void]$java.AppendLine("    }")
[void]$java.AppendLine("}")

$utf8NoBom = New-Object System.Text.UTF8Encoding $false
[System.IO.File]::WriteAllText($javaFilePath, $java.ToString(), $utf8NoBom)

$binary = New-Object System.Collections.Generic.List[byte]
foreach ($entry in $entries) {
    foreach ($value in $entry.Tile) {
        $binary.Add($value)
    }
}
[System.IO.File]::WriteAllBytes($binaryFilePath, $binary.ToArray())

$text = New-Object System.Text.StringBuilder
[void]$text.AppendLine("# Pokemon Gold/Silver Japanese font 1bpp tile data")
[void]$text.AppendLine("# Order matches PokemonGsFont.CHARACTERS and pokemon_gs_font_1bpp.bin.")
[void]$text.AppendLine("# One tile is 8 bytes; one byte per row; bit 7 is the leftmost pixel.")
for ($i = 0; $i -lt $entries.Count; $i++) {
    $entry = $entries[$i]
    $matrix = $entry.Matrix
    $hex = New-Object System.Collections.Generic.List[string]
    foreach ($value in $entry.Tile) {
        $hex.Add(("0x{0:X2}" -f $value))
    }

    [void]$text.AppendLine()
    [void]$text.AppendLine(("{0:D3} {1} {2}: {3}" -f $i, (Format-CodePoint $entry.Character), $entry.Character, [string]::Join(" ", $hex)))
    for ($row = 0; $row -lt 8; $row++) {
        $line = New-Object System.Text.StringBuilder
        for ($col = 0; $col -lt 8; $col++) {
            [void]$line.Append($(if ($matrix[$row,$col] -eq 1) { "#" } else { "." }))
        }
        [void]$text.AppendLine($line.ToString())
    }
}
[System.IO.File]::WriteAllText($textFilePath, $text.ToString(), $utf8NoBom)

Write-Host "Converted $($entries.Count) glyphs."
Write-Host "Java: $javaFilePath"
Write-Host "1bpp binary: $binaryFilePath"
Write-Host "1bpp text: $textFilePath"
