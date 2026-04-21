$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot
$null = Push-Location $root

$buildDir = 'run-classes'
if (Test-Path $buildDir) {
    Remove-Item -Recurse -Force $buildDir
}
New-Item -ItemType Directory -Path $buildDir | Out-Null

$m2Repo = Join-Path $env:USERPROFILE '.m2\repository'
if (-not (Test-Path $m2Repo)) {
    throw "Maven local repository not found: $m2Repo"
}

$jarPaths = Get-ChildItem $m2Repo -Recurse -Filter *.jar |
    Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } |
    Where-Object {
        $_.FullName -notmatch '\\org\\apache\\maven\\' -and
        $_.FullName -notmatch '\\org\\apache\\maven\\plugins\\' -and
        $_.FullName -notmatch '\\org\\apache\\maven\\shared\\' -and
        $_.FullName -notmatch '\\org\\apache\\maven\\resolver\\' -and
        $_.FullName -notmatch '\\org\\codehaus\\plexus\\' -and
        $_.FullName -notmatch '\\org\\sonatype\\' -and
        $_.FullName -notmatch '\\org\\eclipse\\aether\\' -and
        $_.FullName -notmatch '\\org\\slf4j\\slf4j-api\\1\.7\.'
    } |
    ForEach-Object { $_.FullName }

$compileClasspath = $jarPaths -join ';'
$runtimeClasspath = (@('src\main\resources', $buildDir) + $jarPaths) -join ';'

$compileArgsFile = 'compile-backend.args'
@"
-cp
$compileClasspath
-d
"$buildDir"
"@ | Set-Content -Encoding ASCII $compileArgsFile

Get-ChildItem 'src\main\java' -Recurse -Filter *.java |
    ForEach-Object { $_.FullName.Substring($root.Length + 1) } |
    Add-Content -Encoding ASCII $compileArgsFile

& javac "@$compileArgsFile"

$runArgsFile = 'run-backend.args'
@"
-cp
$runtimeClasspath
com.network.monitoring.Application
"@ | Set-Content -Encoding ASCII $runArgsFile

& java "@$runArgsFile"
