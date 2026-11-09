# Expérience comparative (sans interface) : décentralisé (DF + Contract Net) vs centralisé (dispatcher),
# avec 1, 2, 3 et 5 taxis, et les mêmes clients générés à partir d'une graine fixe.
# Résultat : results/experience.csv (une ligne par simulation) et un tableau récapitulatif.
# Usage : .\experience.ps1 [-Duree 45] [-Clients 20] [-Seed 42]
param(
    [int]$Duree = 45,
    [int]$Clients = 20,
    [int]$Seed = 42,
    [int[]]$Flottes = @(1, 2, 3, 5),
    [string[]]$Modes = @("decentralise", "centralise")
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$cp = "lib\jade.jar"
New-Item -ItemType Directory -Force out, results | Out-Null
$sources = Get-ChildItem -Recurse src -Filter *.java | ForEach-Object { $_.FullName }
javac -encoding UTF-8 -nowarn -cp $cp -d out $sources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Remove-Item -ErrorAction SilentlyContinue results\experience.csv
# JADE écrit ses logs sur stderr : en PowerShell 5.1 il ne faut pas les traiter comme des erreurs.
$ErrorActionPreference = "Continue"
$total = $Flottes.Count * $Modes.Count; $i = 0
foreach ($mode in $Modes) {
    foreach ($n in $Flottes) {
        $i++
        Write-Host "[$i/$total] mode=$mode taxis=$n ($Duree s)..."
        java "-Dstdout.encoding=UTF-8" "-Dcity.tick=150" -cp "$cp;out" taxi.Lancement experience `
            "--taxis=$n" "--mode=$mode" "--duree=$Duree" "--seed=$Seed" "--clients=$Clients" "--periode=1200" `
            "--label=$mode-$n" "--port=$(1500 + $i)" 2>$null | Select-String "Arrivés|Attente|BILAN" | ForEach-Object { "    $_" }
    }
}

Write-Host "`nRésultats (results\experience.csv) :"
Import-Csv results\experience.csv -Delimiter ";" |
    Select-Object mode, taxis, clients, arrives, abandons,
        @{n = "abandon_%"; e = { [math]::Round([double]$_.taux_abandon * 100) } },
        attente_moy, prix_moy,
        @{n = "occupation_%"; e = { [math]::Round([double]$_.occupation_moy * 100) } },
        negociations, covoiturages |
    Format-Table -AutoSize
