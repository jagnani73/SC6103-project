# SC6103 -- compile src/java and tests/java, then run the conformance vectors.
#
# README sec. 15.1 commits the project to plain javac with no build tool and no
# third-party jar, so this script is a thin driver around one javac call and one
# java call. It must not grow into a build system.
#
# Two things about this machine drive the shape of the script:
#
#   1. The repository path contains square brackets ("[SC6103] DISTRIBUTED SYSTEMS").
#      PowerShell treats [ ] as wildcard character classes in every -Path parameter,
#      so a bracketed path silently resolves to nothing. Every path operation here
#      therefore uses -LiteralPath, and paths are joined with [IO.Path]::Combine
#      rather than Join-Path so no wildcard interpretation is possible at all.
#
#   2. There is no JDK on PATH. The Red Hat Java extension for VS Code ships one, so
#      the script globs for it and falls back to PATH if the extension is absent.
#      The glob lives under $env:USERPROFILE\.vscode\extensions, which has no
#      brackets, so a wildcard -Path is safe there -- and is required, since that is
#      the one place a wildcard is actually wanted.

[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

# ---- Locate the JDK ----------------------------------------------------------

function Find-Jdk {
    $found = @()

    $extensions = [IO.Path]::Combine($env:USERPROFILE, '.vscode', 'extensions')
    if (Test-Path -LiteralPath $extensions) {
        # Wildcard is intentional here; this path has no brackets.
        $bundles = Get-ChildItem -Path ([IO.Path]::Combine($extensions, 'redhat.java-*')) `
            -Directory -ErrorAction SilentlyContinue
        foreach ($bundle in $bundles) {
            $jreRoot = [IO.Path]::Combine($bundle.FullName, 'jre')
            if (-not (Test-Path -LiteralPath $jreRoot)) { continue }
            foreach ($version in Get-ChildItem -LiteralPath $jreRoot -Directory) {
                $javac = [IO.Path]::Combine($version.FullName, 'bin', 'javac.exe')
                $java = [IO.Path]::Combine($version.FullName, 'bin', 'java.exe')
                if ((Test-Path -LiteralPath $javac) -and (Test-Path -LiteralPath $java)) {
                    $found += [pscustomobject]@{
                        Javac  = $javac
                        Java   = $java
                        Source = "VS Code Red Hat Java extension ($($version.Name))"
                        Sort   = $version.Name
                    }
                }
            }
        }
    }

    if ($found.Count -gt 0) {
        # Newest bundled runtime first, so a stale older extension copy is not picked.
        return ($found | Sort-Object -Property Sort -Descending)[0]
    }

    $javacCmd = Get-Command -Name 'javac' -CommandType Application -ErrorAction SilentlyContinue
    $javaCmd = Get-Command -Name 'java' -CommandType Application -ErrorAction SilentlyContinue
    if ($javacCmd -and $javaCmd) {
        return [pscustomobject]@{
            Javac  = $javacCmd.Source
            Java   = $javaCmd.Source
            Source = 'PATH'
            Sort   = 'path'
        }
    }

    return $null
}

$jdk = Find-Jdk
if ($null -eq $jdk) {
    Write-Host 'ERROR: no JDK found.' -ForegroundColor Red
    Write-Host ''
    Write-Host 'Looked in:'
    Write-Host ("  {0}\.vscode\extensions\redhat.java-*\jre\*\bin\javac.exe" -f $env:USERPROFILE)
    Write-Host '  javac and java on PATH'
    Write-Host ''
    Write-Host 'Fix by installing a JDK 17 or newer and putting its bin directory on PATH,'
    Write-Host 'or by installing the "Extension Pack for Java" in VS Code, which bundles one.'
    exit 1
}

# ---- Paths -------------------------------------------------------------------

# [IO.Path]::GetDirectoryName rather than Split-Path: Split-Path's -LiteralPath
# parameter set does not accept -Parent (the combination fails to bind), and its
# -Path form would treat this repository's square brackets as a wildcard. A pure
# string operation has neither problem.
$root = [IO.Path]::GetDirectoryName($PSScriptRoot)
$srcDir = [IO.Path]::Combine($root, 'src', 'java')
$testDir = [IO.Path]::Combine($root, 'tests', 'java')
$outDir = [IO.Path]::Combine($root, 'build', 'classes')

foreach ($dir in @($srcDir, $testDir)) {
    if (-not (Test-Path -LiteralPath $dir)) {
        Write-Host "ERROR: source directory not found: $dir" -ForegroundColor Red
        exit 1
    }
}

if (-not (Test-Path -LiteralPath $outDir)) {
    New-Item -ItemType Directory -Path $outDir -Force | Out-Null
}

$sources = @()
foreach ($dir in @($srcDir, $testDir)) {
    $sources += (Get-ChildItem -LiteralPath $dir -Recurse -Filter '*.java' -File |
        ForEach-Object { $_.FullName })
}

if ($sources.Count -eq 0) {
    Write-Host 'ERROR: no .java sources found under src/java or tests/java' -ForegroundColor Red
    exit 1
}

Write-Host "JDK:      $($jdk.Source)"
Write-Host "javac:    $($jdk.Javac)"
Write-Host "sources:  $($sources.Count) files"
Write-Host "output:   $outDir"
Write-Host ''

# ---- Compile -----------------------------------------------------------------
#
# --release 17    the language and API level the project targets, checked against
#                 the real 17 API rather than merely -source/-target
# -encoding US-ASCII
#                 all sources are pure ASCII by rule; this makes a stray non-ASCII
#                 byte a compile error instead of a value that depends on the
#                 machine's default charset (README sec. 11.1)
# -Xlint:all      the marshalling code is the graded artefact, so every warning is
#                 worth seeing

$javacArgs = @('--release', '17', '-encoding', 'US-ASCII', '-Xlint:all', '-d', $outDir) + $sources

Write-Host 'Compiling...'
& $jdk.Javac @javacArgs
if ($LASTEXITCODE -ne 0) {
    Write-Host ''
    Write-Host "ERROR: javac failed with exit code $LASTEXITCODE" -ForegroundColor Red
    exit $LASTEXITCODE
}
Write-Host 'Compiled OK.'
Write-Host ''

# ---- Run the conformance vectors --------------------------------------------
#
# The test prints one line per assertion and exits nonzero on any failure, so its
# exit code is this script's exit code.

& $jdk.Java '-cp' $outDir 'sc6103.conformance.ConformanceTest'
exit $LASTEXITCODE
