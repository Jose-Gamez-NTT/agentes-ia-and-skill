[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'Medium')]
param(
    [Parameter(Mandatory = $true)][string]$LibraryName,
    [Parameter(Mandatory = $true)][ValidateSet('add', 'delete', 'update', 'list', 'import', 'export', 'get')][string]$Action,
    [string]$Key,
    [string]$Value,
    [switch]$IsSecret,
    [string]$JsonFile,
    [string]$Organization,
    [string]$Project,
    [string]$Pat,
    [ValidateSet('merge', 'replace')][string]$Mode = 'merge'
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$ApiVersion = '7.1-preview.1'
$ScriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$DefaultLibrariesDirectory = Join-Path $ScriptDirectory 'libraries'

function Write-Info {
    param([string]$Message)
    Write-Host "[INFO] $Message" -ForegroundColor Cyan
}

function Write-Success {
    param([string]$Message)
    Write-Host "[OK] $Message" -ForegroundColor Green
}

function Write-Warn {
    param([string]$Message)
    Write-Host "[WARN] $Message" -ForegroundColor Yellow
}

function Write-ErrorMessage {
    param([string]$Message)
    Write-Host "[ERROR] $Message" -ForegroundColor Red
}

function ConvertTo-Hashtable {
    param([Parameter(Mandatory = $false)]$InputObject)

    if ($null -eq $InputObject) {
        return $null
    }

    if ($InputObject -is [hashtable]) {
        $table = @{}
        foreach ($entry in $InputObject.GetEnumerator()) {
            $table[$entry.Key] = ConvertTo-Hashtable -InputObject $entry.Value
        }
        return $table
    }

    if ($InputObject -is [System.Collections.IDictionary]) {
        $table = @{}
        foreach ($entry in $InputObject.GetEnumerator()) {
            $table[$entry.Key] = ConvertTo-Hashtable -InputObject $entry.Value
        }
        return $table
    }

    if ($InputObject -is [System.Collections.IEnumerable] -and -not ($InputObject -is [string])) {
        $list = @()
        foreach ($item in $InputObject) {
            $list += ,(ConvertTo-Hashtable -InputObject $item)
        }
        return $list
    }

    if ($InputObject -is [psobject]) {
        # Forzar array con @() para evitar member-access enumeration en PS 7 con StrictMode
        $props = @($InputObject.PSObject.Properties)
        if ($props.Count -gt 0) {
            $table = @{}
            foreach ($property in $props) {
                $table[$property.Name] = ConvertTo-Hashtable -InputObject $property.Value
            }
            return $table
        }
    }

    return $InputObject
}

function Get-DotEnvValues {
    param([string]$Path)

    $values = @{}
    if (-not (Test-Path -LiteralPath $Path)) {
        return $values
    }

    foreach ($rawLine in Get-Content -LiteralPath $Path) {
        if ($null -eq $rawLine) {
            continue
        }

        $line = $rawLine.Trim()
        if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith('#')) {
            continue
        }

        $separatorIndex = $line.IndexOf('=')
        if ($separatorIndex -lt 1) {
            continue
        }

        $envKey = $line.Substring(0, $separatorIndex).Trim()
        $envValue = $line.Substring($separatorIndex + 1).Trim()

        if (($envValue.StartsWith('"') -and $envValue.EndsWith('"')) -or ($envValue.StartsWith("'") -and $envValue.EndsWith("'"))) {
            $envValue = $envValue.Substring(1, $envValue.Length - 2)
        }

        if (-not [string]::IsNullOrWhiteSpace($envKey)) {
            $values[$envKey] = $envValue
        }
    }

    return $values
}

function Resolve-ConfigValue {
    param(
        [string]$ParameterValue,
        [string]$EnvName,
        [hashtable]$DotEnvValues
    )

    if (-not [string]::IsNullOrWhiteSpace($ParameterValue)) {
        return $ParameterValue
    }

    $envValue = [Environment]::GetEnvironmentVariable($EnvName)
    if (-not [string]::IsNullOrWhiteSpace($envValue)) {
        return $envValue
    }

    if ($null -ne $DotEnvValues -and $DotEnvValues.ContainsKey($EnvName) -and -not [string]::IsNullOrWhiteSpace($DotEnvValues[$EnvName])) {
        return $DotEnvValues[$EnvName]
    }

    return $null
}

