# Builds the animation in the README, reproducibly, from a running service.
#
# Why this exists rather than somebody recording their screen: the media in a README goes stale the moment
# the interface changes, and a hand-made recording can only be refreshed by the person who made it. This
# plays a game through the API, photographs the page after each move with headless Chrome, and assembles
# the frames â€” so anyone can regenerate it with one command after changing the design.
#
# It leans on the page accepting ?game=<id>, which is a real feature (a game is a URL you can share or
# reload) rather than a hook added for this script.
#
#   docker compose --profile demo up -d
#   pwsh tools/capture-readme-gif.ps1

param(
    [string]   $BaseUrl = "http://localhost:8080",
    [string]   $Out = "docs/play.gif",
    [string]   $Difficulty = "CLUB",
    # A short, recognisable opening. Each is played only if it is legal in the position reached, so the
    # engine's own choices cannot break the run.
    [string[]] $Moves = @("e2e4", "g1f3", "f1c4", "e1g1", "d2d3"),
    [int]      $Width = 1040,
    [int]      $Height = 880,
    [int]      $FrameMs = 1400
)

# Chrome reports "N bytes written to file" on stderr even when it succeeds, and with Stop that counts as a
# fatal error. Failures are detected explicitly instead - by checking that the screenshot exists.
$ErrorActionPreference = "Continue"
Set-Location (Split-Path $PSScriptRoot -Parent)

$chrome = @(
    "$env:ProgramFiles\Google\Chrome\Application\chrome.exe",
    "${env:ProgramFiles(x86)}\Google\Chrome\Application\chrome.exe",
    "$env:LOCALAPPDATA\Google\Chrome\Application\chrome.exe"
) | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $chrome) { throw "Chrome not found; needed for the headless screenshots" }

$profileDir = Join-Path $env:TEMP "ludus-capture-profile"
$frames = Join-Path (Get-Location) "build\frames"
if (Test-Path $frames) { Get-ChildItem $frames -Filter *.png | ForEach-Object { $_.Delete() } }
New-Item -ItemType Directory -Force $frames | Out-Null

function Api($method, $path, $body) {
    $args = @{ UseBasicParsing = $true; Uri = "$BaseUrl$path"; Method = $method }
    if ($body) { $args.ContentType = 'application/json'; $args.Body = $body }
    $response = Invoke-WebRequest @args
    $text = if ($response.Content -is [byte[]]) { [Text.Encoding]::UTF8.GetString($response.Content) }
            else { $response.Content }
    return $text | ConvertFrom-Json
}

function Shoot($url, $index) {
    $path = Join-Path $frames ("{0:d2}.png" -f $index)
    # A fresh browser per frame. Slower than driving one, and it means a frame is exactly what a visitor
    # would see if they opened that URL â€” no state left over from the frame before it.
    # An isolated profile per run. Without it Chrome refuses to start headless while the user's own
    # Chrome holds the default profile, and the only symptom is a screenshot that never appears.
    & $chrome --headless=new --disable-gpu --hide-scrollbars --force-color-profile=srgb `
        --user-data-dir="$profileDir" --no-first-run --no-default-browser-check `
        --window-size="$Width,$Height" --virtual-time-budget=4000 `
        --screenshot="$path" $url | Out-Null
    if (-not (Test-Path $path)) { throw "no screenshot produced for $url" }
    return $path
}

Write-Host "Starting a game at $Difficulty" -ForegroundColor Cyan
$game = Api POST "/api/games" "{`"difficulty`":`"$Difficulty`",`"playAsWhite`":true}"
$url = "$BaseUrl/?game=$($game.id)"

$frame = 0
Shoot $url $frame | Out-Null
Write-Host ("  frame {0}: the opening position" -f $frame)

foreach ($wanted in $Moves) {
    $state = Api GET "/api/games/$($game.id)"
    if ($state.status -ne 'IN_PROGRESS') { break }

    # Fall back to whatever is legal rather than failing: the engine chooses black's moves, so a scripted
    # line cannot be guaranteed to stay available.
    $move = if ($state.legalMoves -contains $wanted) { $wanted } else { $state.legalMoves[0] }

    $result = Api POST "/api/games/$($game.id)/moves" "{`"move`":`"$move`"}"
    $frame++
    Shoot $url $frame | Out-Null
    Write-Host ("  frame {0}: {1}, answered {2} at depth {3}" -f `
        $frame, $move, $result.engineMove.move, $result.engineMove.depth)
}

Write-Host "Assembling $Out" -ForegroundColor Cyan
$python = "training/.venv/Scripts/python.exe"
& $python tools/assemble_gif.py $frames $Out $FrameMs
Write-Host ("{0}: {1:N0} KB" -f $Out, ((Get-Item $Out).Length / 1KB)) -ForegroundColor Green
