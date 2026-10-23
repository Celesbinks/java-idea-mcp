param(
    [string]$IdeaHome = $env:IDEA_HOME,
    [string]$JdkHome = $env:JAVA_HOME,
    [switch]$Test,
    [switch]$PsiTest,
    [string]$JUnitJar = "$env:USERPROFILE/.m2/repository/junit/junit/4.13.2/junit-4.13.2.jar",
    [string]$HamcrestJar = "$env:USERPROFILE/.m2/repository/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar",
    [string]$OpenTest4jJar = "$env:USERPROFILE/.m2/repository/org/opentest4j/opentest4j/1.3.0/opentest4j-1.3.0.jar"
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$taskRoot = Split-Path -Parent $PSScriptRoot
if (-not $IdeaHome) { throw 'Pass -IdeaHome or set IDEA_HOME to an IntelliJ IDEA 2025.2 installation.' }
if (-not $JdkHome) { $JdkHome = Join-Path $IdeaHome 'jbr' }
$ideaInfo = Get-Content -LiteralPath (Join-Path $IdeaHome 'product-info.json') -Raw | ConvertFrom-Json
if (-not $ideaInfo.buildNumber.StartsWith('252.')) { throw 'This build targets IDEA 2025.2 (252.*).' }
$javac = Join-Path $JdkHome 'bin/javac.exe'
$java = Join-Path $JdkHome 'bin/java.exe'
$jar = Join-Path $JdkHome 'bin/jar.exe'
$compileJars = @(
    Get-ChildItem -LiteralPath (Join-Path $IdeaHome 'lib') -Filter '*.jar'
    Get-ChildItem -LiteralPath (Join-Path $IdeaHome 'lib/modules') -Filter '*.jar'
    Get-ChildItem -LiteralPath (Join-Path $IdeaHome 'plugins/java/lib') -Filter '*.jar'
    Get-ChildItem -LiteralPath (Join-Path $IdeaHome 'plugins/java/lib/modules') -Filter '*.jar'
) | ForEach-Object FullName
$classpath = $compileJars -join [IO.Path]::PathSeparator
# Use fresh output paths so deleted source classes cannot survive incremental packaging.
$stamp = [Guid]::NewGuid().ToString('N')
$classes = Join-Path $taskRoot "build/local/$stamp/classes"
$stage = Join-Path $taskRoot "build/local/$stamp/stage/idea-symbol-mcp/lib"
$dist = Join-Path $taskRoot 'build/distributions'
New-Item -ItemType Directory -Path $classes,$stage,$dist -Force | Out-Null
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
& $jar --create --file (Join-Path $stage "idea-symbol-mcp-$pluginVersion.jar") -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'Plugin JAR packaging failed.' }
$zip = Join-Path $dist "idea-symbol-mcp-$pluginVersion.zip"
Compress-Archive -Path (Split-Path -Parent $stage) -DestinationPath $zip -Force
Write-Output "Plugin: $zip"
if ($Test -or $PsiTest) {
    $testClasses = Join-Path $taskRoot "build/local/$stamp/test-classes"
    New-Item -ItemType Directory -Path $testClasses -Force | Out-Null
    $testSource = Join-Path $taskRoot 'src/test/java/dev/ideamcp/ProtocolSelfTest.java'
    & $javac --release 21 -encoding UTF-8 -classpath "$classes;$classpath" -d $testClasses $testSource
    if ($LASTEXITCODE -ne 0) { throw 'Self-test compilation failed.' }
    & $java -ea -classpath "$testClasses;$classes;$classpath" dev.ideamcp.ProtocolSelfTest
    if ($LASTEXITCODE -ne 0) { throw 'Protocol self-tests failed.' }
    if ($PsiTest) {
        if (-not (Test-Path -LiteralPath $JUnitJar) -or -not (Test-Path -LiteralPath $HamcrestJar) -or -not (Test-Path -LiteralPath $OpenTest4jJar)) {
            throw 'PSI tests require JUnit 4.13.2, Hamcrest 1.3 and OpenTest4j 1.3.0. Pass the corresponding JAR paths or use Gradle test.'
        }
        $testDeps = Join-Path $taskRoot "build/local/$stamp/test-dependencies"
        New-Item -ItemType Directory -Path $testDeps -Force | Out-Null
        Copy-Item -LiteralPath $JUnitJar,$HamcrestJar,$OpenTest4jJar -Destination $testDeps
        $testClasspath = "$testClasses;$classes;$classpath;" + ((Get-ChildItem -LiteralPath $testDeps -Filter '*.jar' | ForEach-Object FullName) -join ';')
        $integrationSources = @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'src/test/java') -Recurse -Filter '*.java' | ForEach-Object FullName)
        & $javac --release 21 -encoding UTF-8 -classpath $testClasspath -d $testClasses $integrationSources
        if ($LASTEXITCODE -ne 0) { throw 'PSI integration test compilation failed.' }
        $sandbox = Join-Path $taskRoot "build/local/$stamp/sandbox"
        New-Item -ItemType Directory -Path $sandbox -Force | Out-Null
        $vmArgs = @('-ea', '-Xmx2g', '-Djava.awt.headless=true', "-Didea.home.path=$IdeaHome",
            "-Didea.mcp.test.jdk=$JdkHome",
            "-Didea.config.path=$sandbox/config", "-Didea.system.path=$sandbox/system", "-Didea.log.path=$sandbox/log",
            "-Djna.boot.library.path=$IdeaHome/lib/jna/amd64", '-Djna.nosys=true', '-Djna.noclasspath=true',
            '-Didea.load.plugins.id=com.intellij.java', '-Didea.force.use.core.classloader=true',
            '-Djava.system.class.loader=com.intellij.util.lang.PathClassLoader')
        $productVmArgs = @($ideaInfo.launch[0].additionalJvmArguments | Where-Object { $_.StartsWith('--add-opens=') })
        & $java @vmArgs @productVmArgs -classpath $testClasspath org.junit.runner.JUnitCore dev.ideamcp.JavaCodeToolsTest dev.ideamcp.ReferencePaginationTest dev.ideamcp.JavaCallToolsTest dev.ideamcp.DiagnosticsToolsTest dev.ideamcp.RunLogBufferTest dev.ideamcp.RunLogServiceTest dev.ideamcp.TokenPersistenceTest dev.ideamcp.RunControlServiceTest dev.ideamcp.DebugServiceTest dev.ideamcp.DebugValueTest
        if ($LASTEXITCODE -ne 0) { throw "PSI integration tests failed. Logs: $sandbox/log" }
    }
}
