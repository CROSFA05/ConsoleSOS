param(
    [switch]$Build,
    [switch]$Doctor,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Args
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Show-Usage {
    Write-Host "SOS Launcher - Consola de Simulacion de Sistemas Operativos" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "Uso:" -ForegroundColor Yellow
    Write-Host "  .\sos.ps1                           Abre la consola grafica (Swing)"
    Write-Host "  .\sos.ps1 <subcomando> [args...]    Ejecuta un comando directo en CLI"
    Write-Host "  .\sos.ps1 -Build [subcomando...]    Compila con 'mvn clean package' y luego ejecuta"
    Write-Host "  .\sos.ps1 -Doctor                   Verifica Java y Maven en el sistema"
    Write-Host ""
    Write-Host "Ejemplos:" -ForegroundColor Yellow
    Write-Host "  .\sos.ps1 sim run escenarios\escenario1.txt"
    Write-Host "  .\sos.ps1 -Build sim plan escenarios\planificacion.txt"
}

function Invoke-Doctor {
    Write-Host "Verificando entorno..." -ForegroundColor Cyan
    $javaCmd = Get-Command java -ErrorAction SilentlyContinue
    $mvnCmd  = Get-Command mvn  -ErrorAction SilentlyContinue

    if ($javaCmd) {
        $javaVer = & java -version 2>&1 | Select-Object -First 1
        Write-Host "[OK] Java encontrado: $javaVer" -ForegroundColor Green
    } else {
        Write-Host "[ERROR] 'java' no esta disponible en el PATH." -ForegroundColor Red
    }

    if ($mvnCmd) {
        $mvnVer = & mvn -version 2>&1 | Select-Object -First 1
        Write-Host "[OK] Maven encontrado: $mvnVer" -ForegroundColor Green
    } else {
        Write-Host "[WARN] 'mvn' no esta disponible en el PATH (solo necesario para compilar)." -ForegroundColor Yellow
    }
}

function Resolve-SosJar {
    $targetDir = Join-Path $PSScriptRoot "target"
    if (-not (Test-Path -Path $targetDir)) {
        return $null
    }

    $preferred = Join-Path $targetDir "consolesos-1.0-SNAPSHOT.jar"
    if (Test-Path -Path $preferred) {
        return $preferred
    }

    $candidate = Get-ChildItem -Path $targetDir -Filter "*.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike "original-*" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if ($candidate) {
        return $candidate.FullName
    }
    return $null
}

if ($Doctor) {
    Invoke-Doctor
    exit 0
}

if ($Args -and $Args.Count -eq 1 -and ($Args[0] -eq "--help" -or $Args[0] -eq "-h" -or $Args[0] -eq "ayuda")) {
    Show-Usage
    exit 0
}

if ($Build) {
    Write-Host "Compilando proyecto con Maven (mvn clean package)..." -ForegroundColor Cyan
    Push-Location $PSScriptRoot
    try {
        & mvn clean package
        if ($LASTEXITCODE -ne 0) {
            Write-Error "Fallo la compilacion con Maven (codigo $LASTEXITCODE)."
            exit $LASTEXITCODE
        }
    }
    finally {
        Pop-Location
    }
}

$jar = Resolve-SosJar
if (-not $jar) {
    Write-Error "No se encontro ningun archivo .jar en 'target\'. Ejecuta 'mvn clean package' o '.\sos.ps1 -Build'."
    exit 1
}

$javaArgs = @("-Dfile.encoding=UTF-8", "-jar", $jar)
if ($Args -and $Args.Count -gt 0) {
    $javaArgs += $Args
}

& java @javaArgs
exit $LASTEXITCODE