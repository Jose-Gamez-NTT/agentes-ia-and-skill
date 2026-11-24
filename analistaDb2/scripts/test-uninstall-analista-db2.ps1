$ErrorActionPreference = 'Stop'
$installer = Join-Path $PSScriptRoot 'install-analista-db2.ps1'
$packageRoot = Split-Path -Parent $PSScriptRoot
$testRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('analista db2 uninstall ' + [guid]::NewGuid().ToString('N'))

function Assert-Condition([bool]$Condition, [string]$Message) {
    if (!$Condition) { throw $Message }
}

try {
    foreach ($scenario in @('installed', 'missing-definition')) {
        $workspace = Join-Path $testRoot $scenario
        $skill = Join-Path $workspace '.axet-code/skills/analista-db2'
        $runtime = Join-Path $skill 'runtime/db2-report-server'
        $agent = Join-Path $workspace '.axet-code/agents/analista-db2.md'
        $command = Join-Path $workspace '.axet-code/commands/db2-configurar.md'
        $externalDriver = Join-Path $workspace 'external-drivers/db2.jar'
        foreach ($directory in @($skill, (Join-Path $runtime 'Drivers_win'), (Join-Path $runtime 'target'), (Split-Path -Parent $agent), (Split-Path -Parent $command), (Split-Path -Parent $externalDriver))) {
            New-Item -ItemType Directory -Force -Path $directory | Out-Null
        }
        Copy-Item -LiteralPath (Join-Path $packageRoot 'agents/analista-db2.md') -Destination $agent
        Copy-Item -LiteralPath (Join-Path $packageRoot 'commands/db2-configurar.md') -Destination $command
        if ($scenario -eq 'installed') {
            Copy-Item -LiteralPath (Join-Path $packageRoot 'skills/analista-db2/SKILL.md') -Destination (Join-Path $skill 'SKILL.md')
        }
        Set-Content -LiteralPath (Join-Path $runtime 'db2.properties') -Value 'local configuration'
        Set-Content -LiteralPath (Join-Path $runtime 'Drivers_win/db2.jar') -Value 'driver'
        Set-Content -LiteralPath (Join-Path $runtime 'target/generated.txt') -Value 'generated'
        Set-Content -LiteralPath $externalDriver -Value 'external driver'

        & $installer -Action Uninstall -Workspace $workspace
        Assert-Condition (!(Test-Path -LiteralPath $skill)) "La skill instalada no se eliminó en $scenario"
        Assert-Condition (!(Test-Path -LiteralPath $runtime)) "El runtime instalado no se eliminó en $scenario"
        Assert-Condition (!(Test-Path -LiteralPath $agent)) "El agente no se eliminó en $scenario"
        Assert-Condition (!(Test-Path -LiteralPath $command)) "El comando no se eliminó en $scenario"
        Assert-Condition (Test-Path -LiteralPath $externalDriver) "Se eliminó un driver externo en $scenario"
        & $installer -Action Uninstall -Workspace $workspace
    }
    Assert-Condition (Test-Path -LiteralPath (Join-Path $packageRoot 'skills/analista-db2/runtime/db2-report-server/pom.xml')) 'Se eliminó el runtime del paquete fuente'
    Write-Host 'Pruebas de desinstalación completadas.'
} finally {
    if (Test-Path -LiteralPath $testRoot) {
        Remove-Item -LiteralPath $testRoot -Recurse -Force
    }
}
