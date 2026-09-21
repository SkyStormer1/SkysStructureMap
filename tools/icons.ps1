# Draws the structure icons in src/main/resources/assets/skysstructuremap/textures/structure/ from the
# pixel grids below, and a preview (8x, on light and dark ground) to the path given as the first argument.
# Every transparent pixel next to a filled one becomes the dark outline, so icons read on any map colour.
param([string]$Preview)
Add-Type -AssemblyName System.Drawing

$icons = [ordered]@{
  monument = @{
    colours = @{ T = '3FB8A8'; D = '1F6F6A'; L = 'E8F6D0' }
    rows = @(
      '................',
      '.......LL.......',
      '......DLLD......',
      '.....DTTTTD.....',
      '.....DTTTTD.....',
      '....DDDDDDDD....',
      '...DTTTTTTTTD...',
      '...DTLTTTTLTD...',
      '..DDDDDDDDDDDD..',
      '.DTTTTTTTTTTTTD.',
      '.DTLTTTTTTTTLTD.',
      '.DTTTTTDDTTTTTD.',
      '.DTTTTD..DTTTTD.',
      '.DDDDDD..DDDDDD.',
      '................',
      '................'
    )
  }
  shipwreck = @{
    colours = @{ B = '9A6B3F'; K = '5A3A1E'; M = '6B4A2A'; W = 'E6E0D0'; A = '3F76E4' }
    rows = @(
      '................',
      '.......M........',
      '.......MWW......',
      '.......MWWW.....',
      '.......MW.WW....',
      '.......MWW......',
      '.......M........',
      '.......M........',
      '.K.....M......K.',
      '.KBBBBBBBBBBBBK.',
      '..KBBKBBBBKBBK..',
      '..KBBBBBBBBBBK..',
      '...KBBBBBBBBK...',
      '....KKKKKKKK....',
      '.AA..AAA..AAA.A.',
      '................'
    )
  }
  fortress = @{
    colours = @{ N = '3A1418'; R = '8A3036'; X = 'F0A040' }
    rows = @(
      '................',
      '................',
      '.NN.NN....NN.NN.',
      '.NNNNN....NNNNN.',
      '.NRRRN....NRRRN.',
      '.NRXRNNNNNNRXRN.',
      '.NRRRNRRRRNRRRN.',
      '.NRRRNRRRRNRRRN.',
      '.NRXRNRRRRNRXRN.',
      '.NRRRNRNNRNRRRN.',
      '.NRRRNRXXRNRRRN.',
      '.NRRRNRXXRNRRRN.',
      '.NNNNNNXXNNNNNN.',
      '................',
      '................',
      '................'
    )
  }
  bastion = @{
    colours = @{ S = '24202A'; H = '4A4652'; G = 'F2C230' }
    rows = @(
      '................',
      '................',
      '.SS.SS.SS.SS.SS.',
      '.SSSSSSSSSSSSSS.',
      '.SHHHHHHHHHHHHS.',
      '.SHGHHHHHHHHGHS.',
      '.SHHHHSSSSHHHHS.',
      '.SHHHHSGGSHHHHS.',
      '.SGGGGSGGSGGGGS.',
      '.SHHHHSSSSHHHHS.',
      '.SHHHHHHHHHHHHS.',
      '.SHHHHSSSSHHHHS.',
      '.SHHHHS..SHHHHS.',
      '.SSSSSS..SSSSSS.',
      '................',
      '................'
    )
  }
  village = @{
    colours = @{ R = 'B0503A'; W = 'C8A064'; L = '6B4A2A'; D = '4A2E18'; G = '9FD8E8' }
    rows = @(
      '................',
      '.......RR.......',
      '......RRRR......',
      '.....RRRRRR.....',
      '....RRRRRRRR....',
      '...RRRRRRRRRR...',
      '..RRRRRRRRRRRR..',
      '...LWWWWWWWWL...',
      '...LWGGWWGGWL...',
      '...LWGGWWGGWL...',
      '...LWWWWWWWWL...',
      '...LWWWDDWWWL...',
      '...LWWWDDWWWL...',
      '...LLLLDDLLLL...',
      '................',
      '................'
    )
  }
  outpost = @{
    colours = @{ D = '3E2A1A'; P = 'D8C88A'; C = '7A7A7A'; F = 'E8E8E8'; K = '404040' }
    rows = @(
      '................',
      '...DDDDDDDDDD...',
      '...DPPPPPPPPD...',
      '...DDDDDDDDDD...',
      '.....DFFFFD.....',
      '.....DFKKFD.....',
      '.....DFFFFD.....',
      '.....DPFFPD.....',
      '.....DPPPPD.....',
      '.....DPPPPD.....',
      '.....DPPPPD.....',
      '.....DPPPPD.....',
      '....CCCCCCCC....',
      '...CCCCCCCCCC...',
      '................',
      '................'
    )
  }
  mansion = @{
    colours = @{ R = '2A1E14'; D = '4A3220'; W = 'D8C8A0'; G = '9FD8E8' }
    rows = @(
      '................',
      '................',
      '..RRRRRRRRRRRR..',
      '.RRRRRRRRRRRRRR.',
      '.DWWWWWWWWWWWWD.',
      '.DWGWWGWWGWWGWD.',
      '.DWWWWWWWWWWWWD.',
      '.DDDDDDDDDDDDDD.',
      '.DWWWWWWWWWWWWD.',
      '.DWGWWGWWGWWGWD.',
      '.DWWWWWDDWWWWWD.',
      '.DWWWWWDDWWWWWD.',
      '.DDDDDDDDDDDDDD.',
      '................',
      '................',
      '................'
    )
  }
  stronghold = @{
    colours = @{ F = 'C8D8A0'; E = '2E8B57'; V = '101820'; S = '7FE0FF' }
    rows = @(
      '................',
      '................',
      '..FFFFFFFFFFFF..',
      '..FFEFFEEFFEFF..',
      '..FFVVVVVVVVFF..',
      '..EFVSVVVVVVFE..',
      '..FFVVVVVSVVFF..',
      '..EFVVVVVVVVFE..',
      '..EFVVSVVVVVFE..',
      '..FFVVVVVVSVFF..',
      '..EFVVVVVVVVFE..',
      '..FFVVVVVVVVFF..',
      '..FFEFFEEFFEFF..',
      '..FFFFFFFFFFFF..',
      '................',
      '................'
    )
  }
  witch_hut = @{
    colours = @{ H = '6A2A8A'; R = '4A3420'; P = '7A5A3A'; L = '6B4A2A'; G = '7FBF5F' }
    rows = @(
      '.......H........',
      '......HHH.......',
      '.....HHHHH......',
      '...HHHHHHHHH....',
      '..RRRRRRRRRRRR..',
      '..RPPPPPPPPPPR..',
      '..RPPGGPPPPPPR..',
      '..RPPGGPPPPPPR..',
      '..RPPPPPPPPPPR..',
      '..RRRRRRRRRRRR..',
      '...L..L..L..L...',
      '...L..L..L..L...',
      '...L..L..L..L...',
      '...L..L..L..L...',
      '................',
      '................'
    )
  }
  jungle_temple = @{
    colours = @{ C = '8A8A8A'; M = '5E7A4A'; V = '3F8F2F'; D = '202020' }
    rows = @(
      '................',
      '................',
      '......CCCC......',
      '......CMMC......',
      '....CCCCCCCC....',
      '....CMCMMCMC....',
      '....CCCCCCCC....',
      '..CCCCCCCCCCCC..',
      '..CMCVMCCMVCMC..',
      '..CCCMCDDCMCCC..',
      '..CVCCCDDCCCVC..',
      '..CCCMCDDCMCCC..',
      '..CCCCCCCCCCCC..',
      '................',
      '................',
      '................'
    )
  }
  desert_temple = @{
    colours = @{ S = 'E0CC90'; O = 'C06030'; B = '3A5AA0'; D = '5A4A2A'; T = 'B8A070' }
    rows = @(
      '................',
      '.TT..........TT.',
      '.TT.....S....TT.',
      '.TT....SSS...TT.',
      '.TT...SSSSS..TT.',
      '.TT..SSSSSSS.TT.',
      '.TT.SOOOOOOOSTT.',
      '.TTSSSSBBSSSSTT.',
      '.TSSSSSBBSSSSST.',
      '.SSOOOOOOOOOOSS.',
      '.SSSSSSDDSSSSSS.',
      '.SSSSSSDDSSSSSS.',
      '.SSSSSSDDSSSSSS.',
      '................',
      '................',
      '................'
    )
  }
  trail_ruins = @{
    colours = @{ T = 'A0603A'; K = '5A3020'; Y = 'E0B050' }
    rows = @(
      '................',
      '................',
      '......KKKK......',
      '.......KK.......',
      '.....KKKKKK.....',
      '....KTTTTTTK....',
      '...KTTYTTYTTK...',
      '...KTTTTTTTTK...',
      '...KTYTTTTYTK...',
      '...KTTTYYTTTK...',
      '...KTTTTTTTTK...',
      '....KTTTTTTK....',
      '.....KKKKKK.....',
      '................',
      '................',
      '................'
    )
  }
  ancient_city = @{
    colours = @{ S = '4A4A56'; T = '2E2E38'; C = '1FC0C8'; R = '5A6A62' }
    rows = @(
      '................',
      '..SSSSSSSSSSSS..',
      '..STTTTTTTTTTS..',
      '..STRRRRRRRRTS..',
      '..STR......RTS..',
      '..STR.CCCC.RTS..',
      '..STR.CCCC.RTS..',
      '..STR.CCCC.RTS..',
      '..STR.CCCC.RTS..',
      '..STR......RTS..',
      '..STR......RTS..',
      '.SSSSS....SSSSS.',
      '.STTTS....STTTS.',
      '.SSSSS....SSSSS.',
      '................',
      '................'
    )
  }
  trial_chambers = @{
    colours = @{ C = '2E2E36'; B = '8A8A96'; F = 'F07830'; Y = 'F8D048'; T = '6A6E66'; K = '4A4E48' }
    rows = @(
      '................',
      '..CCCCCCCCCCCC..',
      '..CBCBCBCBCBCC..',
      '..CB........BC..',
      '..CC...YY...CC..',
      '..CB..YFFY..BC..',
      '..CC..FFFF..CC..',
      '..CB.FFYYFF.BC..',
      '..CC.FFFFFF.CC..',
      '..CB..FFFF..BC..',
      '..CBCBCBCBCBCC..',
      '..CCCCCCCCCCCC..',
      '.TTKTTTKTTTKTTT.',
      '.TKTTTKTTTKTTTK.',
      '................',
      '................'
    )
  }
  end_gateway = @{
    colours = @{ B = '3A3A3A'; H = '6A6A6A'; V = '0C0C18'; S = '7FE0FF'; M = 'B070E0' }
    rows = @(
      '.......MM.......',
      '.......MM.......',
      '.......MM.......',
      '....BHBHBHBH....',
      '....HBHBHBHB....',
      '....BH....BH....',
      '...HBVVVVVVBH...',
      '...BHVSVVVVHB...',
      '...HBVVVVSVBH...',
      '...BHVVSVVVHB...',
      '...HBVVVVVVBH...',
      '....BH....BH....',
      '....HBHBHBHB....',
      '....BHBHBHBH....',
      '................',
      '................'
    )
  }
  end_city = @{
    colours = @{ P = 'A070A0'; Q = 'C898C8'; E = 'E8E4B0'; G = 'D040D0' }
    rows = @(
      '................',
      '....PPPPPPPP....',
      '....PQQQQQQP....',
      '....PPPPPPPP....',
      '.....PQGGQP.....',
      '.....PQQQQP.....',
      '.....PPPPPP.....',
      '...PPPPPPPPPP...',
      '...PQQGQQGQQP...',
      '...PPPPPPPPPP...',
      '....PQQQQQQP....',
      '....PQQGGQQP....',
      '....PQQQQQQP....',
      '...EEEEEEEEEE...',
      '................',
      '................'
    )
  }
}

