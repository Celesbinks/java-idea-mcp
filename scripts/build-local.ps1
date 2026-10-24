param(
    [string]$IdeaHome = $env:IDEA_HOME,
    [string]$JdkHome = $env:JAVA_HOME,
    [switch]$Test,
    [switch]$PsiTest
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$taskRoot = Split-Path -Parent $PSScriptRoot
if (-not $IdeaHome) { throw 'Pass -IdeaHome or set IDEA_HOME to an IntelliJ IDEA 2026.2.3 installation.' }
if (-not $JdkHome) { $JdkHome = Join-Path $IdeaHome 'jbr' }
$ideaInfo = Get-Content -LiteralPath (Join-Path $IdeaHome 'product-info.json') -Raw | ConvertFrom-Json
if (-not $ideaInfo.buildNumber.StartsWith('262.') -or [version]$ideaInfo.buildNumber -lt [version]'262.10968.63') {
    throw 'This build targets IDEA 2026.2.3 (262.10968.63) or newer 262.* builds.'
}
$javac = Join-Path $JdkHome 'bin/javac.exe'
$java = Join-Path $JdkHome 'bin/java.exe'
$runtimeDependencies = Join-Path $taskRoot 'build/local/runtime-dependencies'
if (-not (Test-Path -LiteralPath (Join-Path $runtimeDependencies 'tomlj-1.1.1.jar'))) {
    throw 'Run gradlew.bat prepareLocalDependencies with the localIdePath property once before using this script.'
}
$compileJars = @(
    foreach ($relativePath in @('lib', 'lib/modules', 'plugins/java/lib', 'plugins/java/lib/modules', 'plugins/mcpserver/lib', 'plugins/vcs-git/lib')) {
        $libraryPath = Join-Path $IdeaHome $relativePath
        if (Test-Path -LiteralPath $libraryPath) { Get-ChildItem -LiteralPath $libraryPath -Filter '*.jar' }
    }
) | ForEach-Object FullName
$compileJars += @(Get-ChildItem -LiteralPath $runtimeDependencies -Filter '*.jar' | ForEach-Object FullName)
$classpath = $compileJars -join [IO.Path]::PathSeparator
# Use fresh output paths so deleted source classes cannot survive incremental packaging.
$stamp = [Guid]::NewGuid().ToString('N')
$classes = Join-Path $taskRoot "build/local/$stamp/classes"
$stage = Join-Path $taskRoot "build/local/$stamp/stage/idea-symbol-mcp/lib"
$dist = Join-Path $taskRoot 'build/distributions'
New-Item -ItemType Directory -Path $classes,$stage,$dist -Force | Out-Null
Copy-Item -Path (Join-Path $runtimeDependencies '*.jar') -Destination $stage
$sources = @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'src/main/java') -Recurse -Filter '*.java' | ForEach-Object FullName)
$argsFile = Join-Path $taskRoot "build/local/$stamp/javac.args"
$compilerArgs = @('--release', '21', '-encoding', 'UTF-8', '-classpath', ('"' + $classpath.Replace('\', '/') + '"'), '-d', ('"' + $classes.Replace('\', '/') + '"'))
$compilerArgs += $sources | ForEach-Object { '"' + $_.Replace('\', '/') + '"' }
[IO.File]::WriteAllLines($argsFile, $compilerArgs, [Text.UTF8Encoding]::new($false))
& $javac "@$argsFile"
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed.' }
Copy-Item -Path (Join-Path $taskRoot 'src/main/resources/*') -Destination $classes -Recurse -Force
[xml]$pluginDescriptor = Get-Content -LiteralPath (Join-Path $classes 'META-INF/plugin.xml') -Raw
$pluginVersion = $pluginDescriptor.'idea-plugin'.version
# The IDE's JBR 25 has javac but may omit jar.exe. A JAR is a ZIP with a manifest.
$manifest = Join-Path $classes 'META-INF/MANIFEST.MF'
if (-not (Test-Path -LiteralPath $manifest)) {
    [IO.File]::WriteAllText($manifest, "Manifest-Version: 1.0`r`n`r`n", [Text.UTF8Encoding]::new($false))
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory($classes, (Join-Path $stage "idea-symbol-mcp-$pluginVersion.jar"))
$zip = Join-Path $dist "idea-symbol-mcp-$pluginVersion.zip"
Compress-Archive -Path (Split-Path -Parent $stage) -DestinationPath $zip -Force
Write-Output "Plugin: $zip"
if ($Test -or $PsiTest) {
    $testClasses = Join-Path $taskRoot "build/local/$stamp/test-classes"
    New-Item -ItemType Directory -Path $testClasses -Force | Out-Null
    $testSource = Join-Path $taskRoot 'src/test/java/dev/ideamcp/ProtocolSelfTest.java'
    $testArgsFile = Join-Path $taskRoot "build/local/$stamp/javac-test.args"
    $testCompilerArgs = @('--release', '21', '-encoding', 'UTF-8', '-classpath', ('"' + "$classes;$classpath".Replace('\', '/') + '"'),
        '-d', ('"' + $testClasses.Replace('\', '/') + '"'), ('"' + $testSource.Replace('\', '/') + '"'))
    [IO.File]::WriteAllLines($testArgsFile, $testCompilerArgs, [Text.UTF8Encoding]::new($false))
    & $javac "@$testArgsFile"
    if ($LASTEXITCODE -ne 0) { throw 'Self-test compilation failed.' }
    $javaArgsFile = Join-Path $taskRoot "build/local/$stamp/java-test.args"
    [IO.File]::WriteAllLines($javaArgsFile, @('-ea', '-classpath', ('"' + "$testClasses;$classes;$classpath".Replace('\', '/') + '"'),
        'dev.ideamcp.ProtocolSelfTest'), [Text.UTF8Encoding]::new($false))
    & $java "@$javaArgsFile"
    if ($LASTEXITCODE -ne 0) { throw 'Protocol self-tests failed.' }
    if ($PsiTest) {
        # 262 distributions do not bundle the test framework. Let Gradle resolve
        # the framework for this exact SDK and prepare an isolated test sandbox.
        $previousJavaHome = $env:JAVA_HOME
        Push-Location $taskRoot
        try {
            $env:JAVA_HOME = $JdkHome
            & (Join-Path $taskRoot 'gradlew.bat') "-PlocalIdePath=$IdeaHome" test --no-daemon
            if ($LASTEXITCODE -ne 0) { throw 'PSI integration tests failed. See build/reports/tests/test/index.html.' }
        } finally {
            $env:JAVA_HOME = $previousJavaHome
            Pop-Location
        }
    }
}
