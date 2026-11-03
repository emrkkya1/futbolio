param([switch]$Refresh)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
Add-Type -AssemblyName System.Drawing
$root = Join-Path (Split-Path $PSScriptRoot -Parent) 'engine/src/main/resources/artwork'
New-Item -ItemType Directory -Force -Path "$root/teams", "$root/competitions" | Out-Null
$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromSeconds(25)
$client.DefaultRequestHeaders.UserAgent.ParseAdd('Futbolio-Artwork-Import/0.3')
$leagues = @('tur.1','tur.2','tur.cup','eng.1','eng.fa','eng.league_cup','esp.1','ita.1','ger.1','fra.1','por.1','ned.1','usa.1','uefa.champions','uefa.europa','uefa.europa.conf','fifa.world','uefa.euro','uefa.euroq','uefa.nations','conmebol.america','caf.nations','afc.asian.cup','concacaf.gold')
$teams = @{}
$competitions = @()
$jobs = @()
foreach ($id in $leagues) {
    $base = "https://site.api.espn.com/apis/site/v2/sports/soccer/$id"
    $jobs += @{ Id=$id; Kind='teams'; Task=$client.GetStringAsync("$base/teams?limit=100") }
    $jobs += @{ Id=$id; Kind='league'; Task=$client.GetStringAsync("$base/scoreboard?limit=1") }
}
foreach ($job in $jobs) {
    try {
        $json = $job.Task.GetAwaiter().GetResult() | ConvertFrom-Json
        if ($job.Kind -eq 'teams') {
            foreach ($entry in $json.sports[0].leagues[0].teams) {
                $team = $entry.team
                $logo = @($team.logos | Where-Object { $_.rel -contains 'default' })[0]
                if (!$logo) { $logo = @($team.logos)[0] }
                if ($logo -and $team.id -match '^\d+$') {
                    $names = @($team.displayName, $team.shortDisplayName, $team.name, $team.nickname) | Where-Object { $_ -and $_.Length -gt 2 } | Select-Object -Unique
                    $teams[$team.id] = @{ id=$team.id; name=$team.displayName; aliases=@($names); file="teams/$($team.id).png"; source=$logo.href }
                }
            }
        } else {
            $league = @($json.leagues)[0]
            $logo = @($league.logos | Where-Object { $_.rel -contains 'dark' })[0]
            if (!$logo) { $logo = @($league.logos)[0] }
            if ($logo) { $competitions += @{ id=$job.Id; name=$league.name; file="competitions/$($job.Id).png"; source=$logo.href } }
        }
    } catch { Write-Warning "No artwork metadata for $($job.Id)/$($job.Kind)" }
}
$downloads = @()
foreach ($asset in @($teams.Values) + $competitions) {
    $path = Join-Path $root $asset.file
    if ((Test-Path $path) -and !$Refresh) { continue }
    $uri = [Uri]$asset.source
    if ($uri.Scheme -ne 'https' -or $uri.Host -ne 'a.espncdn.com') { continue }
    $downloads += @{ Asset=$asset; Task=$client.GetByteArrayAsync($uri); Path=$path }
    # Keep the importer bounded too; this script is never run by builds or the app.
    if ($downloads.Count -ge 8) {
        foreach ($download in $downloads) {
            try {
                $bytes = $download.Task.GetAwaiter().GetResult()
                if ($bytes.Length -gt 1048576) { throw 'Oversized image' }
                $stream = New-Object System.IO.MemoryStream(,$bytes)
                $image = [System.Drawing.Image]::FromStream($stream)
                try {
                    $side = 192
                    $bitmap = New-Object System.Drawing.Bitmap($side,$side)
                    $g = [System.Drawing.Graphics]::FromImage($bitmap)
                    try {
                        $g.Clear([System.Drawing.Color]::Transparent)
                        $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                        $scale = [Math]::Min($side / $image.Width, $side / $image.Height)
                        $w = [int]($image.Width * $scale); $h = [int]($image.Height * $scale)
                        $g.DrawImage($image, [int](($side-$w)/2), [int](($side-$h)/2), $w, $h)
                        $bitmap.Save($download.Path, [System.Drawing.Imaging.ImageFormat]::Png)
                    } finally { $g.Dispose(); $bitmap.Dispose() }
                } finally { $image.Dispose(); $stream.Dispose() }
            } catch { Write-Warning "Missing logo: $($download.Asset.name)" }
        }
        $downloads = @()
    }
}
# Finish the last partial batch using the same image processing path on the next invocation.
foreach ($download in $downloads) {
    try { [System.IO.File]::WriteAllBytes($download.Path, $download.Task.GetAwaiter().GetResult()) }
    catch { Write-Warning "Missing logo: $($download.Asset.name)" }
}
$availableTeams = @($teams.Values | Where-Object { Test-Path (Join-Path $root $_.file) } | Sort-Object { [int]$_.id })
$availableCompetitions = @($competitions | Where-Object { Test-Path (Join-Path $root $_.file) })
$manifest = @{ version=1; importedAt=[DateTime]::UtcNow.ToString('yyyy-MM-dd'); teams=$availableTeams; competitions=$availableCompetitions }
$encoding = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText((Join-Path $root 'index.json'), ($manifest | ConvertTo-Json -Depth 8), $encoding)
$client.Dispose()
Write-Output "Bundled $($availableTeams.Count) teams and $($availableCompetitions.Count) competitions. Review ARTWORK-NOTICES.md before distribution."
