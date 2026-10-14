$ErrorActionPreference = 'Stop'
$root = 'C:\Users\26982\Desktop\MOD\Soul Rekindling\Running\tools'
$lines = Get-Content (Join-Path $root 'shapes_audit.txt')

# 解析 RESULT 行
$records = @()
foreach ($ln in $lines) {
    if ($ln -notmatch 'RESULT "([^"]+)" bbox=(\w+) states=(\d+) noCollision=(\d+) withCollision=(\d+) maxLocalY=([\d.]+)') { continue }
    $records += [pscustomobject]@{
        Name    = $Matches[1]
        Bbox    = $Matches[2]
        States  = [int]$Matches[3]
        NoCol   = [int]$Matches[4]
        WithCol = [int]$Matches[5]
        MaxY    = [double]$Matches[6]
    }
}
Write-Output ('parsed=' + $records.Count)

# 插件当前的无碰撞名称白名单（与 BlockNameTable.NON_SOLID_NAMES 同步）
$nonSolid = @(
'air','cave_air','void_air','water','lava','bubble_column','short_grass','tall_grass','fern','large_fern',
'dead_bush','seagrass','tall_seagrass','sugar_cane','kelp','kelp_plant','sweet_berry_bush','nether_wart',
'crimson_roots','warped_roots','nether_sprouts','crimson_fungus','warped_fungus','brown_mushroom','red_mushroom',
'chorus_plant','cobweb','ladder','tripwire','tripwire_hook','dandelion','poppy','blue_orchid','allium',
'azure_bluet','red_tulip','orange_tulip','white_tulip','pink_tulip','oxeye_daisy','cornflower',
'lily_of_the_valley','wither_rose','sunflower','lilac','rose_bush','peony','pink_petals','torchflower',
'pitcher_plant','spore_blossom','torch','soul_torch','redstone_torch','redstone_wire','lever','fire','soul_fire',
'vine','glow_lichen','sculk_vein','rail','powered_rail','detector_rail','activator_rail','lily_pad','wheat',
'carrots','potatoes','beetroots','melon_stem','pumpkin_stem','attached_melon_stem','attached_pumpkin_stem',
'torchflower_crop','pitcher_crop','cave_vines','cave_vines_plant','weeping_vines','weeping_vines_plant',
'twisting_vines','twisting_vines_plant','hanging_roots','bamboo_sapling','nether_portal','end_portal',
'end_gateway','light','structure_void','bush','short_dry_grass','tall_dry_grass','golden_dandelion',
'cactus_flower','resin_clump','wildflowers','leaf_litter','pale_hanging_moss','firefly_bush','open_eyeblossom',
'closed_eyeblossom','powder_snow','frogspawn','small_dripleaf','big_dripleaf_stem','mangrove_propagule',
'moving_piston')
$suffix = @('_sapling','_sign','_banner','_torch','_coral','_coral_fan','_wall_fan')

function Test-NonSolid($n) {
    if ($n.StartsWith('potted_')) { return $false }
    if ($nonSolid -contains $n) { return $true }
    foreach ($s in $suffix) { if ($n.EndsWith($s)) { return $true } }
    return $false
}

Write-Output '=== [A] 白名单声称无碰撞，但真值表有碰撞（接入后会变成阻挡）==='
$records | Where-Object { (Test-NonSolid $_.Name) -and $_.WithCol -gt 0 } |
    ForEach-Object { Write-Output ('  ' + $_.Name + '  withCol=' + $_.WithCol + '/' + $_.States + '  maxLocalY=' + $_.MaxY) }

Write-Output '=== [B] blocks.json 标 block，但真值表所有状态都无碰撞（此前被当整方块）==='
$b = $records | Where-Object { $_.Bbox -eq 'block' -and $_.WithCol -eq 0 }
Write-Output ('  count=' + $b.Count)
$b | ForEach-Object { Write-Output ('  ' + $_.Name) }

Write-Output '=== [C] blocks.json 标 empty，但真值表有碰撞（此前被当成可穿过）==='
$c = $records | Where-Object { $_.Bbox -eq 'empty' -and $_.WithCol -gt 0 }
Write-Output ('  count=' + $c.Count)
$c | ForEach-Object { Write-Output ('  ' + $_.Name + '  withCol=' + $_.WithCol + '/' + $_.States + '  maxLocalY=' + $_.MaxY) }

Write-Output '=== [D] 同名字方块的 noCollision 与 withCollision 混合（形状随状态变化）==='
$records | Where-Object { $_.NoCol -gt 0 -and $_.WithCol -gt 0 } |
    ForEach-Object { Write-Output ('  ' + $_.Name + '  no=' + $_.NoCol + ' yes=' + $_.WithCol) }
