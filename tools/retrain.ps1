# Retrains the evaluation network end to end and reports whether it is worth its cost.
#
# The measurement at the end is the point of the script. A network that predicts deep search results
# worse than the hand-crafted evaluation those searches used has learned nothing that evaluation does
# not already contain, and no amount of Elo tuning will rescue it. That check is cheap and runs before
# the SPRT, which is not.
#
#   pwsh tools/retrain.ps1 -Samples 700000 -Epochs 30

param(
    [string] $Data = "build/selfplay-d10.txt",
    [string] $Holdout = "build/holdout-d10.txt",
    [int]    $HoldoutSamples = 40000,
    [int]    $Epochs = 30,
    [int]    $Depth = 10,
    [double] $EndgameFraction = 0.35,
    [int]    $Concurrency = 22,
    [string] $Net = "build/net-d10.pt",
    [string] $Nnue = "build/ludus-d10.nnue"
)

$ErrorActionPreference = "Stop"
Set-Location (Split-Path $PSScriptRoot -Parent)

$jar = "ludus-tools/target/ludus-match.jar"
$python = "training/.venv/Scripts/python.exe"

function Step($text) { Write-Host "`n=== $text ===" -ForegroundColor Cyan }

if (-not (Test-Path $Data)) { throw "no training data at $Data" }

# The holdout is generated separately with its own seed rather than split off the training file.
# Positions from one game are correlated, so holding back every tenth line would leave the rest of
# each game in training and report a number better than the truth.
Step "holdout ($HoldoutSamples samples, seed independent of training)"
if (Test-Path $Holdout) {
    Write-Host "already present, keeping it"
} else {
    & java -jar $jar collect --local --samples $HoldoutSamples --games-per-job 8 `
        --depth $Depth --concurrency $Concurrency --endgame-fraction $EndgameFraction `
        --seed 31337 --out $Holdout
    if ($LASTEXITCODE -ne 0) { throw "holdout generation failed" }
}

Step "training ($Epochs epochs)"
Push-Location training
& "../$python" train.py --data "../$Data" --epochs $Epochs --out "../$Net"
$trained = $LASTEXITCODE
Pop-Location
if ($trained -ne 0) { throw "training failed" }

Step "export and quantise"
Push-Location training
& "../$python" export.py --net "../$Net" --out "../$Nnue" --fixtures ../build/nnue-fixtures.txt
$exported = $LASTEXITCODE
Pop-Location
if ($exported -ne 0) { throw "export failed" }

Step "does it beat the evaluation its labels came from?"
& java -jar $jar bench --predict $Holdout --nnue $Nnue --sample 20000

Step "speed"
& java -jar $jar bench --depth 8 --nnue $Nnue

Write-Host "`nIf the network won the prediction test, the SPRT is worth running:" -ForegroundColor Yellow
Write-Host "  java -jar $jar match --sprt --engine-a <baseline jar> --engine-b <this jar>"
Write-Host "If it did not, the SPRT will only spend hours confirming it." -ForegroundColor Yellow
