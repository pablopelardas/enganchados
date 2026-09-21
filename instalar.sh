#!/usr/bin/env bash
# Deja el proyecto listo en macOS o Linux. Se puede correr las veces que
# quieras: lo que ya esta instalado no se toca.
#
#   ./instalar.sh
set -euo pipefail
cd "$(dirname "$0")"

# Abierto con doble clic desde el Finder, el script NO carga tu perfil, asi
# que no ve lo que instalo Homebrew aunque este instalado. Se agrega a mano.
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"

titulo() { printf '\n\033[36m== %s\033[0m\n' "$1"; }
ok()     { printf '   %s\n' "$1"; }
mal()    { printf '   \033[31m%s\033[0m\n' "$1"; }
existe() { command -v "$1" >/dev/null 2>&1; }

printf '\n  \033[35mEnganchados - instalacion\033[0m\n'

# ---------------------------------------------------------------- Python

titulo "Python"
PY=""
for c in python3 python; do
  if existe "$c" && "$c" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)' 2>/dev/null; then
    PY="$c"; break
  fi
done
if [ -z "$PY" ]; then
  mal "Falta Python 3.10 o mas nuevo."
  if [ "$(uname)" = "Darwin" ]; then
    mal "Instalalo con:  brew install python   (o desde https://www.python.org/downloads/)"
  else
    mal "Instalalo con el gestor de tu distro, por ejemplo:  sudo apt install python3 python3-venv"
  fi
  exit 1
fi
ok "$("$PY" --version)"

# ------------------------------------------------- herramientas de audio

titulo "Herramientas de audio"
faltan=()
existe yt-dlp || faltan+=(yt-dlp)   # bajar de YouTube
existe ffmpeg || faltan+=(ffmpeg)   # cortar y mezclar audio
# yt-dlp dejo de soportar YouTube sin un runtime de JavaScript: sin esto
# faltan formatos o directamente fallan descargas.
existe deno   || faltan+=(deno)

if [ ${#faltan[@]} -eq 0 ]; then
  ok "yt-dlp, ffmpeg y deno ya estan"
elif [ "$(uname)" = "Darwin" ]; then
  if ! existe brew; then
    mal "Faltan: ${faltan[*]}"
    mal "Instala Homebrew (https://brew.sh) y volve a correr este script."
    exit 1
  fi
  ok "Instalando ${faltan[*]} con Homebrew..."
  brew install "${faltan[@]}"
else
  mal "Faltan: ${faltan[*]}"
  mal "En Debian/Ubuntu:  sudo apt install ffmpeg  y  pipx install yt-dlp"
  mal "deno:  curl -fsSL https://deno.land/install.sh | sh"
  exit 1
fi

# --------------------------------------------------------- entorno Python

titulo "Entorno de Python"
if [ ! -x .venv/bin/python ]; then
  ok "Creando .venv..."
  "$PY" -m venv .venv
fi
ok "Instalando dependencias (puede tardar unos minutos)..."
.venv/bin/python -m pip install --quiet --upgrade pip
.venv/bin/python -m pip install --quiet -r requirements.txt

titulo "Verificando"
.venv/bin/python -c "
import librosa, numpy, fastapi
print('   librosa', librosa.__version__)
print('   numpy  ', numpy.__version__)
print('   fastapi', fastapi.__version__)
"

mkdir -p sets musica recetas salida

printf '\n  \033[32mListo.\033[0m\n'
if [ "$(uname)" = "Darwin" ]; then
  printf '  \033[32mAhora hace doble clic en Enganchados.command\033[0m\n\n'
else
  printf '  \033[32mAhora corre ./Enganchados.command\033[0m\n\n'
fi
