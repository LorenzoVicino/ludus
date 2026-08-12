# Ranks training configurations on a subset before committing hours to one of them.
#
# This exists because bench --predict is cheap. Without a gate that answers in a minute, choosing
# between these would mean an SPRT each - hours apiece - so in practice it would mean guessing. Short
# runs on a subset rank them; the winner then gets the full-length run.
#
# The ranking metric is the near-level band error in win-probability terms. Not the overall mean: a
# network can win the mean by being right about thoroughly winning positions, which changes no move.
#
#   pwsh tools/sweep.ps1

param(
    [string] $Data = "build/selfplay-d10.txt",
    [string] $Holdout = "build/holdout-d10.txt",
    [int]    $Limit = 200000,
    [int]    $Epochs = 10,
    [int]    $Sample = 15000
)

$ErrorActionPreference = "Stop"
Set-Location (Split-Path $PSScriptRoot -Parent)

$jar = "ludus-tools/target/ludus-match.jar"
$python = "training/.venv/Scripts/python.exe"

# lambda is the weight on the search score against the game result. The hypothesis under test: near
# level, the game result is close to a coin flip while the score is informative, so blending in the
# result injects noise exactly where accuracy decides which move gets played.
$configs = @(
    @{ name = "lambda-0.7-baseline"; lambda = 0.7 },
    @{ name = "lambda-1.0-score-only"; lambda = 1.0 },
    @{ name = "lambda-0.9"; lambda = 0.9 },
    @{ name = "lambda-0.5"; lambda = 0.5 }
)

if (-not (Test-Path $Holdout)) { throw "no holdout at $Holdout - run retrain.ps1 first" }

foreach ($config in $configs) {
    $net = "build/sweep-$($config.name).pt"
    $nnue = "build/sweep-$($config.name).nnue"

    Write-Host "`n=== $($config.name) ===" -ForegroundColor Cyan

    Push-Location training
    & "../$python" train.py --data "../$Data" --limit $Limit --epochs $Epochs `
        --lambda-score $config.lambda --out "../$net"
    $trained = $LASTEXITCODE
    if ($trained -eq 0) {
        & "../$python" export.py --net "../$net" --out "../$nnue" --fixtures ../build/nnue-fixtures.txt
        $trained = $LASTEXITCODE
    }
    Pop-Location

    if ($trained -ne 0) {
        Write-Host "$($config.name) failed to train or export, skipping" -ForegroundColor Red
        continue
    }

    & java -jar $jar bench --predict $Holdout --nnue $nnue --sample $Sample
}

Write-Host "`nRead the |label| table, bands 0-50 through 150-400, win% columns." -ForegroundColor Yellow
Write-Host "The winner is whichever beats the hand-crafted baseline by most there - or if none do,"
Write-Host "the answer is that lambda was not the problem and the next suspect is capacity or epochs."
