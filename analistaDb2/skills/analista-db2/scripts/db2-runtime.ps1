[CmdletBinding()]
param(
    [ValidateSet('Configure', 'Check', 'Test', 'Start')]
    [string]$Action = 'Check',
    [string]$Workspace = (Get-Location).Path,
    [string]$DriversSource,
    [string]$BaseUrl = 'http://localhost:8080',
    [switch]$NoEditor
)

$ErrorActionPreference = 'Stop'
$workspaceRoot = [System.IO.Path]::GetFullPath($Workspace)
$runtime = Join-Path $workspaceRoot '.axet-code/skills/analista-db2/runtime/db2-report-server'
$configPath = Join-Path $runtime 'db2.properties'
$templatePath = Join-Path $runtime 'db2.properties.example'

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
    if ($java) { Write-Host 'Java: disponible' } else { Write-Host 'Java: no encontrado'; $ok = $false }
    if ($maven) { Write-Host 'Maven: disponible' } else { Write-Host 'Maven: no encontrado'; $ok = $false }

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
    Write-Host ("JAR JDBC: {0}" -f $driverJars.Count)
    if ($driverJars.Count -eq 0) { $ok = $false }

    $git = Get-Command git -ErrorAction SilentlyContinue
    if ($git) {
        & git -C $workspaceRoot check-ignore --quiet -- $configPath
        if ($LASTEXITCODE -eq 0) {
            Write-Host 'db2.properties: excluido de Git'
        } else {
            Write-Warning 'db2.properties no aparece excluido por Git; no lo añadas ni lo compartas.'
        }
    }
    return $ok
}

switch ($Action) {
    'Configure' {
        if (!(Test-Path -LiteralPath $runtime -PathType Container)) {
            throw "No se encuentra el runtime instalado en: $runtime. Ejecuta primero el instalador de analistaDb2."
        }
        if (!(Test-Path -LiteralPath $configPath)) {
            Copy-Item -LiteralPath $templatePath -Destination $configPath
            Write-Host 'Creada configuración local desde la plantilla; no se mostrará su contenido.'
        } else {
            Write-Host 'Se conserva db2.properties existente; no se mostrará ni sobrescribirá.'
        }
        $driverDir = Get-DriverDirectory (Get-Properties $configPath)
        New-Item -ItemType Directory -Force -Path $driverDir | Out-Null
        $existingJars = @(Get-ChildItem -LiteralPath $driverDir -Filter '*.jar' -File)
        if ($existingJars.Count -eq 0) {
            if ([string]::IsNullOrWhiteSpace($DriversSource) -and !$NoEditor) {
                $DriversSource = Read-Host 'Ruta de la carpeta con JAR JDBC DB2 aprobados (no introduzcas credenciales)'
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
                Write-Host ("Copiados {0} JAR aprobados al runtime local." -f $jars.Count)
            } else {
                Write-Warning 'No se indicó la carpeta de drivers.'
            }
        } else {
            Write-Host ("Ya hay {0} JAR; no se sobrescribieron." -f $existingJars.Count)
        }
        $values = Get-Properties $configPath
        $hasUrl = (Test-ConfiguredValue $values 'db2.jdbcUrl') -or
                ((Test-ConfiguredValue $values 'db2.host') -and (Test-ConfiguredValue $values 'db2.database'))
        $configIncomplete = !$hasUrl -or !(Test-ConfiguredValue $values 'db2.user') -or !(Test-ConfiguredValue $values 'db2.password')
        if ($configIncomplete -and !$NoEditor) {
            Write-Host 'Completa host/base/usuario/contraseña localmente en el editor; los valores no se imprimirán.'
            Start-Process -FilePath 'notepad.exe' -ArgumentList @($configPath) -Wait
        }
        if (!(Test-LocalSetup -RequireDb2Config)) { exit 2 }
        Write-Host 'Configuración completa. Inicia con -Action Start y valida con -Action Test.'
    }
    'Check' {
        if (!(Test-LocalSetup -RequireDb2Config)) { exit 2 }
        Write-Host 'Requisitos locales presentes. El chequeo no prueba la conexión DB2.'
    }
    'Test' {
        if (!(Test-LocalSetup -RequireDb2Config)) { exit 2 }
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
        try {
            $result = Invoke-RestMethod -Method Post -Uri "$($BaseUrl.TrimEnd('/'))/query" -ContentType 'application/json' -Body $probe -TimeoutSec 20
        } catch {
            throw 'El servidor respondió, pero falló la consulta DB2 de solo lectura. Revisa el error JDBC localmente; no compartas credenciales.'
        }
        if ($result.rowCount -lt 1) { throw 'La consulta inocua de prueba no devolvió filas.' }
        Write-Host ("Health: OK; consulta DB2 de solo lectura: OK ({0} fila, columnas {1})." -f $result.rowCount, ($result.columns -join ', '))
    }
    'Start' {
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
