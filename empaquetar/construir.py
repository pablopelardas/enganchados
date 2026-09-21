"""
Arma la app de escritorio para el sistema en el que corre.

    python empaquetar/construir.py --version v0.1.0

Deja en dist/release/:
    Windows  ->  Enganchados-Windows-Setup.exe   (instalador)
    macOS    ->  Enganchados-macOS-<arq>.zip     (la .app comprimida)

Lo corre GitHub Actions en una maquina de cada sistema (ver
.github/workflows/release.yml), pero anda igual a mano.
"""
import argparse
import hashlib
import os
import platform
import shutil
import stat
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

AQUI = Path(__file__).resolve().parent
RAIZ = AQUI.parent
DESCARGAS = AQUI / "descargas"      # cache: no volver a bajar 200 MB por prueba
BIN = AQUI / "bin"
BUILD = AQUI / "build"
DIST = RAIZ / "dist"
RELEASE = DIST / "release"

YTDLP = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/"
DENO = "https://github.com/denoland/deno/releases/latest/download/"
FFMPEG_MAC = "https://ffmpeg.martin-riedl.de/redirect/latest/macos/{}/release/ffmpeg.zip"

# Que herramienta, de donde, y que archivo adentro del zip (None = no es zip).
# Todas verificadas: el ffmpeg de macOS se reviso leyendo el encabezado del
# binario, porque bajar la arquitectura equivocada revienta sin avisar.
HERRAMIENTAS = {
    "windows": {
        "yt-dlp": (YTDLP + "yt-dlp.exe", None),
        "ffmpeg": ("https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip",
                   "bin/ffmpeg.exe"),
        "deno": (DENO + "deno-x86_64-pc-windows-msvc.zip", "deno.exe"),
    },
    "macos-arm64": {
        "yt-dlp": (YTDLP + "yt-dlp_macos", None),
        "ffmpeg": (FFMPEG_MAC.format("arm64"), "ffmpeg"),
        "deno": (DENO + "deno-aarch64-apple-darwin.zip", "deno"),
    },
    "macos-intel": {
        "yt-dlp": (YTDLP + "yt-dlp_macos", None),
        "ffmpeg": (FFMPEG_MAC.format("amd64"), "ffmpeg"),
        "deno": (DENO + "deno-x86_64-apple-darwin.zip", "deno"),
    },
}


def plataforma() -> str:
    if sys.platform == "win32":
        return "windows"
    if sys.platform == "darwin":
        return "macos-arm64" if platform.machine() == "arm64" else "macos-intel"
    raise SystemExit("Por ahora la app se arma para Windows y macOS")


def bajar(url: str) -> Path:
    DESCARGAS.mkdir(parents=True, exist_ok=True)
    # sha1 y no hash(): el hash() de Python cambia en cada ejecucion, y el
    # cache nunca encontraria lo que bajo la vez anterior
    clave = hashlib.sha1(url.encode()).hexdigest()[:10]
    destino = DESCARGAS / f"{clave}-{url.rstrip('/').split('/')[-1]}"
    if destino.exists() and destino.stat().st_size > 0:
        return destino
    print(f"  bajando {url}")
    pedido = urllib.request.Request(url, headers={"User-Agent": "enganchados-build"})
    with urllib.request.urlopen(pedido, timeout=300) as r, open(destino, "wb") as f:
        shutil.copyfileobj(r, f)
    return destino