$outline = [System.Drawing.Color]::FromArgb(230, 20, 18, 22)
$outDir = Join-Path $PSScriptRoot '..\src\main\resources\assets\skysstructuremap\textures\structure'
New-Item -ItemType Directory -Force $outDir | Out-Null
$made = @{}

foreach ($name in $icons.Keys) {
  $icon = $icons[$name]
  if ($icon.rows.Count -ne 16) { throw "$name has $($icon.rows.Count) rows" }
  $bmp = New-Object System.Drawing.Bitmap 16, 16, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
  $filled = New-Object 'bool[,]' 16, 16
  for ($y = 0; $y -lt 16; $y++) {
    $row = $icon.rows[$y]
    if ($row.Length -ne 16) { throw "$name row $y is $($row.Length) wide" }
    for ($x = 0; $x -lt 16; $x++) {
      $c = [string]$row[$x]
      if ($c -eq '.') { continue }
      $hex = $icon.colours[$c]
      if (-not $hex) { throw "$name uses unknown colour $c" }
      $bmp.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(255, [Convert]::ToInt32($hex.Substring(0,2),16), [Convert]::ToInt32($hex.Substring(2,2),16), [Convert]::ToInt32($hex.Substring(4,2),16)))
      $filled[$x, $y] = $true
    }
  }
  for ($y = 0; $y -lt 16; $y++) { for ($x = 0; $x -lt 16; $x++) {
    if ($filled[$x, $y]) { continue }
    $near = $false
    foreach ($d in @(@(1,0),@(-1,0),@(0,1),@(0,-1))) {
      $nx = $x + $d[0]; $ny = $y + $d[1]
      if ($nx -ge 0 -and $nx -lt 16 -and $ny -ge 0 -and $ny -lt 16 -and $filled[$nx, $ny]) { $near = $true }
    }
    if ($near) { $bmp.SetPixel($x, $y, $outline) }
  } }
  $bmp.Save((Join-Path $outDir "$name.png"), [System.Drawing.Imaging.ImageFormat]::Png)
  $made[$name] = $bmp
}

if ($Preview) {
  $scale = 8; $gap = 16
  $w = $icons.Count * (16 * $scale + $gap) + $gap
  $sheet = New-Object System.Drawing.Bitmap $w, (2 * (16 * $scale + $gap) + $gap)
  $g = [System.Drawing.Graphics]::FromImage($sheet)
  $g.InterpolationMode = 'NearestNeighbor'; $g.PixelOffsetMode = 'Half'
  $g.FillRectangle((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 96, 150, 70))), 0, 0, $w, 16 * $scale + $gap + $gap / 2)
  $g.FillRectangle((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 200, 90, 30))), 0, 16 * $scale + $gap + $gap / 2, $w, $sheet.Height)
  $i = 0
  foreach ($name in $icons.Keys) {
    $x = $gap + $i * (16 * $scale + $gap)
    $g.DrawImage($made[$name], $x, $gap, 16 * $scale, 16 * $scale)
    $g.DrawImage($made[$name], $x, 2 * $gap + 16 * $scale, 16 * $scale, 16 * $scale)
    $i++
  }
  $sheet.Save($Preview)
}
"icons written to $outDir"
