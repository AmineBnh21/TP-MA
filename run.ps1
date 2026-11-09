# Compile le projet puis lance un scénario.
# Usage : .\run.ps1 <scenario> [-NoGui] [-Mode centralise|decentralise]
#
#   Exercices :  gui | hello | comm | booktrading
#   Taxis     :  nominal | concurrence | reservation | budget | negociation | carburant | profils
#                revision | covoiturage | trafic | centralise | ville | fipa | multijvm
param(
    [Parameter(Position = 0)][string]$Scenario = "nominal",
    [switch]$NoGui,
    [string]$Mode = ""
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$exercices = @{
    "gui"         = ""
    "hello"       = "hello:firstAgent.HelloAgent(Amine,TP-JADE)"
    "comm"        = "receiver:communication.ReceiverAgent;sender:communication.SenderAgent(receiver)"
    "booktrading" = "seller1:examples.bookTrading.BookSellerAgent;seller2:examples.bookTrading.BookSellerAgent;buyer1:examples.bookTrading.BookBuyerAgent(Le-Petit-Prince)"
}
$taxis = @("nominal", "concurrence", "reservation", "budget", "negociation", "carburant", "profils",
           "revision", "covoiturage", "trafic", "centralise", "ville", "fipa", "multijvm")

if (-not ($exercices.ContainsKey($Scenario) -or $taxis -contains $Scenario)) {
    Write-Host "Scénario inconnu : $Scenario"
    Write-Host "Exercices : $($exercices.Keys -join ', ')"
    Write-Host "Taxis     : $($taxis -join ', ')"
    exit 1
}

$cp = "lib\jade.jar"
New-Item -ItemType Directory -Force out | Out-Null
$sources = Get-ChildItem -Recurse src -Filter *.java | ForEach-Object { $_.FullName }
javac -encoding UTF-8 -nowarn -cp $cp -d out $sources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$jcp = "$cp;out"

if ($exercices.ContainsKey($Scenario)) {
    $jadeArgs = @()
    if (-not $NoGui) { $jadeArgs += "-gui" }
    if ($exercices[$Scenario] -ne "") { $jadeArgs += @("-agents", $exercices[$Scenario]) }
    java "-Dstdout.encoding=UTF-8" -cp $jcp jade.Boot @jadeArgs
    exit $LASTEXITCODE
}

if ($Scenario -eq "multijvm") {
    # JVM 1 : plateforme principale + taxis (fenêtre Ville). JVM 2 : conteneur distant avec des clients.
    Write-Host "JVM 1 : plateforme + taxis..."
    Start-Process java -ArgumentList "-Dstdout.encoding=UTF-8", "-cp", $jcp, "taxi.Lancement", "distant" | Out-Null
    Start-Sleep -Seconds 6
    Write-Host "JVM 2 : conteneur Clients-JVM2 qui rejoint la plateforme sur localhost:1099"
    java "-Dstdout.encoding=UTF-8" "-Dcity.view=false" -cp $jcp jade.Boot -container -host localhost -port 1099 `
        -container-name Clients-JVM2 `
        -agents "distant1:taxi.ClientAgent(1,8,8,2,30,14);distant2:taxi.ClientAgent(8,1,2,8,30,14,3,3000,presse);gen:taxi.GeneratorAgent(4000,5,3,jvm2)"
    exit 0
}

$opts = @($Scenario)
if ($NoGui) { $opts += "--nogui" }
if ($Mode -ne "") { $opts += "--mode=$Mode" }
java "-Dstdout.encoding=UTF-8" -cp $jcp taxi.Lancement @opts
