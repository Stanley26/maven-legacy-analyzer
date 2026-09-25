param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    if (-not $SkipBuild) {
        & .\mvnw.cmd -B -ntp verify
        if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
    }
    $labRun = Join-Path (Get-Location) ('target/lab-runs/' + [guid]::NewGuid().ToString('N'))
    & java lab/Prepare.java $labRun
    if ($LASTEXITCODE -ne 0) { throw 'Laboratory preparation failed' }
    $labCache = Join-Path (Get-Location) 'target/synthetic-maven-cache'
    $labConfig = @{ defaults = @{ timeoutSeconds = 300; alignmentGroups = @('com.example.components'); properties = @{ 'maven.repo.local' = $labCache } } }
    $labConfig | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $labRun 'config.local.json') -Encoding utf8
    & java -jar target/maven-legacy-analyzer.jar scan (Join-Path $labRun 'projects') --config (Join-Path $labRun 'config.local.json') --output (Join-Path $labRun 'report')
    if ($LASTEXITCODE -ne 0) { throw "Analysis failed; inspect $labRun/report" }
    & java --class-path target/maven-legacy-analyzer.jar lab/Verify.java (Join-Path $labRun 'report/analysis.json')
    if ($LASTEXITCODE -ne 0) { throw 'Laboratory verification failed' }
    Write-Output "Report: $labRun/report/index.html"
} finally { Pop-Location }