function Normalize-OrganizationUrl {
    param([Parameter(Mandatory = $true)][string]$Org)

    $trimmed = $Org.Trim().TrimEnd('/')
    if ($trimmed -match '^https?://') {
        return $trimmed
    }

    return ("https://dev.azure.com/{0}" -f $trimmed)
}

function Get-AuthHeaders {
    param([Parameter(Mandatory = $true)][string]$Token)

    $basicToken = [Convert]::ToBase64String([System.Text.Encoding]::ASCII.GetBytes((":{0}" -f $Token)))
    return @{
        Authorization = "Basic $basicToken"
        Accept        = 'application/json'
    }
}

function Invoke-AdoRequest {
    param(
        [Parameter(Mandatory = $true)][ValidateSet('GET', 'PUT', 'POST')][string]$Method,
        [Parameter(Mandatory = $true)][string]$Uri,
        [hashtable]$Headers,
        $Body
    )

    try {
        if ($null -ne $Body) {
            $jsonBody = $Body | ConvertTo-Json -Depth 100
            return Invoke-RestMethod -Method $Method -Uri $Uri -Headers $Headers -Body $jsonBody -ContentType 'application/json; charset=utf-8'
        }

        return Invoke-RestMethod -Method $Method -Uri $Uri -Headers $Headers
    }
    catch {
        $exception = $_.Exception
        $statusCode = $null
        $responseBody = $null

        if ($null -ne $exception.Response) {
            try {
                $statusCode = [int]$exception.Response.StatusCode
            }
            catch {
                $statusCode = $null
            }

            try {
                $reader = New-Object System.IO.StreamReader($exception.Response.GetResponseStream())
                $responseBody = $reader.ReadToEnd()
                $reader.Dispose()
            }
            catch {
                $responseBody = $null
            }
        }

        if (-not [string]::IsNullOrWhiteSpace($responseBody)) {
            throw "Error REST $Method $Uri. Status: $statusCode. Detalle: $responseBody"
        }

        throw "Error REST $Method $Uri. Detalle: $($exception.Message)"
    }
}

function Get-VariableGroupByName {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$BaseUri,
        [Parameter(Mandatory = $true)][string]$ApiVersion,
        [Parameter(Mandatory = $true)][hashtable]$Headers
    )

    $escapedName = [System.Uri]::EscapeDataString($Name)
    $uri = "{0}?groupName={1}&actionFilter=manage&api-version={2}" -f $BaseUri, $escapedName, $ApiVersion

    $response = Invoke-AdoRequest -Method 'GET' -Uri $uri -Headers $Headers
    if ($null -eq $response -or $null -eq $response.value) {
        return $null
    }

    foreach ($group in $response.value) {
        if ($group.name -eq $Name) {
            return $group
        }
    }

    return $response.value | Select-Object -First 1
}

function Normalize-VariableMap {
    param($VariablesInput)

    $normalized = @{}
    $variables = ConvertTo-Hashtable -InputObject $VariablesInput
    if ($null -eq $variables) {
        return $normalized
    }

    foreach ($entry in $variables.GetEnumerator()) {
        $name = [string]$entry.Key
        $rawValue = $entry.Value

        if ($rawValue -is [hashtable]) {
            $item = @{}

            if ($rawValue.ContainsKey('value')) {
                $item.value = $rawValue['value']
            }
            else {
                $item.value = ''
            }

            if ($rawValue.ContainsKey('isSecret')) {
                $item.isSecret = [bool]$rawValue['isSecret']
            }
            else {
                $item.isSecret = $false
            }

            $normalized[$name] = $item
            continue
        }

        $normalized[$name] = @{
            value    = [string]$rawValue
            isSecret = $false
        }
    }

    return $normalized
}

function Get-SafeProperty {
    param($Object, [string]$Name)
    $props = @($Object.PSObject.Properties | Where-Object { $_.Name -eq $Name })
    if ($props.Count -gt 0) { return $props[0].Value }
    return $null
}

