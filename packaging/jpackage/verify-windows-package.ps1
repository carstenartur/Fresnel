param(
    [string]$MsiPath = ""
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($MsiPath)) {
    $candidate = Get-ChildItem -Path "backend/target/dist" -Filter "*.msi" -File |
        Select-Object -First 1
    if ($null -eq $candidate) {
        throw "verify-windows-package.ps1: no .msi package found"
    }
    $MsiPath = $candidate.FullName
}

$MsiPath = (Resolve-Path $MsiPath).Path
$installer = New-Object -ComObject WindowsInstaller.Installer
$database = $installer.GetType().InvokeMember(
    "OpenDatabase",
    [System.Reflection.BindingFlags]::InvokeMethod,
    $null,
    $installer,
    @($MsiPath, 0)
)

function Get-MsiRows {
    param(
        [Parameter(Mandatory = $true)] $Database,
        [Parameter(Mandatory = $true)] [string] $Query,
        [Parameter(Mandatory = $true)] [int] $FieldCount
    )

    $view = $Database.GetType().InvokeMember(
        "OpenView",
        [System.Reflection.BindingFlags]::InvokeMethod,
        $null,
        $Database,
        @($Query)
    )
    try {
        $view.GetType().InvokeMember(
            "Execute",
            [System.Reflection.BindingFlags]::InvokeMethod,
            $null,
            $view,
            $null
        ) | Out-Null

        $rows = @()
        while ($true) {
            $record = $view.GetType().InvokeMember(
                "Fetch",
                [System.Reflection.BindingFlags]::InvokeMethod,
                $null,
                $view,
                $null
            )
            if ($null -eq $record) { break }

            $fields = @()
            for ($index = 1; $index -le $FieldCount; $index++) {
                $fields += $record.GetType().InvokeMember(
                    "StringData",
                    [System.Reflection.BindingFlags]::GetProperty,
                    $null,
                    $record,
                    @($index)
                )
            }
            $rows += ,$fields
        }
        return $rows
    }
    finally {
        try {
            $view.GetType().InvokeMember(
                "Close",
                [System.Reflection.BindingFlags]::InvokeMethod,
                $null,
                $view,
                $null
            ) | Out-Null
        }
        catch {
            # Closing is best effort; query failures still propagate above.
        }
    }
}

$tables = Get-MsiRows -Database $database -Query "SELECT ``Name`` FROM ``_Tables``" -FieldCount 1 |
    ForEach-Object { $_[0] }

# Current jpackage/WiX versions model file associations through Registry rows.
# Older toolchains may additionally populate Extension/MIME tables, but those are
# not a portable invariant and must not be required here.
if ($tables -notcontains "Registry") {
    throw "MSI is missing the Registry table required for the .fresnel association"
}

$registryRows = Get-MsiRows -Database $database `
    -Query "SELECT ``Key``, ``Name``, ``Value`` FROM ``Registry``" -FieldCount 3
$mimeType = "application/vnd.carstenartur.fresnel.job+json"

$mimeMapping = $registryRows | Where-Object {
    $_[0] -eq "MIME\Database\Content Type\$mimeType" -and
    $_[1] -eq "Extension" -and
    $_[2] -eq ".fresnel"
} | Select-Object -First 1
if ($null -eq $mimeMapping) {
    throw "MSI Registry table does not map the canonical Fresnel media type to .fresnel"
}

$contentType = $registryRows | Where-Object {
    $_[1] -eq "Content Type" -and $_[2] -eq $mimeType
} | Select-Object -First 1
if ($null -eq $contentType) {
    throw "MSI Registry table does not assign the canonical media type to the Fresnel file class"
}

$openCommand = $registryRows | Where-Object {
    $_[0] -like "*\shell\open\command" -and $_[2] -match '"%1"'
} | Select-Object -First 1
if ($null -eq $openCommand) {
    throw "MSI Registry table does not forward an opened file path through the shell command"
}

$userChoice = $registryRows | Where-Object {
    ($_[0] -like "*\UserChoice*") -or ($_[1] -eq "ProgId" -and $_[0] -like "*FileExts*")
} | Select-Object -First 1
if ($null -ne $userChoice) {
    throw "MSI must advertise a capable handler without overriding the user's default application"
}

Write-Host "Verified Windows .fresnel MIME mapping and shell-open command in $MsiPath"
