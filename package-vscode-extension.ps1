<#
.SYNOPSIS
  Builds vscode-designer, the Axon Ivy Designer extension for VS Code with the changes of this repo, packages it into
  target\vscode-designer-14-<version>.vsix and installs it in VS Code. Then it waits: type "build" to do it again,
  or "install" to install the last package again.

.DESCRIPTION
  VS Code uses the installed version after "Developer: Reload Window". Without the command line "code", install the
  package with "Extensions: Install from VSIX..." in VS Code.

  The extension needs Node.js 24.11 or newer and pnpm 12.20.0. If the Node.js on the PATH is older, a portable
  Node.js is downloaded once into vscode-designer\.tools, where pnpm is installed too. Nothing is installed on the
  system, and the PATH changes only while this script runs.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\package-vscode-extension.ps1
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$NodeVersion = '24.22.3'
$MinimumNodeVersion = [version]'24.11.0'
$PnpmVersion = '12.20.0'

$repo = Join-Path $PSScriptRoot 'vscode-designer'
$extension = Join-Path $repo 'extension'
$tools = Join-Path $repo '.tools'
$target = Join-Path $PSScriptRoot 'target'

function Invoke-Native {
  param([string]$File, [string[]]$Arguments)
  # pnpm writes warnings to stderr: they must not stop the script, only the exit code counts. The output goes to
  # the console, not to the pipeline, where it would become the return value of the calling function.
  $ErrorActionPreference = 'Continue'
  & $File @Arguments | Out-Host
  if ($LASTEXITCODE -ne 0) {
    throw "'$(Split-Path $File -Leaf) $($Arguments -join ' ')' failed with exit code $LASTEXITCODE."
  }
}

