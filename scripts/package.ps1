# Produce the one-file source archive required for NTULearn submission.
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetDirectoryName($PSScriptRoot)
$distDir = [IO.Path]::Combine($root, 'dist')
$archive = [IO.Path]::Combine($distDir, 'SC6103-project-source.zip')

New-Item -ItemType Directory -Force -Path $distDir | Out-Null

$items = @(
    [IO.Path]::Combine($root, '.gitignore'),
    [IO.Path]::Combine($root, 'CLAUDE.md'),
    [IO.Path]::Combine($root, 'Makefile'),
    [IO.Path]::Combine($root, 'README.md'),
    [IO.Path]::Combine($root, 'docs'),
    [IO.Path]::Combine($root, 'scripts'),
    [IO.Path]::Combine($root, 'src'),
    [IO.Path]::Combine($root, 'tests')
)

foreach ($item in $items) {
    if (-not (Test-Path -LiteralPath $item)) {
        throw "Required package input is missing: $item"
    }
}

Compress-Archive -LiteralPath $items -DestinationPath $archive `
    -CompressionLevel Optimal -Force

$entryCount = (tar -tf $archive | Measure-Object).Count
if ($LASTEXITCODE -ne 0 -or $entryCount -lt 1) {
    throw "Archive validation failed: $archive"
}

Write-Host "Created $archive"
Write-Host "Validated $entryCount archive entries."
