# Does the network get better with more data, and how fast?
#
# The question this settles: is 642,000 positions the binding constraint, or is something else wrong?
# Every other hypothesis this project has entertained about the network - the teacher's depth, endgame
# coverage, training length, the blend weight, quantisation - has been measured and found not to be the
# answer. Data volume is what the design document named first and what kept being deprioritised in
# favour of more interesting ideas.
#
# A curve answers it in a way a single point cannot. If error falls steadily with data, the shortfall is
# data and the slope says roughly how much more is needed. If it has flattened, more data is wasted
# effort and the limit is capacity or the target.
#
# Measured on the float model, deliberately: quantisation is a separate error source and mixing the two
# would make the curve unreadable.
#
#   pwsh tools/learning-curve.ps1

param(
    [string] $Data = "build/selfplay-d10-clean.txt",
    [string] $Holdout = "build/holdout-d10.txt",
    [int[]]  $Sizes = @(80000, 200000, 400000, 642000),
    [int]    $Epochs = 120,
    [double] $Lambda = 0.7
)

$ErrorActionPreference = "Stop"
Set-Location (Split-Path $PSScriptRoot -Parent)
$python = "training/.venv/Scripts/python.exe"

foreach ($size in $Sizes) {
    $net = "build/curve-$size.pt"
    Write-Host "`n=== $size positions, $Epochs epochs ===" -ForegroundColor Cyan

    Push-Location training
    & "../$python" train.py --data "../$Data" --limit $size --epochs $Epochs `
        --lambda-score $Lambda --out "../$net" 2>&1 | Select-String -Pattern 'samples:|saved|train longer'
    $trained = $LASTEXITCODE
    if ($trained -eq 0) {
        Write-Host "on the holdout:"
        & "../$python" bandcheck.py "../$net" "../$Holdout"
    }
    Pop-Location
    if ($trained -ne 0) { Write-Host "$size failed" -ForegroundColor Red }
}

Write-Host "`nRead the 0-50 and 50-150 columns down the sizes." -ForegroundColor Yellow
Write-Host "Still falling at the largest size means data is the constraint."
Write-Host "Flat means it is not, and more generation would be wasted."