function Build-UpdateBody {
    param(
        $CurrentGroup,
        [hashtable]$Variables
    )

    $groupType = Get-SafeProperty -Object $CurrentGroup -Name 'type'
    if (-not $groupType) { $groupType = 'Vsts' }

    $body = @{
        id          = [int]$CurrentGroup.id
        type        = $groupType
        name        = $CurrentGroup.name
        description = (Get-SafeProperty -Object $CurrentGroup -Name 'description')
        variables   = $Variables
    }

    $projectRefs = Get-SafeProperty -Object $CurrentGroup -Name 'variableGroupProjectReferences'
    if ($null -ne $projectRefs) {
        $body.variableGroupProjectReferences = ConvertTo-Hashtable -InputObject $projectRefs
    }

    $providerData = Get-SafeProperty -Object $CurrentGroup -Name 'providerData'
    if ($null -ne $providerData) {
        $body.providerData = ConvertTo-Hashtable -InputObject $providerData
    }

    return $body
}

function Update-VariableGroup {
    param(
        $CurrentGroup,
        [hashtable]$Variables,
        [Parameter(Mandatory = $true)][string]$BaseUri,
        [Parameter(Mandatory = $true)][string]$ApiVersion,
        [Parameter(Mandatory = $true)][hashtable]$Headers
    )

    $body = Build-UpdateBody -CurrentGroup $CurrentGroup -Variables $Variables
    $uri = "{0}/{1}?api-version={2}" -f $BaseUri, $CurrentGroup.id, $ApiVersion
    return Invoke-AdoRequest -Method 'PUT' -Uri $uri -Headers $Headers -Body $body
}

function New-VariableGroup {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [string]$Description,
        [hashtable]$Variables,
        [Parameter(Mandatory = $true)][string]$BaseUri,
        [Parameter(Mandatory = $true)][string]$ApiVersion,
        [Parameter(Mandatory = $true)][hashtable]$Headers,
        [Parameter(Mandatory = $true)][string]$ProjectName
    )

    $desc = if ([string]::IsNullOrWhiteSpace($Description)) { '' } else { $Description }

    $body = @{
        type        = 'Vsts'
        name        = $Name
        description = $desc
        variables   = $Variables
        variableGroupProjectReferences = @(
            @{
                name             = $Name
                description      = $desc
                projectReference = @{ name = $ProjectName }
            }
        )
    }

    $uri = "{0}?api-version={1}" -f $BaseUri, $ApiVersion
    return Invoke-AdoRequest -Method 'POST' -Uri $uri -Headers $Headers -Body $body
}

