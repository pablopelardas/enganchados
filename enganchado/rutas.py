"""
Donde vive cada cosa.

Corriendo desde el repo, codigo y datos estan en la misma carpeta, como
siempre. Empaquetado como app no pueden: la app instalada es de solo lectura
(en macOS la protege el sistema, en Windows puede estar en Archivos de
programa), asi que los enganchados van a Documentos/Enganchados.
"""
import json
import os
import shutil
import stat
import sys
from pathlib import Path

# True cuando corre como app empaquetada con PyInstaller.
CONGELADO = getattr(sys, "frozen", False)

# El codigo y lo que viaja con el (web/, yt-dlp.conf, binarios): la carpeta
# del paquete, o la que arma PyInstaller al empaquetar.
CODIGO = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))


def _carpeta_config() -> Path:
    """Donde cada sistema espera la configuracion de una app."""
    if os.name == "nt":
        return Path(os.environ.get("APPDATA", Path.home())) / "Enganchados"
    if sys.platform == "darwin":
        return Path.home() / "Library" / "Application Support" / "Enganchados"
    return Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / "enganchados"


# La carpeta de datos elegida. La escribe el instalador de Windows (en
# {userappdata}\Enganchados, la misma ruta) o la app al cambiarla desde el
# menu, y se lee al arrancar.
CONFIG = _carpeta_config() / "config.json"


def _datos() -> Path:
    if os.environ.get("ENGANCHADOS_DATOS"):          # para pruebas
        return Path(os.environ["ENGANCHADOS_DATOS"])
    if CONGELADO:
        try:
            elegida = json.loads(CONFIG.read_text(encoding="utf-8")).get("datos")
            if elegida:
                return Path(elegida)
        except (OSError, ValueError):
            pass                                     # sin config: el default
        return Path.home() / "Documents" / "Enganchados"
    return Path(__file__).resolve().parent.parent    # el repo


def guardar_datos(carpeta: Path) -> None:
    CONFIG.parent.mkdir(parents=True, exist_ok=True)
    CONFIG.write_text(json.dumps({"datos": str(carpeta)}, ensure_ascii=False), encoding="utf-8")


DATOS = _datos()

# En el repo yt-dlp.conf esta en la raiz; empaquetado viaja junto al codigo.
CONFIG_YTDLP = next(
    (p for p in (CODIGO / "yt-dlp.conf", CODIGO.parent / "yt-dlp.conf") if p.exists()),
    CODIGO / "yt-dlp.conf",
)

EXE = ".exe" if os.name == "nt" else ""

# Los binarios que trae la app (ffmpeg, yt-dlp, deno) se COPIAN a los datos
# antes de usarlos, por dos razones:
#   - yt-dlp se actualiza a si mismo y adentro de la app no puede escribir.
#   - en macOS, lo que se bajo de internet queda "en cuarentena" y el sistema
#     bloquea cada ejecutable suelto; una copia hecha por la app no hereda eso.
BIN_APP = CODIGO / "bin"
BIN = DATOS / ".bin"

HERRAMIENTAS = ("ffmpeg", "yt-dlp", "deno")

# En Windows, lanzar un programa de consola desde una app de ventana abre una
# consola negra por un instante. Esto la evita en cada subproceso.
SIN_VENTANA = {"creationflags": 0x08000000} if os.name == "nt" else {}


def herramienta(nombre: str) -> str:
    """La copia de la app si esta; si no, la del sistema (modo repo)."""
    propia = BIN / f"{nombre}{EXE}"
    return str(propia) if propia.exists() else nombre


def preparar_herramientas() -> None:
    """Copia los binarios de la app a los datos y los pone en el PATH.

    yt-dlp busca deno y ffmpeg en el PATH, asi que tambien tienen que estar
    ahi. Solo copia lo que falta o cambio de tamano (una version nueva de la
    app); yt-dlp no se pisa si ya estaba, porque puede haberse actualizado.
    """
    if not BIN_APP.is_dir():
        return
    BIN.mkdir(parents=True, exist_ok=True)
    for nombre in HERRAMIENTAS:
        origen = BIN_APP / f"{nombre}{EXE}"
        destino = BIN / f"{nombre}{EXE}"
        if not origen.exists():
            continue
        if destino.exists() and (nombre == "yt-dlp" or
                                 destino.stat().st_size == origen.stat().st_size):
            continue
        shutil.copy2(origen, destino)
        if os.name != "nt":
            destino.chmod(destino.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    os.environ["PATH"] = str(BIN) + os.pathsep + os.environ.get("PATH", "")
