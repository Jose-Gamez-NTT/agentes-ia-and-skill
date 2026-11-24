[CmdletBinding()]
param(
    [ValidateSet('Install', 'Uninstall', 'Check', 'Test', 'Start')]
    [string]$Action = 'Install',
    [string]$Workspace = (Get-Location).Path,
    [string]$DriversSource,
    [string]$BaseUrl = 'http://localhost:8080',
    [switch]$NoEditor,
    [switch]$SkipMavenTests
)

$ErrorActionPreference = 'Stop'
$packageRoot = Split-Path -Parent $PSScriptRoot
$sourceAgent = Join-Path $packageRoot 'agents/analista-db2.md'
$sourceSkill = Join-Path $packageRoot 'skills/analista-db2'
$sourceCommand = Join-Path $packageRoot 'commands/db2-configurar.md'
$workspaceRoot = [System.IO.Path]::GetFullPath($Workspace)
$agentTarget = Join-Path $workspaceRoot '.axet-code/agents/analista-db2.md'
$skillTarget = Join-Path $workspaceRoot '.axet-code/skills/analista-db2'
$commandTarget = Join-Path $workspaceRoot '.axet-code/commands/db2-configurar.md'
$runtime = Join-Path $skillTarget 'runtime/db2-report-server'
$configPath = Join-Path $runtime 'db2.properties'
$templatePath = Join-Path $runtime 'db2.properties.example'

function Write-Step([string]$Message) {
    Write-Host "`n==> $Message" -ForegroundColor Cyan
}

function Get-Properties([string]$Path) {
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^\s*([^#!][^=]*)=(.*)$') {
            $values[$matches[1].Trim()] = $matches[2].Trim()
        }
    }
    return $values
}

function Test-ConfiguredValue([hashtable]$Values, [string]$Name) {
    if (!$Values.ContainsKey($Name)) { return $false }
    $value = [string]$Values[$Name]
    return ($value.Length -gt 0 -and $value -notmatch '<[^>]+>')
}

function Get-DriverDirectory([hashtable]$Values) {
    $configured = if (Test-ConfiguredValue $Values 'db2.driverDir') { [string]$Values['db2.driverDir'] } else { 'Drivers_win' }
    if ([System.IO.Path]::IsPathRooted($configured)) { return $configured }
    return Join-Path $runtime $configured
}

function Test-LocalSetup([switch]$RequireDb2Config) {
    $ok = $true
    $java = Get-Command java -ErrorAction SilentlyContinue
    $maven = Get-Command mvn -ErrorAction SilentlyContinue
    if ($java) { Write-Host 'Java: disponible' } else { Write-Host 'Java: no encontrado' }
    if ($maven) { Write-Host 'Maven: disponible' } else { Write-Host 'Maven: no encontrado' }
    if (!$java -or !$maven) { $ok = $false }

    $values = @{}
    if (Test-Path -LiteralPath $configPath) {
        $values = Get-Properties $configPath
        Write-Host 'db2.properties: presente (valores ocultos)'
    } else {
        Write-Host 'db2.properties: falta'
        if ($RequireDb2Config) { $ok = $false }
    }

    if ($RequireDb2Config -and $values.Count -gt 0) {
        $hasUrl = (Test-ConfiguredValue $values 'db2.jdbcUrl') -or
                ((Test-ConfiguredValue $values 'db2.host') -and (Test-ConfiguredValue $values 'db2.database'))
        foreach ($key in @('db2.user', 'db2.password')) {
            if (!(Test-ConfiguredValue $values $key)) {
                Write-Host "$key`: falta o conserva el marcador de ejemplo"
                $ok = $false
            }
        }
        if (!$hasUrl) {
            Write-Host 'URL DB2: falta o conserva el marcador de ejemplo'
            $ok = $false
        } else {
            Write-Host 'URL DB2: configurada (valor oculto)'
        }
    }

    $driverDir = Get-DriverDirectory $values
    $driverJars = @()
    if (Test-Path -LiteralPath $driverDir -PathType Container) {
        $driverJars = @(Get-ChildItem -LiteralPath $driverDir -Filter '*.jar' -File)
    }
    Write-Host ("JAR JDBC en Drivers_win: {0}" -f $driverJars.Count)
    if ($driverJars.Count -eq 0) { $ok = $false }

    $git = Get-Command git -ErrorAction SilentlyContinue
    if ($git) {
        & git -C $workspaceRoot check-ignore --quiet -- $configPath
        if ($LASTEXITCODE -eq 0) {
            Write-Host 'db2.properties: excluido de Git'
        } else {
            Write-Warning 'db2.properties no aparece excluido por Git en este workspace; no lo añadas ni lo compartas.'
        }
    }
    return $ok
}

