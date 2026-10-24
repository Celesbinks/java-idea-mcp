param(
    [Parameter(Mandatory=$true)][ValidateRange(1,2147483647)][int]$IdeaProcessId,
    [ValidateRange(1,300)][int]$DurationSeconds = 60,
    [string]$JdkHome = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
if (-not $JdkHome) { throw 'Pass -JdkHome pointing to the IDEA JBR or a full JDK 25.' }
$jcmd = Join-Path $JdkHome 'bin/jcmd.exe'
if (-not (Test-Path -LiteralPath $jcmd -PathType Leaf)) { throw 'jcmd.exe is missing.' }
$ideProcess = Get-Process -Id $IdeaProcessId -ErrorAction Stop
if ($ideProcess.ProcessName -ne 'idea64') { throw 'Select the exact IDEA process, not a business JVM.' }
$settings = Join-Path $PSScriptRoot 'http-timing.jfc'
$outputDirectory = Join-Path $taskRoot 'build/diagnostics'
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
$destination = Join-Path $outputDirectory ('http-timing-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.jfr')
& $jcmd $IdeaProcessId JFR.start 'name=JavaMcpHttp' "settings=$settings" "duration=$($DurationSeconds)s" "filename=$destination"
if ($LASTEXITCODE -ne 0) { throw 'JFR recording could not be started.' }
Write-Output 'Recording only custom HTTP completion events. Requires plugin 0.6.6+ with dev.ideamcp.timings enabled.'
