param([Parameter(Mandatory = $true)][string]$Portal)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$root = Join-Path $PSScriptRoot '../engine/src/main/resources/artwork'
$destination = Join-Path $root 'channels'
New-Item -ItemType Directory -Force $destination | Out-Null
$channels = @(
    @('bein-sport-1', 'beIN 1|beIN Sports 1|beIN Sport 1'),
    @('bein-sport-2', 'beIN 2|beIN Sports 2|beIN Sport 2'),
    @('bein-sport-3', 'beIN 3|beIN Sports 3|beIN Sport 3'),
    @('bein-sport-4', 'beIN 4|beIN Sports 4|beIN Sport 4'),
    @('bein-sport-5', 'beIN 5|beIN Sports 5|beIN Sport 5'),
    @('bein-sport-1-max', 'beIN Max 1|beIN Sports Max 1|beIN Sport Max 1'),
    @('bein-sport-2-max', 'beIN Max 2|beIN Sports Max 2|beIN Sport Max 2'),
    @('s-sport', 'S Sport|S Sport 1'), @('s-sport-2', 'S Sport 2'),
    @('tivibu-spor-1', 'Tivibu Spor|Tivibu Spor 1'), @('tivibu-spor-2', 'Tivibu Spor 2'),
    @('tivibu-spor-3', 'Tivibu Spor 3'), @('tivibu-spor-4', 'Tivibu Spor 4'),
    @('spor-smart', 'Spor Smart|Spor Smart 1|Smart Spor|Smart Spor 1'),
    @('spor-smart-2', 'Spor Smart 2|Smart Spor 2'), @('nba-tv', 'NBA TV'),
    @('trt-1', 'TRT 1'), @('trt-2', 'TRT 2'), @('trt-spor', 'TRT Spor'),
    @('trt-yildiz', 'TRT Yildiz|TRT Spor Yildiz'), @('a-spor', 'A Spor'),
    @('tv8bucuk', 'TV 8.5|TV8.5|TV 8 Bucuk|TV8 Bucuk'),
    @('eurosport-1', 'Eurosport|Eurosport 1'), @('eurosport-2', 'Eurosport 2')
)
$rows = foreach ($channel in $channels) {
    $name = $channel[0]
    $url = $Portal.TrimEnd('/') + '/lib/img/channels/' + $name + '.png'
    $file = Join-Path $destination "$name.png"
    Invoke-WebRequest -UseBasicParsing $url -OutFile $file
    $image = [System.Drawing.Image]::FromFile($file)
    try {
        if ($image.Width -gt 2048 -or $image.Height -gt 2048 -or (Get-Item $file).Length -gt 1048576) { throw "Artwork exceeds bounds: $name" }
    } finally { $image.Dispose() }
    [ordered]@{ file = "channels/$name.png"; aliases = @($channel[1].Split('|')); source = $url; sha256 = (Get-FileHash $file -Algorithm SHA256).Hash.ToLowerInvariant() }
}
@{ channels = @($rows) } | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 (Join-Path $root 'index-channels.json')
Write-Output "Imported $($rows.Count) channel logos; review ARTWORK-NOTICES.md before redistribution."