def preparar_binarios(plat: str) -> None:
    shutil.rmtree(BIN, ignore_errors=True)
    BIN.mkdir(parents=True)
    exe = ".exe" if plat == "windows" else ""

    for nombre, (url, adentro) in HERRAMIENTAS[plat].items():
        origen = bajar(url)
        destino = BIN / f"{nombre}{exe}"
        if adentro is None:
            shutil.copy2(origen, destino)
        else:
            with zipfile.ZipFile(origen) as z:
                miembro = next(n for n in z.namelist() if n == adentro or n.endswith("/" + adentro))
                destino.write_bytes(z.read(miembro))

        if plat != "windows":
            destino.chmod(destino.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
            # En Apple Silicon un binario sin ninguna firma ni siquiera arranca.
            # Si el original no trae firma valida, se le pone una "ad-hoc".
            if subprocess.run(["codesign", "-v", str(destino)], capture_output=True).returncode:
                subprocess.run(["codesign", "--force", "--sign", "-", str(destino)], check=True)
        print(f"  {nombre:<7} {destino.stat().st_size / 1048576:5.0f} MB")


def empaquetar(plat: str, version: str) -> None:
    sys.path.insert(0, str(AQUI))
    import icono
    ic = icono.generar(BUILD)

    # la version queda visible para quien la use (y en los errores)
    (RAIZ / "enganchado" / "_version.py").write_text(f'VERSION = "{version}"\n', encoding="utf-8")

    sep = os.pathsep
    args = [
        "--noconfirm", "--clean", "--windowed",
        "--name", "Enganchados",
        "--icon", str(ic),
        "--paths", str(RAIZ / "enganchado"),
        "--add-data", f"{RAIZ / 'enganchado' / 'web'}{sep}web",
        "--add-data", f"{RAIZ / 'yt-dlp.conf'}{sep}.",
        # uvicorn carga sus protocolos por nombre en runtime: PyInstaller no
        # los ve al analizar el codigo y la app arrancaria sin poder servir
        "--collect-submodules", "uvicorn",
        # se importan adentro de funciones segun la tarea pedida
        "--hidden-import", "analizar", "--hidden-import", "renderizar",
        "--distpath", str(DIST), "--workpath", str(BUILD), "--specpath", str(BUILD),
    ]
    for f in sorted(BIN.iterdir()):
        args += ["--add-binary", f"{f}{sep}bin"]
    if plat != "windows":
        args += ["--osx-bundle-identifier", "ar.enganchados"]
    args.append(str(RAIZ / "enganchado" / "app.py"))

    import PyInstaller.__main__
    PyInstaller.__main__.run(args)


def instalador_windows(version: str) -> Path | None:
    # winget sin permisos de administrador lo instala en la carpeta del usuario
    local = Path(os.environ.get("LOCALAPPDATA", "")) / "Programs" / "Inno Setup 6" / "ISCC.exe"
    iscc = shutil.which("iscc") or next(
        (str(p) for p in (Path(r"C:\Program Files (x86)\Inno Setup 6\ISCC.exe"),
                          Path(r"C:\Program Files\Inno Setup 6\ISCC.exe"), local)
         if p.exists()), None)
    if not iscc:
        print("  (sin Inno Setup: dejo la carpeta comprimida en vez del instalador)")
        return None
    subprocess.run([
        iscc, "/Q",
        f"/DVersion={version.lstrip('v')}",
        f"/DOrigen={DIST / 'Enganchados'}",
        f"/DSalida={RELEASE}",
        f"/DIcono={BUILD / 'icono.ico'}",
        str(AQUI / "instalador.iss"),
    ], check=True)
    return RELEASE / "Enganchados-Windows-Setup.exe"


def main() -> None:
    ap = argparse.ArgumentParser(description="Arma la app de escritorio")
    ap.add_argument("--version", default="dev")
    args = ap.parse_args()

    plat = plataforma()
    print(f"\n== {plat}, version {args.version}\n\n-- herramientas")
    preparar_binarios(plat)

    print("\n-- empaquetando")
    shutil.rmtree(RELEASE, ignore_errors=True)
    RELEASE.mkdir(parents=True)
    empaquetar(plat, args.version)

    print("\n-- entregable")
    if plat == "windows":
        salida = instalador_windows(args.version)
        if salida is None:
            salida = RELEASE / "Enganchados-Windows.zip"
            shutil.make_archive(str(salida.with_suffix("")), "zip", DIST, "Enganchados")
    else:
        arq = plat.split("-")[1]
        salida = RELEASE / f"Enganchados-macOS-{'AppleSilicon' if arq == 'arm64' else 'Intel'}.zip"
        # ditto y no zip: conserva la estructura, permisos y firma de la .app
        subprocess.run(["ditto", "-c", "-k", "--sequesterRsrc", "--keepParent",
                        str(DIST / "Enganchados.app"), str(salida)], check=True)

    print(f"  {salida.name}  {salida.stat().st_size / 1048576:.0f} MB\n")


if __name__ == "__main__":
    main()