function Install-PortableNode {
  $name = "node-v$NodeVersion-win-x64"
  $directory = Join-Path $tools $name
  if (Test-Path (Join-Path $directory 'node.exe')) {
    return $directory
  }
  New-Item -ItemType Directory -Force $tools | Out-Null
  [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
  $base = "https://nodejs.org/dist/v$NodeVersion"
  $zip = Join-Path $tools "$name.zip"
  Write-Host "Downloading Node.js $NodeVersion into $tools ..."
  Invoke-WebRequest "$base/$name.zip" -OutFile $zip -UseBasicParsing
  $checksums = (Invoke-WebRequest "$base/SHASUMS256.txt" -UseBasicParsing).Content
  $expected = ($checksums -split "`n" | Where-Object { $_.Trim().EndsWith("  $name.zip") } | Select-Object -First 1)
  $actual = (Get-FileHash $zip -Algorithm SHA256).Hash
  if (-not $expected -or $actual -ne $expected.Split(' ')[0].ToUpperInvariant()) {
    Remove-Item $zip
    throw "The checksum of $name.zip does not match SHASUMS256.txt of nodejs.org."
  }
  Expand-Archive $zip -DestinationPath $tools -Force
  Remove-Item $zip
  return $directory
}

function Resolve-NodeDirectory {
  $node = Get-Command node -ErrorAction SilentlyContinue
  if ($node) {
    $version = [version]((& $node.Source -v).Trim().TrimStart('v'))
    if ($version -ge $MinimumNodeVersion -and (Test-Path (Join-Path (Split-Path $node.Source) 'npm.cmd'))) {
      return Split-Path $node.Source
    }
    Write-Host "Node.js $version on the PATH is older than $MinimumNodeVersion."
  }
  return Install-PortableNode
}

function Resolve-Pnpm {
  param([string]$NodeDirectory)
  $prefix = Join-Path $tools "pnpm-$PnpmVersion"
  if (-not (Test-Path (Join-Path $prefix 'node_modules\pnpm\bin\pnpm.mjs'))) {
    Write-Host "Installing pnpm $PnpmVersion into $prefix ..."
    Invoke-Native (Join-Path $NodeDirectory 'npm.cmd') @(
      'install', '--no-audit', '--no-fund', '--ignore-scripts', '--prefix', $prefix, "pnpm@$PnpmVersion")
  }
  # Without its install script, pnpm keeps a placeholder that npm's pnpm.cmd cannot start from cmd or PowerShell.
  # This pnpm.cmd starts pnpm through Node.js instead. The build scripts call pnpm too, so it goes on the PATH.
  $bin = Join-Path $tools 'bin'
  New-Item -ItemType Directory -Force $bin | Out-Null
  $pnpm = Join-Path $bin 'pnpm.cmd'
  Set-Content $pnpm "@node `"%~dp0..\pnpm-$PnpmVersion\node_modules\pnpm\bin\pnpm.mjs`" %*" -Encoding Ascii
  return $pnpm
}

function Resolve-Code {
  $code = Get-Command code -ErrorAction SilentlyContinue
  if ($code) {
    return $code.Source
  }
  $default = Join-Path $env:LOCALAPPDATA 'Programs\Microsoft VS Code\bin\code.cmd'
  if (Test-Path $default) {
    return $default
  }
  return $null
}

function New-ExtensionPackage {
  param([string]$Pnpm)
  Push-Location $repo
  try {
    Write-Host 'Installing the dependencies ...'
    Invoke-Native $Pnpm @('install', '--frozen-lockfile')
    Write-Host 'Building the extension and its editors ...'
    Invoke-Native $Pnpm @('run', 'build:production')
  } finally {
    Pop-Location
  }

  # the version scheme of the published extension: 14.0.3 becomes 140.3.<yyyyMMddHH>
  $package = Get-Content (Join-Path $extension 'package.json') -Raw | ConvertFrom-Json
  $version = ($package.version -replace '^(\d+)\.', '$1') + '.' + (Get-Date -Format 'yyyyMMddHH')
  New-Item -ItemType Directory -Force $target | Out-Null
  Get-ChildItem $target -Filter "$($package.name)-*.vsix" | Remove-Item
  $vsix = Join-Path $target "$($package.name)-$version.vsix"

  Push-Location $extension
  try {
    Write-Host "Packaging version $version ..."
    Invoke-Native (Join-Path $extension 'node_modules\.bin\vsce.cmd') @(
      'package', '--no-update-package-json', '--no-git-tag-version', '--no-dependencies', $version, '--out', $vsix)
  } finally {
    Pop-Location
  }
  return $vsix
}

function Install-ExtensionPackage {
  param([string]$Vsix)
  $code = Resolve-Code
  if (-not $code) {
    Write-Host "Packaged: $Vsix"
    Write-Host 'The command line "code" of VS Code was not found: install it with "Extensions: Install from VSIX...".'
    return
  }
  Write-Host "Installing $Vsix ..."
  try {
    # --force: two builds of the same hour have the same version
    Invoke-Native $code @('--install-extension', $Vsix, '--force')
  } catch {
    throw "$($_.Exception.Message) If VS Code is busy with another install, run ""Developer: Reload Window"" and type ""install""."
  }
  Write-Host 'Installed. Run "Developer: Reload Window" in VS Code to use it.' -ForegroundColor Green
}

if (-not (Test-Path (Join-Path $extension 'package.json'))) {
  throw "No extension at $extension."
}

$nodeDirectory = Resolve-NodeDirectory
$pnpm = Resolve-Pnpm $nodeDirectory
$env:PATH = "$nodeDirectory;$(Split-Path $pnpm);$env:PATH"
Write-Host "Node.js $(& (Join-Path $nodeDirectory 'node.exe') -v), pnpm $(& $pnpm -v)"

$vsix = $null
$command = 'build'
while ($command -eq 'build' -or $command -eq 'install') {
  try {
    if ($command -eq 'build') {
      $vsix = New-ExtensionPackage $pnpm
    }
    if ($vsix) {
      Install-ExtensionPackage $vsix
    } else {
      Write-Host 'There is no package yet: type "build".'
    }
  } catch {
    # a failure does not end the script: fix the cause and build or install again
    Write-Host "Failed: $($_.Exception.Message)" -ForegroundColor Red
  }
  Write-Host ''
  $answer = Read-Host 'Type "build" to build and install again, "install" to install the last package again, or press Enter to quit'
  $command = if ($answer) { $answer.Trim().ToLowerInvariant() } else { '' }
}
