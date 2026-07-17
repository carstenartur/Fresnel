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

foreach ($requiredTable in @("Extension", "MIME")) {
    if ($tables -notcontains $requiredTable) {
        throw "MSI is missing required Windows Installer table: $requiredTable"
    }
}

$extensionRows = Get-MsiRows -Database $database `
    -Query "SELECT ``Extension``, ``MIME_`` FROM ``Extension``" -FieldCount 2
$association = $extensionRows | Where-Object {
    $_[0] -eq "fresnel" -and $_[1] -eq "application/vnd.carstenartur.fresnel.job+json"
} | Select-Object -First 1
if ($null -eq $association) {
    $rendered = ($extensionRows | ForEach-Object { "$($_[0]) -> $($_[1])" }) -join "; "
    throw "MSI Extension table does not contain the canonical .fresnel association. Rows: $rendered"
}

$mimeRows = Get-MsiRows -Database $database `
    -Query "SELECT ``ContentType``, ``Extension_`` FROM ``MIME``" -FieldCount 2
$mime = $mimeRows | Where-Object {
    $_[0] -eq "application/vnd.carstenartur.fresnel.job+json" -and $_[1] -eq "fresnel"
} | Select-Object -First 1
if ($null -eq $mime) {
    $rendered = ($mimeRows | ForEach-Object { "$($_[0]) -> $($_[1])" }) -join "; "
    throw "MSI MIME table does not contain the canonical Fresnel media type. Rows: $rendered"
}

Write-Host "Verified Windows .fresnel association metadata in $MsiPath"
