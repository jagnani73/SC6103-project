# Produce the one-file source archive required for NTULearn submission.
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetDirectoryName($PSScriptRoot)
$distDir = [IO.Path]::Combine($root, 'dist')
$archive = [IO.Path]::Combine($distDir, 'SC6103-project-source.zip')

$items = @('.gitignore', 'Makefile', 'README.md', 'docs', 'scripts', 'src', 'tests')

# Not part of the submitted source: CLAUDE.md holds instructions for an AI coding
# assistant, and the course brief is the module's own document.
$excluded = @('CLAUDE.md', 'docs/Course Project.pdf')

foreach ($item in $items) {
    if (-not (Test-Path -LiteralPath ([IO.Path]::Combine($root, $item)))) {
        throw "Required package input is missing: $item"
    }
}

# Compress-Archive resolves its destination as a wildcard path, and this repository's
# path contains square brackets: -Force then fails to replace an existing archive and a
# rerun appends a second copy of every entry. The archive is therefore built in a
# temporary directory without brackets and moved into dist/ afterwards.
#
# Compress-Archive also has no exclude option, and files passed to it individually lose
# their directories. The inputs are copied into a staging tree, the exclusions removed,
# and the staging tree's top-level entries archived so every path keeps its structure.
$work = [IO.Path]::Combine([IO.Path]::GetTempPath(),
    'sc6103-package-' + [Guid]::NewGuid().ToString('N'))
$stage = [IO.Path]::Combine($work, 'stage')
$built = [IO.Path]::Combine($work, 'SC6103-project-source.zip')

try {
    New-Item -ItemType Directory -Force -Path $stage | Out-Null
    foreach ($item in $items) {
        Copy-Item -LiteralPath ([IO.Path]::Combine($root, $item)) -Destination $stage -Recurse
    }
    foreach ($relative in $excluded) {
        $path = [IO.Path]::Combine($stage, $relative)
        if (Test-Path -LiteralPath $path) {
            Remove-Item -LiteralPath $path -Force
        }
    }

    $entries = Get-ChildItem -LiteralPath $stage -Force | ForEach-Object { $_.FullName }
    Compress-Archive -LiteralPath $entries -DestinationPath $built -CompressionLevel Optimal

    New-Item -ItemType Directory -Force -Path $distDir | Out-Null
    if (Test-Path -LiteralPath $archive) {
        Remove-Item -LiteralPath $archive -Force
    }
    Move-Item -LiteralPath $built -Destination $archive
} finally {
    if (Test-Path -LiteralPath $work) {
        Remove-Item -LiteralPath $work -Recurse -Force
    }
}

$listing = tar -tf $archive
if ($LASTEXITCODE -ne 0 -or ($listing | Measure-Object).Count -lt 1) {
    throw "Archive validation failed: $archive"
}
foreach ($relative in $excluded) {
    if ($listing -contains $relative) {
        throw "Archive still contains excluded file: $relative"
    }
}
$duplicates = $listing | Group-Object | Where-Object { $_.Count -gt 1 }
if ($duplicates) {
    throw "Archive contains duplicate entries: $(($duplicates | ForEach-Object Name) -join ', ')"
}

Write-Host "Created $archive"
Write-Host "Validated $(($listing | Measure-Object).Count) archive entries."
