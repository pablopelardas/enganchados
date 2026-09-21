<#
  Deja el proyecto listo para usar. Se puede correr las veces que quieras:
  lo que ya esta instalado no se toca.

  Uso:
    .\instalar.ps1
#>
$ErrorActionPreference = "Stop"
$raiz = $PSScriptRoot

function Titulo($texto) {
    Write-Host ""
    Write-Host "== $texto" -ForegroundColor Cyan
}

function Existe($comando) {
    $null -ne (Get-Command $comando -ErrorAction SilentlyContinue)
}

function InstalarHerramienta($comando, $idWinget, $idScoop, $paraQue) {
    if (Existe $comando) {
        Write-Host "   $comando ya esta" -ForegroundColor DarkGray
        return $true
    }

    Write-Host "   Falta $comando ($paraQue). Instalando..." -ForegroundColor Yellow

    if (Existe "winget") {
        winget install --id $idWinget --accept-source-agreements --accept-package-agreements -h 2>&1 | Out-Null
    } elseif (Existe "scoop") {
        scoop install $idScoop 2>&1 | Out-Null
    }

    # el PATH de la sesion actual no se entera de lo recien instalado
    $env:Path = [Environment]::GetEnvironmentVariable("Path", "Machine") + ";" +
                [Environment]::GetEnvironmentVariable("Path", "User")

    if (Existe $comando) {
        Write-Host "   $comando instalado" -ForegroundColor Green
        return $true
    }

    Write-Host "   No pude instalar $comando solo." -ForegroundColor Red
    Write-Host "   Instalalo a mano y volve a correr este script." -ForegroundColor Red
    return $false
}

Write-Host ""
Write-Host "  Enganchados - instalacion" -ForegroundColor Magenta
Write-Host "  ---------------------------" -ForegroundColor Magenta

# ------------------------------------------------------------ Python

Titulo "Python"
if (-not (Existe "python")) {
    Write-Host "   No encontre Python." -ForegroundColor Red
    Write-Host "   Instalalo desde https://www.python.org/downloads/" -ForegroundColor Red
    Write-Host "   IMPORTANTE: tildar 'Add Python to PATH' durante la instalacion." -ForegroundColor Yellow
    exit 1
}
$version = (python --version 2>&1)
Write-Host "   $version" -ForegroundColor DarkGray

# ------------------------------------------------- Herramientas externas

Titulo "Herramientas de audio"
$ok = $true
$ok = (InstalarHerramienta "yt-dlp" "yt-dlp.yt-dlp" "yt-dlp" "bajar de YouTube") -and $ok
$ok = (InstalarHerramienta "ffmpeg" "Gyan.FFmpeg" "ffmpeg" "cortar y mezclar audio") -and $ok

# yt-dlp dejo de soportar YouTube sin un runtime de JavaScript: sin esto
# faltan formatos o directamente fallan descargas.
$ok = (InstalarHerramienta "deno" "DenoLand.Deno" "deno" "yt-dlp lo necesita para YouTube") -and $ok

if (-not $ok) {
    Write-Host ""
    Write-Host "  Falto alguna herramienta. Revisá los mensajes de arriba." -ForegroundColor Red
    exit 1
}

# --------------------------------------------------------- Entorno Python

Titulo "Entorno de Python"
$venv = Join-Path $raiz ".venv"
$py = Join-Path $venv "Scripts\python.exe"

if (-not (Test-Path $py)) {
    Write-Host "   Creando .venv..." -ForegroundColor DarkGray
    python -m venv $venv
}

Write-Host "   Instalando dependencias (puede tardar unos minutos)..." -ForegroundColor DarkGray
& $py -m pip install --quiet --upgrade pip
& $py -m pip install --quiet -r (Join-Path $raiz "requirements.txt")

Titulo "Verificando"
& $py -c @"
import librosa, soundfile, numpy, fastapi, uvicorn
print('   librosa', librosa.__version__)
print('   numpy  ', numpy.__version__)
print('   fastapi', fastapi.__version__)
"@

# --------------------------------------------------------------- Carpetas

foreach ($carpeta in @("sets", "musica", "recetas", "salida")) {
    New-Item -ItemType Directory -Force -Path (Join-Path $raiz $carpeta) | Out-Null
}

Write-Host ""
Write-Host "  Listo." -ForegroundColor Green
Write-Host "  Ahora hace doble clic en Enganchados.bat" -ForegroundColor Green
Write-Host ""
