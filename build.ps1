# Compila SOS y genera sos.jar con un solo comando:
#   .\build.ps1
$ErrorActionPreference = 'Stop'

$raiz = Split-Path -Parent $MyInvocation.MyCommand.Path
$clases = Join-Path $raiz 'build\classes'
$jar = Join-Path $raiz 'sos.jar'
$fuentes = Join-Path $raiz 'src\main\java'

Remove-Item -Recurse -Force $clases -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $clases | Out-Null

$archivos = Get-ChildItem -Recurse -Filter *.java $fuentes | ForEach-Object { $_.FullName }
Write-Host "Compilando $($archivos.Count) archivos..."
& javac -encoding UTF-8 -d $clases $archivos
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Remove-Item -Force $jar -ErrorAction SilentlyContinue
& jar --create --file $jar --main-class com.itescasos.cli.SosCli -C $clases .
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Listo: $jar"
Write-Host "Uso: java -jar sos.jar --help"
