$ErrorActionPreference = 'Stop'
$a = Get-Content -LiteralPath 'C:\Users\26982\Desktop\MOD\Soul Rekindling\Running\src\main\resources\blocks.json' -Raw | ConvertFrom-Json
$b = Get-Content -LiteralPath 'C:\Users\26982\Desktop\MOD\Soul Rekindling\Running\MovementSync-main\src\main\resources\blocks.json' -Raw | ConvertFrom-Json
function States($e) { $c = 1; foreach ($s in $e.states) { $c = $c * $s.num_values }; return $c }
$ha = @{}
foreach ($e in $a) { $ha[$e.name] = $e }
$diff = 0
foreach ($e in ($b | Sort-Object minStateId)) {
    if ($e.minStateId -gt 2000) { break }
    if (-not $ha.ContainsKey($e.name)) { continue }
    $ra = $ha[$e.name]
    $ca = States $ra
    $cb = States $e
    if ($ca -ne $cb) {
        $sa = ($ra.states | ForEach-Object { $_.name + ':' + $_.num_values }) -join ','
        $sb = ($e.states | ForEach-Object { $_.name + ':' + $_.num_values }) -join ','
        Write-Output ("COUNT-DIFF {0} B({1})=[{2}] A({3})=[{4}]" -f $e.name, $cb, $sb, $ca, $sa)
        $diff++
    }
}
Write-Output ("divergent-in-range-0..2000 = " + $diff)
foreach ($n in @('acacia_log','amethyst_cluster','banner','barrel','beacon','bedrock','big_dripleaf','black_bed','black_concrete')) {
    $e = $ha[$n]
    if ($null -ne $e) {
        $sa = ($e.states | ForEach-Object { $_.name + ':' + $_.num_values }) -join ','
        Write-Output ("A {0} min={1} count={2} [{3}]" -f $n, $e.minStateId, (States $e), $sa)
    }
}