try {
    $dotEnvPath = Join-Path $ScriptDirectory '.env'
    $dotEnvValues = Get-DotEnvValues -Path $dotEnvPath

    $resolvedOrganization = Resolve-ConfigValue -ParameterValue $Organization -EnvName 'ADO_ORG' -DotEnvValues $dotEnvValues
    $resolvedProject = Resolve-ConfigValue -ParameterValue $Project -EnvName 'ADO_PROJECT' -DotEnvValues $dotEnvValues
    $resolvedPat = Resolve-ConfigValue -ParameterValue $Pat -EnvName 'ADO_PAT' -DotEnvValues $dotEnvValues

    if ([string]::IsNullOrWhiteSpace($resolvedOrganization)) {
        throw 'No se ha podido resolver Organization. Usa -Organization o define ADO_ORG en variable de entorno/.env.'
    }

    if ([string]::IsNullOrWhiteSpace($resolvedProject)) {
        throw 'No se ha podido resolver Project. Usa -Project o define ADO_PROJECT en variable de entorno/.env.'
    }

    if ([string]::IsNullOrWhiteSpace($resolvedPat)) {
        throw 'No se ha podido resolver Pat. Usa -Pat o define ADO_PAT en variable de entorno/.env.'
    }

    $organizationUrl = Normalize-OrganizationUrl -Org $resolvedOrganization
    $baseUri = "{0}/{1}/_apis/distributedtask/variablegroups" -f $organizationUrl.TrimEnd('/'), $resolvedProject
    $headers = Get-AuthHeaders -Token $resolvedPat

    Write-Info ("Organization: {0}" -f $organizationUrl)
    Write-Info ("Project: {0}" -f $resolvedProject)
    Write-Info ("Library: {0}" -f $LibraryName)
    Write-Info ("Action: {0}" -f $Action)

    $group = Get-VariableGroupByName -Name $LibraryName -BaseUri $baseUri -ApiVersion $ApiVersion -Headers $headers
    if ($null -eq $group -and $Action -ne 'import') {
        throw ("No se encontro la library '{0}'. Verifica nombre, permisos y proyecto." -f $LibraryName)
    }

    $currentVariables = if ($null -ne $group) { Normalize-VariableMap -VariablesInput $group.variables } else { @{} }

    switch ($Action) {
        'list' {
            if ($currentVariables.Count -eq 0) {
                Write-Warn 'La library no tiene variables.'
                break
            }

            Write-Host ''
            Write-Host ("Variables de '{0}':" -f $LibraryName) -ForegroundColor White
            foreach ($varName in ($currentVariables.Keys | Sort-Object)) {
                $varInfo = $currentVariables[$varName]
                $isSecretValue = $false
                if ($varInfo.ContainsKey('isSecret')) {
                    $isSecretValue = [bool]$varInfo.isSecret
                }

                $displayValue = if ($isSecretValue) { '[SECRET]' } else { [string]$varInfo.value }
                Write-Host (" - {0} = {1}" -f $varName, $displayValue) -ForegroundColor Gray
            }
        }

        'get' {
            if ([string]::IsNullOrWhiteSpace($Key)) {
                throw 'La accion get requiere -Key.'
            }

            if (-not $currentVariables.ContainsKey($Key)) {
                throw ("La variable '{0}' no existe en la library '{1}'." -f $Key, $LibraryName)
            }

            $item = $currentVariables[$Key]
            $secretFlag = $false
            if ($item.ContainsKey('isSecret')) {
                $secretFlag = [bool]$item.isSecret
            }

            $valueToShow = if ($secretFlag) { '[SECRET]' } else { [string]$item.value }
            Write-Success ("{0} = {1}" -f $Key, $valueToShow)
        }

        'add' {
            if ([string]::IsNullOrWhiteSpace($Key)) {
                throw 'La accion add requiere -Key.'
            }

            if ($currentVariables.ContainsKey($Key)) {
                throw ("La variable '{0}' ya existe. Usa update para modificarla." -f $Key)
            }

            $currentVariables[$Key] = @{
                value    = $Value
                isSecret = [bool]$IsSecret
            }

            if ($PSCmdlet.ShouldProcess($LibraryName, ("add variable '{0}'" -f $Key))) {
                [void](Update-VariableGroup -CurrentGroup $group -Variables $currentVariables -BaseUri $baseUri -ApiVersion $ApiVersion -Headers $headers)
                Write-Success ("Variable '{0}' anadida correctamente." -f $Key)
            }
        }

        'update' {
            if ([string]::IsNullOrWhiteSpace($Key)) {
                throw 'La accion update requiere -Key.'
            }

            if (-not $currentVariables.ContainsKey($Key)) {
                throw ("La variable '{0}' no existe. Usa add para crearla." -f $Key)
            }

            $existing = $currentVariables[$Key]
            $secretToUse = if ($PSBoundParameters.ContainsKey('IsSecret')) { [bool]$IsSecret } else { [bool]$existing.isSecret }

            $currentVariables[$Key] = @{
                value    = $Value
                isSecret = $secretToUse
            }

            if ($PSCmdlet.ShouldProcess($LibraryName, ("update variable '{0}'" -f $Key))) {
                [void](Update-VariableGroup -CurrentGroup $group -Variables $currentVariables -BaseUri $baseUri -ApiVersion $ApiVersion -Headers $headers)
                Write-Success ("Variable '{0}' actualizada correctamente." -f $Key)
            }
        }

        'delete' {
            if ([string]::IsNullOrWhiteSpace($Key)) {
                throw 'La accion delete requiere -Key.'
            }

            if (-not $currentVariables.ContainsKey($Key)) {
                throw ("La variable '{0}' no existe en la library '{1}'." -f $Key, $LibraryName)
            }

            [void]$currentVariables.Remove($Key)

            if ($PSCmdlet.ShouldProcess($LibraryName, ("delete variable '{0}'" -f $Key))) {
                [void](Update-VariableGroup -CurrentGroup $group -Variables $currentVariables -BaseUri $baseUri -ApiVersion $ApiVersion -Headers $headers)
                Write-Success ("Variable '{0}' eliminada correctamente." -f $Key)
            }
        }

        'export' {
            if ([string]::IsNullOrWhiteSpace($JsonFile)) {
                if (-not (Test-Path -LiteralPath $DefaultLibrariesDirectory)) {
                    New-Item -Path $DefaultLibrariesDirectory -ItemType Directory -Force | Out-Null
                }
                $JsonFile = Join-Path $DefaultLibrariesDirectory ("{0}.json" -f $LibraryName)
            }

            $exportVariables = @{}
            foreach ($varName in ($currentVariables.Keys | Sort-Object)) {
                $varData = $currentVariables[$varName]
                $secretFlag = $false
                if ($varData.ContainsKey('isSecret')) {
                    $secretFlag = [bool]$varData.isSecret
                }

                $exportVariables[$varName] = @{
                    value    = if ($secretFlag) { '' } else { [string]$varData.value }
                    isSecret = $secretFlag
                }
            }

            $exportObject = @{
                libraryName = $LibraryName
                description = if ($null -ne $group.description) { [string]$group.description } else { '' }
                variables   = $exportVariables
            }

            $exportDirectory = Split-Path -Parent $JsonFile
            if (-not [string]::IsNullOrWhiteSpace($exportDirectory) -and -not (Test-Path -LiteralPath $exportDirectory)) {
                New-Item -Path $exportDirectory -ItemType Directory -Force | Out-Null
            }

            $exportObject | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $JsonFile -Encoding UTF8
            Write-Success ("Library exportada en: {0}" -f $JsonFile)
        }

        'import' {
            if ([string]::IsNullOrWhiteSpace($JsonFile)) {
                $JsonFile = Join-Path $DefaultLibrariesDirectory ("{0}.json" -f $LibraryName)
            }

            if (-not (Test-Path -LiteralPath $JsonFile)) {
                throw ("No existe el fichero JSON para importar: {0}" -f $JsonFile)
            }

            $rawJson = Get-Content -LiteralPath $JsonFile -Raw
            if ([string]::IsNullOrWhiteSpace($rawJson)) {
                throw ("El fichero JSON esta vacio: {0}" -f $JsonFile)
            }

            $importObject = $rawJson | ConvertFrom-Json
            if ($null -eq $importObject.variables) {
                throw "El JSON de importacion no contiene la propiedad 'variables'."
            }

            if ($null -ne $importObject.libraryName -and -not [string]::IsNullOrWhiteSpace([string]$importObject.libraryName) -and ([string]$importObject.libraryName -ne $LibraryName)) {
                throw ("El JSON pertenece a la library '{0}' pero se ha solicitado importar sobre '{1}'. Corrige -LibraryName o el contenido del JSON." -f [string]$importObject.libraryName, $LibraryName)
            }

            $importVariables = Normalize-VariableMap -VariablesInput $importObject.variables
            $finalVariables = @{}

            if ($Mode -eq 'merge') {
                foreach ($name in $currentVariables.Keys) {
                    $finalVariables[$name] = ConvertTo-Hashtable -InputObject $currentVariables[$name]
                }
            }

            foreach ($name in $importVariables.Keys) {
                $finalVariables[$name] = ConvertTo-Hashtable -InputObject $importVariables[$name]
            }

            $importDescription = if ($null -ne $importObject.description) { [string]$importObject.description } else { '' }

            if ($PSCmdlet.ShouldProcess($LibraryName, ("import variables from '{0}' (mode={1})" -f $JsonFile, $Mode))) {
                if ($null -eq $group) {
                    Write-Info ("La library '{0}' no existe. Creando..." -f $LibraryName)
                    [void](New-VariableGroup -Name $LibraryName -Description $importDescription -Variables $finalVariables -BaseUri $baseUri -ApiVersion $ApiVersion -Headers $headers -ProjectName $resolvedProject)
                    Write-Success ("Library '{0}' creada con {1} variables." -f $LibraryName, $finalVariables.Count)
                } else {
                    [void](Update-VariableGroup -CurrentGroup $group -Variables $finalVariables -BaseUri $baseUri -ApiVersion $ApiVersion -Headers $headers)
                    Write-Success ("Importacion completada (mode={0}). Variables aplicadas: {1}" -f $Mode, $finalVariables.Count)
                }
            }
        }
    }
}
catch {
    Write-ErrorMessage $_
    exit 1
}
