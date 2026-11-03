param([switch]$Refresh)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
Add-Type -AssemblyName System.Drawing
$root = Join-Path (Split-Path $PSScriptRoot -Parent) 'engine/src/main/resources/artwork'
New-Item -ItemType Directory -Force -Path "$root/teams", "$root/competitions" | Out-Null
$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromSeconds(30)
$client.DefaultRequestHeaders.UserAgent.ParseAdd('Futbolio-Artwork-Import/0.3')

function Save-Logo($url, $path) {
    if ((Test-Path $path) -and !$Refresh) { return }
    $uri = [Uri]$url
    if ($uri.Scheme -ne 'https' -or $uri.Host -notin @('www.tff.org','fys.tff.org')) { throw "Untrusted artwork host: $url" }
    $bytes = $client.GetByteArrayAsync($uri).GetAwaiter().GetResult()
    if ($bytes.Length -gt 8388608) { throw 'Oversized artwork' }
    $stream = New-Object System.IO.MemoryStream(,$bytes)
    $image = [System.Drawing.Image]::FromStream($stream)
    try {
        if ($image.Width -gt 8192 -or $image.Height -gt 8192) { throw 'Oversized artwork dimensions' }
        $bitmap = New-Object System.Drawing.Bitmap(192,192)
        $g = [System.Drawing.Graphics]::FromImage($bitmap)
        try {
            $g.Clear([System.Drawing.Color]::Transparent)
            $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $scale = [Math]::Min(192 / $image.Width, 192 / $image.Height)
            $w = [int]($image.Width * $scale); $h = [int]($image.Height * $scale)
            $g.DrawImage($image, [int]((192-$w)/2), [int]((192-$h)/2), $w, $h)
            $bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
        } finally { $g.Dispose(); $bitmap.Dispose() }
    } finally { $image.Dispose(); $stream.Dispose() }
}

try {
    $clubs = Get-Content (Join-Path $PSScriptRoot 'tff-artwork-clubs.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $teams = @()
    foreach ($club in $clubs) {
        $page = "https://www.tff.org/Default.aspx?pageID=28&kulupID=$($club.id)"
        $html = $client.GetStringAsync($page).GetAwaiter().GetResult()
        $match = [Regex]::Match($html, '<img\b[^>]*src="(https://fys\.tff\.org/TFFUploadFolder/KulupLogolari[^"]+)"', 'IgnoreCase')
        if (!$match.Success) { throw "No official crest found for $($club.name)" }
        $url = $match.Groups[1].Value.Replace('\','/')
        $file = "teams/tff-$($club.id).png"
        Save-Logo $url (Join-Path $root $file)
        $teams += @{ id="tff-$($club.id)"; name=$club.name; aliases=@($club.aliases); file=$file; source=$url; sourcePage=$page }
    }
    $leagueUrl = 'https://www.tff.org/Resources/TFF/Images/0000000015/TFF/TFF-Logolar/2023-trendyol/trendyol-1-lig.png'
    Save-Logo $leagueUrl (Join-Path $root 'competitions/tur.2.png')
    $manifest = @{ version=1; importedAt=[DateTime]::UtcNow.ToString('yyyy-MM-dd');
        source='https://www.tff.org/default.aspx?pageID=142';
        teams=$teams;
        competitions=@(@{ id='tur.2'; name='Trendyol 1. Lig'; file='competitions/tur.2.png'; source=$leagueUrl; lightBackground=$true; sourcePage='https://www.tff.org/default.aspx?pageID=1314' }) }
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText((Join-Path $root 'index-tff.json'), ($manifest | ConvertTo-Json -Depth 8), $encoding)
    Write-Output "Bundled $($teams.Count) TFF club crests and the Trendyol 1. Lig logo."
} finally { $client.Dispose() }
