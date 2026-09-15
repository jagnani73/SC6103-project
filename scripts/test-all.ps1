# Build both language implementations and run every automated suite on Windows.
[CmdletBinding()]
param([switch]$Experiments)

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetDirectoryName($PSScriptRoot)
$classDir = [IO.Path]::Combine($root, 'build', 'classes')
$cppDir = [IO.Path]::Combine($root, 'build', 'cpp')
$serverExe = [IO.Path]::Combine($cppDir, 'flight_server.exe')
$cppTestExe = [IO.Path]::Combine($cppDir, 'cpp_conformance_test.exe')

function Get-Jdk {
    $candidates = @()
    if ($env:JAVA_HOME) {
        $candidates += [pscustomobject]@{
            Javac = [IO.Path]::Combine($env:JAVA_HOME, 'bin', 'javac.exe')
            Java = [IO.Path]::Combine($env:JAVA_HOME, 'bin', 'java.exe')
            Label = 'JAVA_HOME'
        }
    }
    $javac = Get-Command javac -CommandType Application -ErrorAction SilentlyContinue
    $java = Get-Command java -CommandType Application -ErrorAction SilentlyContinue
    if ($javac -and $java) {
        $candidates += [pscustomobject]@{ Javac=$javac.Source; Java=$java.Source; Label='PATH' }
    }
    $extensions = [IO.Path]::Combine($env:USERPROFILE, '.vscode', 'extensions')
    if (Test-Path -LiteralPath $extensions) {
        foreach ($bundle in Get-ChildItem -Path ([IO.Path]::Combine($extensions, 'redhat.java-*')) `
                -Directory -ErrorAction SilentlyContinue) {
            foreach ($version in Get-ChildItem -LiteralPath ([IO.Path]::Combine($bundle.FullName, 'jre')) `
                    -Directory -ErrorAction SilentlyContinue) {
                $candidates += [pscustomobject]@{
                    Javac=[IO.Path]::Combine($version.FullName, 'bin', 'javac.exe')
                    Java=[IO.Path]::Combine($version.FullName, 'bin', 'java.exe')
                    Label="VS Code Java ($($version.Name))"
                }
            }
        }
    }
    foreach ($candidate in $candidates) {
        if (-not (Test-Path -LiteralPath $candidate.Javac) -or
            -not (Test-Path -LiteralPath $candidate.Java)) { continue }
        $versionText = (& $candidate.Javac -version 2>&1 | Out-String)
        $match = [regex]::Match($versionText, '(\d+)(?:\.|$)')
        if ($match.Success -and [int]$match.Groups[1].Value -ge 17) { return $candidate }
    }
    throw 'JDK 17 or newer is required. Set JAVA_HOME or put javac/java on PATH.'
}

function Get-Cxx {
    $names = @()
    if ($env:CXX) { $names += $env:CXX }
    $names += @('clang++', 'g++', 'c++')
    $llvm = [IO.Path]::Combine($env:ProgramFiles, 'LLVM', 'bin', 'clang++.exe')
    if (Test-Path -LiteralPath $llvm) { $names += $llvm }
    foreach ($name in $names) {
        $command = Get-Command $name -CommandType Application -ErrorAction SilentlyContinue
        if ($command) { return $command.Source }
    }
    throw 'A C++17 compiler is required. Set CXX or put clang++/g++ on PATH.'
}

$jdk = Get-Jdk
$cxx = Get-Cxx
$compilerDir = [IO.Path]::GetDirectoryName($cxx)
$env:PATH = $compilerDir + [IO.Path]::PathSeparator + $env:PATH
New-Item -ItemType Directory -Force -Path $classDir,$cppDir | Out-Null

Write-Host "C++:  $cxx"
& $cxx '-std=c++17' '-Wall' '-Wextra' '-Wpedantic' '-Werror' '-O2' `
    "-I$([IO.Path]::Combine($root, 'src', 'cpp', 'server'))" `
    ([IO.Path]::Combine($root, 'src', 'cpp', 'server', 'flight_server.cpp')) `
    '-o' $serverExe '-static' '-lws2_32'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $cxx '-std=c++17' '-Wall' '-Wextra' '-Wpedantic' '-Werror' '-O2' `
    "-I$([IO.Path]::Combine($root, 'src', 'cpp', 'server'))" `
    ([IO.Path]::Combine($root, 'tests', 'cpp', 'conformance_test.cpp')) `
    '-o' $cppTestExe '-static'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$sources = Get-ChildItem -LiteralPath ([IO.Path]::Combine($root, 'src', 'java')), `
    ([IO.Path]::Combine($root, 'tests', 'java')) -Recurse -Filter '*.java' -File |
    ForEach-Object { $_.FullName }
Write-Host "JDK:  $($jdk.Label)"
& $jdk.Javac '--release' '17' '-encoding' 'US-ASCII' '-Xlint:all' '-d' $classDir @sources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$failures = 0
& $cppTestExe
if ($LASTEXITCODE -ne 0) { $failures++ }
& $jdk.Java '-cp' $classDir 'sc6103.conformance.ConformanceTest'
if ($LASTEXITCODE -ne 0) { $failures++ }
& $jdk.Java '-cp' $classDir 'sc6103.client.ClientBehaviourTest'
if ($LASTEXITCODE -ne 0) { $failures++ }
& $jdk.Java "-Dsc6103.server.binary=$serverExe" '-cp' $classDir `
    'sc6103.integration.ServerIntegrationTest'
if ($LASTEXITCODE -ne 0) { $failures++ }

if ($failures -ne 0) {
    Write-Host "$failures of 4 suites FAILED" -ForegroundColor Red
    exit 1
}
Write-Host 'All 4 suites passed.' -ForegroundColor Green
if ($Experiments) {
    & $jdk.Java "-Dsc6103.server.binary=$serverExe" '-cp' $classDir `
        'sc6103.integration.ExperimentHarness'
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
