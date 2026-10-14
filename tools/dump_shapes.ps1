$ErrorActionPreference = 'Stop'
$root = 'C:\Users\26982\Desktop\MOD\Soul Rekindling\Running'
$blocksPath = Join-Path $root 'src\main\resources\blocks.json'
$binPath = Join-Path $root 'MovementSync-main\src\main\resources\collision_shapes.bin'

$blocks = (Get-Content $blocksPath -Raw) | ConvertFrom-Json
$bytes = [System.IO.File]::ReadAllBytes($binPath)

# Header: >4sII  magic, version, stateCount
$magic = [System.Text.Encoding]::ASCII.GetString($bytes[0..3])
function BE-Int($b, $o) {
    return ($b[$o] * 16777216) + ($b[$o+1] * 65536) + ($b[$o+2] * 256) + $b[$o+3]
}
function BE-UByte($b, $o) { return $b[$o] }
function BE-Float($b, $o) {
    $tmp = @($b[$o+3], $b[$o+2], $b[$o+1], $b[$o])
    return [BitConverter]::ToSingle([byte[]]$tmp, 0)
}

$ver = BE-Int $bytes 4
$sc = BE-Int $bytes 8
Write-Output ("magic=" + $magic + " version=" + $ver + " stateCount=" + $sc)

# Build per-state offset table by walking the file
$offsets = New-Object 'int[]' ($sc + 1)
$off = 12
for ($s = 0; $s -lt $sc; $s++) {
    $offsets[$s] = $off
    $c = BE-UByte $bytes $off
    $off += 1 + $c * 24
}
$offsets[$sc] = $off
Write-Output ("totalBytes=" + $bytes.Length + " walkedEnd=" + $off)

$targets = @('short_grass','grass','stone_slab','oak_trapdoor','lever','oak_fence','oak_fence_gate','cobblestone_wall','snow','carpet','white_bed','farmland','stone_pressure_plate','oak_button','glass_pane','iron_bars','ladder','chest','anvil','end_rod','cobweb','vine','big_dripleaf','small_flower','tall_grass')

foreach ($t in $targets) {
    $blk = $blocks | Where-Object { $_.name -eq $t } | Select-Object -First 1
    if ($null -eq $blk) { Write-Output ("--- " + $t + " : NOT IN blocks.json"); continue }
    Write-Output ("--- " + $t + "  id=" + $blk.id + " bbox=" + $blk.boundingBox + " states=" + ($blk.maxStateId - $blk.minStateId + 1) + " defaultState=" + $blk.defaultState)
    $lo = $blk.minStateId; $hi = $blk.maxStateId
    $shown = 0
    for ($s = $lo; $s -le $hi -and $shown -lt 12; $s++) {
        $p = $offsets[$s]
        $c = BE-UByte $bytes $p
        if ($c -eq 0) { continue }
        $p += 1
        $parts = @()
        for ($i = 0; $i -lt $c; $i++) {
            $x0 = BE-Float $bytes ($p + $i*24 + 0)
            $y0 = BE-Float $bytes ($p + $i*24 + 4)
            $z0 = BE-Float $bytes ($p + $i*24 + 8)
            $x1 = BE-Float $bytes ($p + $i*24 + 12)
            $y1 = BE-Float $bytes ($p + $i*24 + 16)
            $z1 = BE-Float $bytes ($p + $i*24 + 20)
            $px = @($x0*16, $y0*16, $z0*16, $x1*16, $y1*16, $z1*16) | ForEach-Object { [Math]::Round($_, 3) }
            $parts += ('[px ' + ($px -join ' ') + ']')
        }
        $sx = ($s - $lo)
        Write-Output ("   state+" + $sx + " (sid=" + $s + ") boxes=" + $c + " " + ($parts -join ' '))
        $shown++
    }
    $emptyCount = 0
    for ($s = $lo; $s -le $hi; $s++) { if ((BE-UByte $bytes $offsets[$s]) -eq 0) { $emptyCount++ } }
    Write-Output ("   (empty-collision states: " + $emptyCount + " / " + ($hi-$lo+1) + ")")
}