function Get-Sha256Hash([string]$Path) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $stream = [System.IO.File]::OpenRead($Path)
        try {
            return [System.BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-', '')
        } finally {
            $stream.Dispose()
        }
    } finally {
        $sha.Dispose()
    }
}

function Copy-OwnedFile([string]$Source, [string]$Destination) {
    if (Test-Path -LiteralPath $Destination -PathType Leaf) {
        $sourceHash = Get-Sha256Hash $Source
        $destinationHash = Get-Sha256Hash $Destination
        if ($sourceHash -ne $destinationHash) {
            Write-Warning "Se conserva el archivo local existente: $Destination"
            return
        }
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Destination) | Out-Null
    Copy-Item -LiteralPath $Source -Destination $Destination -Force
}

function Copy-SkillFiles([string]$Source, [string]$Destination) {
    $excludedSegments = @('.git', '.axet-code', 'Drivers_win', 'target', 'node_modules', 'dist')
    $sourceFull = [System.IO.Path]::GetFullPath($Source).TrimEnd([System.IO.Path]::DirectorySeparatorChar)
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    foreach ($item in Get-ChildItem -LiteralPath $sourceFull -Force -Recurse) {
        $relative = $item.FullName.Substring($sourceFull.Length).TrimStart('\', '/')
        $parts = $relative -split '[\\/]'
        if (($parts | Where-Object { $excludedSegments -contains $_ }).Count -gt 0) { continue }
        if ($item.Name -in @('db2.properties', '.env') -or $item.Extension -eq '.log') { continue }
        $target = Join-Path $Destination $relative
        if ($item.PSIsContainer) {
            New-Item -ItemType Directory -Force -Path $target | Out-Null
        } else {
            Copy-OwnedFile -Source $item.FullName -Destination $target
        }
    }
}

function Remove-OwnedFile([string]$Source, [string]$Destination) {
    if (!(Test-Path -LiteralPath $Destination -PathType Leaf)) { return }
    if (!(Test-Path -LiteralPath $Source -PathType Leaf)) {
        Write-Warning "No se pudo verificar el archivo instalado; se conserva: $Destination"
        return
    }
    $sourceHash = Get-Sha256Hash $Source
    $destinationHash = Get-Sha256Hash $Destination
    if ($sourceHash -eq $destinationHash) {
        Remove-Item -LiteralPath $Destination -Force
        Write-Host "Eliminado: $Destination"
    } else {
        Write-Warning "Se conserva el archivo modificado localmente: $Destination"
    }
}

function Remove-EmptyDirectories([string]$Path) {
    if (!(Test-Path -LiteralPath $Path -PathType Container)) { return }
    $directories = @(Get-ChildItem -LiteralPath $Path -Directory -Force -Recurse | Sort-Object { $_.FullName.Length } -Descending)
    foreach ($directory in $directories) {
        if (@(Get-ChildItem -LiteralPath $directory.FullName -Force).Count -eq 0) {
            Remove-Item -LiteralPath $directory.FullName -Force
        }
    }
    if (@(Get-ChildItem -LiteralPath $Path -Force).Count -eq 0) {
        Remove-Item -LiteralPath $Path -Force
    }
}

function Test-ActiveRuntimeProcess([string]$Path) {
    try {
        $processes = @(Get-CimInstance -ClassName Win32_Process -ErrorAction Stop)
    } catch {
        Write-Warning 'No se pudo comprobar si el runtime está activo; se intentará eliminarlo y se informará si hay archivos bloqueados.'
        return
    }
    $normalizedPath = [System.IO.Path]::GetFullPath($Path).TrimEnd([System.IO.Path]::DirectorySeparatorChar)
    $matchingProcesses = @($processes | Where-Object {
        $_.CommandLine -and $_.CommandLine.IndexOf($normalizedPath, [System.StringComparison]::OrdinalIgnoreCase) -ge 0
    })
    if ($matchingProcesses.Count -gt 0) {
        $processIds = ($matchingProcesses | ForEach-Object { $_.ProcessId }) -join ', '
        throw "El runtime parece estar en uso por los procesos $processIds. Detén el servidor DB2 (Ctrl+C) y vuelve a ejecutar Uninstall. No se ha eliminado la skill."
    }
}

function Invoke-Tests {
    Write-Step 'Pruebas unitarias del runtime'
    Push-Location $runtime
    try {
        & mvn --no-transfer-progress test
        if ($LASTEXITCODE -ne 0) { throw 'Las pruebas Maven fallaron.' }
    } finally {
        Pop-Location
    }
}

switch ($Action) {
    'Install' {
        Write-Step 'Instalación del agente, skill, comando y runtime en el workspace'
        if (!(Test-Path -LiteralPath $workspaceRoot -PathType Container)) {
            throw "No existe el workspace: $workspaceRoot"
        }
        foreach ($path in @((Split-Path -Parent $agentTarget), $skillTarget, (Split-Path -Parent $commandTarget))) {
            New-Item -ItemType Directory -Force -Path $path | Out-Null
        }
        Copy-OwnedFile -Source $sourceAgent -Destination $agentTarget
        Copy-SkillFiles -Source $sourceSkill -Destination $skillTarget
        Copy-OwnedFile -Source $sourceCommand -Destination $commandTarget
        Write-Host 'Agente, skill, runtime y comando /db2-configurar instalados.'

        if (!(Test-Path -LiteralPath $configPath)) {
            Copy-Item -LiteralPath $templatePath -Destination $configPath
            Write-Host 'Creado db2.properties desde db2.properties.example.'
        } else {
            Write-Host 'Se conserva db2.properties existente; no se muestra ni sobrescribe.'
        }

        $driverDir = Get-DriverDirectory (Get-Properties $configPath)
        New-Item -ItemType Directory -Force -Path $driverDir | Out-Null
        $existingJars = @(Get-ChildItem -LiteralPath $driverDir -Filter '*.jar' -File)
        if ($existingJars.Count -eq 0) {
            if ([string]::IsNullOrWhiteSpace($DriversSource) -and !$NoEditor) {
                $DriversSource = Read-Host 'Ruta de la carpeta con los JAR JDBC DB2 aprobados (no pegues credenciales)'
            }
            if (![string]::IsNullOrWhiteSpace($DriversSource)) {
                $DriversSource = [System.IO.Path]::GetFullPath($DriversSource)
                if (!(Test-Path -LiteralPath $DriversSource -PathType Container)) {
                    throw "No existe la carpeta de drivers indicada: $DriversSource"
                }
                $jars = @(Get-ChildItem -LiteralPath $DriversSource -Filter '*.jar' -File)
                if ($jars.Count -eq 0) { throw 'La carpeta indicada no contiene JAR.' }
                foreach ($jar in $jars) {
                    Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $driverDir $jar.Name) -Force
                }
                Write-Host ("Copiados {0} JAR al runtime (excluidos de Git)." -f $jars.Count)
            } else {
                Write-Warning 'No se indicaron drivers. Puedes repetir Install con -DriversSource <carpeta>'.
            }
        } else {
            Write-Host ("Ya hay {0} JAR en Drivers_win; no se sobrescribieron." -f $existingJars.Count)
        }

        $values = Get-Properties $configPath
        $hasUrl = (Test-ConfiguredValue $values 'db2.jdbcUrl') -or
                ((Test-ConfiguredValue $values 'db2.host') -and (Test-ConfiguredValue $values 'db2.database'))
        $configIncomplete = !$hasUrl -or !(Test-ConfiguredValue $values 'db2.user') -or !(Test-ConfiguredValue $values 'db2.password')
        if ($configIncomplete -and !$NoEditor) {
            Write-Host 'Abriendo db2.properties para que completes host/base/usuario/contraseña localmente; el contenido no se mostrará.'
            Start-Process -FilePath 'notepad.exe' -ArgumentList @($configPath) -Wait
        }

        if (!(Test-LocalSetup -RequireDb2Config)) {
            Write-Warning 'Instalación de archivos completada; falta resolver uno o más requisitos locales indicados arriba.'
            Write-Host "Completa db2.properties y los drivers; luego ejecuta: powershell -NoProfile -File `"$PSScriptRoot/install-analista-db2.ps1`" -Action Check -Workspace `"$workspaceRoot`""
            exit 2
        }
        if (!$SkipMavenTests) { Invoke-Tests }
        Write-Host "`nInstalación lista. En una nueva sesión usa /db2-configurar o inicia el servidor con -Action Start."
    }
    'Uninstall' {
        Write-Step 'Desinstalación del agente, skill, comando y runtime'
        if (!(Test-Path -LiteralPath $workspaceRoot -PathType Container)) {
            throw "No existe el workspace: $workspaceRoot"
        }
        Test-ActiveRuntimeProcess -Path $skillTarget
        Remove-OwnedFile -Source $sourceAgent -Destination $agentTarget
        Remove-OwnedFile -Source $sourceCommand -Destination $commandTarget
        if (Test-Path -LiteralPath $skillTarget -PathType Container) {
            try {
                Remove-Item -LiteralPath $skillTarget -Recurse -Force -ErrorAction Stop
            } catch {
                throw "No se pudo eliminar por completo la skill y su runtime en '$skillTarget'. Detén cualquier proceso que use esos archivos y vuelve a ejecutar Uninstall. Error: $($_.Exception.Message)"
            }
            if (Test-Path -LiteralPath $skillTarget) {
                throw "La skill sigue presente en '$skillTarget'; la desinstalación quedó incompleta."
            }
            Write-Host "Eliminada la skill y todo su contenido local: $skillTarget"
        }
        Remove-EmptyDirectories -Path (Split-Path -Parent $skillTarget)
        Remove-EmptyDirectories -Path (Split-Path -Parent $agentTarget)
        Remove-EmptyDirectories -Path (Split-Path -Parent $commandTarget)
        Write-Host 'Desinstalación completada. Se eliminan también configuración, drivers, runtime compilado y demás datos dentro de la carpeta de la skill.'
    }
    'Check' {
        Write-Step 'Comprobación local (sin mostrar valores de configuración)'
        if (!(Test-LocalSetup -RequireDb2Config)) { exit 2 }
        Write-Host 'Requisitos locales presentes. Usa -Action Test con el servidor arrancado.'
    }
    'Test' {
        Write-Step 'Pruebas unitarias y conexión DB2 de solo lectura'
        if (!(Test-LocalSetup -RequireDb2Config)) { exit 2 }
        Invoke-Tests
        try {
            $health = Invoke-RestMethod -Method Get -Uri "$($BaseUrl.TrimEnd('/'))/health" -TimeoutSec 3
            if ($health.status -ne 'ok') { throw 'Health check no devolvió status ok.' }
        } catch {
            throw "No responde el servidor local en $BaseUrl. Inícialo con -Action Start y vuelve a ejecutar Test."
        }
        $probe = @{
            query = @{
                select = @(@{ star = 'd' })
                from = @{ schema = 'SYSIBM'; table = 'SYSDUMMY1'; alias = 'd' }
                limit = 1
            }
            maxRows = 1
            timeoutSeconds = 10
        } | ConvertTo-Json -Depth 8
        $result = Invoke-RestMethod -Method Post -Uri "$($BaseUrl.TrimEnd('/'))/query" -ContentType 'application/json' -Body $probe -TimeoutSec 20
        if ($result.rowCount -lt 1) { throw 'La consulta de prueba no devolvió filas.' }
        Write-Host ("Health: OK; prueba DB2 solo lectura: OK ({0} fila, columnas {1})." -f $result.rowCount, ($result.columns -join ', '))
    }
    'Start' {
        Write-Step 'Arranque del servidor DB2 local'
        if (!(Test-LocalSetup -RequireDb2Config)) { exit 2 }
        Push-Location $runtime
        try {
            & mvn --no-transfer-progress compile exec:java
            if ($LASTEXITCODE -ne 0) { throw 'El servidor DB2 terminó con error.' }
        } finally {
            Pop-Location
        }
    }
}
