"""
Analiza los temas de un set y PROPONE una receta de enganchado.

No mezcla nada: solo mide y sugiere. La receta que escribe es un JSON
pensado para que vos lo edites a mano antes de renderizar.

Uso:
    python enganchado/analizar.py 01-carnaval-carioca
    python enganchado/analizar.py 01-carnaval-carioca --duracion 45
"""
import argparse
import json
import subprocess
import sys
import tempfile
from pathlib import Path

# La consola de Windows es cp1252 y varios titulos traen emojis/acentos.
for flujo in (sys.stdout, sys.stderr):
    try:
        flujo.reconfigure(encoding="utf-8", errors="replace")
    except AttributeError:
        pass

import librosa
import numpy as np

from orden import archivos_ordenados, id_de, sin_id

SR = 22050          # suficiente para detectar beats; mas alto solo tarda mas
HOP = 512
COMPAS = 4          # 4/4 — vale para todos los generos de estos sets

RAIZ = Path(__file__).resolve().parent.parent

# Perfiles de Krumhansl-Schmuckler para estimar tonalidad
PERFIL_MAYOR = np.array([6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88])
PERFIL_MENOR = np.array([6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17])
NOTAS = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]


def decodificar(origen: Path) -> str:
    """m4a/opus -> wav mono temporal. librosa 1.0 no lee AAC."""
    tmp = tempfile.NamedTemporaryFile(suffix=".wav", delete=False)
    tmp.close()
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-i", str(origen),
         "-ac", "1", "-ar", str(SR), tmp.name],
        check=True,
    )
    return tmp.name


def plegar_bpm(bpm: float, minimo=82.0, maximo=164.0) -> float:
    """Corrige errores de octava del detector de beats.

    Los beat trackers eligen mal el nivel metrico muy seguido: reportan
    mitad o doble de tiempo. Ningun tema bailable real vive fuera de
    82-164 BPM, asi que duplicamos o dividimos hasta caer en la banda.
    """
    if bpm <= 0:
        return 0.0
    while bpm < minimo:
        bpm *= 2
    while bpm > maximo:
        bpm /= 2
    return bpm


def estimar_tono(y, sr) -> str:
    """Estimacion de tonalidad. Es una PISTA, no una verdad:
    con tanta percusion (cumbia, axe, carnaval) se equivoca seguido."""
    chroma = librosa.feature.chroma_cqt(y=y, sr=sr).mean(axis=1)
    mejor, puntaje = None, -np.inf
    for i in range(12):
        rot = np.roll(chroma, -i)
        for perfil, modo in ((PERFIL_MAYOR, ""), (PERFIL_MENOR, "m")):
            p = np.corrcoef(rot, perfil)[0, 1]
            if p > puntaje:
                mejor, puntaje = f"{NOTAS[i]}{modo}", p
    return mejor


def proponer_tramo(y, sr, compases, duracion):
    """Elige el tramo de mayor energia sostenida y lo pega al inicio de un compas.

    El estribillo casi siempre es la parte mas energica del tema, asi que
    esto ademas esquiva solo las intros habladas y los fade-in largos.
    """
    rms = librosa.feature.rms(y=y, hop_length=HOP)[0]
    t = librosa.frames_to_time(np.arange(len(rms)), sr=sr, hop_length=HOP)
    total = len(y) / sr

    candidatos = [c for c in compases if c + duracion <= total]
    if not candidatos:
        return 0.0, round(total, 2)

    mejor, energia_max = candidatos[0], -np.inf
    for inicio in candidatos:
        ventana = rms[(t >= inicio) & (t < inicio + duracion)]
        if len(ventana) and ventana.mean() > energia_max:
            mejor, energia_max = inicio, ventana.mean()

    # el corte tambien cae en compas, para que la transicion no quede coja
    fin = min((c for c in compases if c >= mejor + duracion), default=mejor + duracion)
    return round(float(mejor), 2), round(float(min(fin, total)), 2)


def analizar(archivo: Path, duracion: float) -> dict:
    wav = decodificar(archivo)
    try:
        y, sr = librosa.load(wav, sr=SR, mono=True)
    finally:
        Path(wav).unlink(missing_ok=True)

    tempo, beats = librosa.beat.beat_track(y=y, sr=sr, units="time")
    bpm_crudo = float(np.atleast_1d(tempo)[0])
    bpm = plegar_bpm(bpm_crudo)
    compases = list(beats[::COMPAS]) if len(beats) else [0.0]

    inicio, fin = proponer_tramo(y, sr, compases, duracion)

    return {
        # el ID es la identidad del tema; el orden lo pone la receta
        "id": id_de(archivo),
        "archivo": str(archivo.relative_to(RAIZ)).replace("\\", "/"),
        "titulo": sin_id(archivo.stem),
        "bpm": round(bpm, 1),
        "bpm_crudo": round(bpm_crudo, 1),
        "tono_pista": estimar_tono(y, sr),
        "duracion_total": round(len(y) / sr, 2),
        "inicio": inicio,
        "fin": fin,
        "crossfade_siguiente": None,   # None = usa el global de la receta
    }


def main():
    ap = argparse.ArgumentParser(description="Propone una receta de enganchado")
    ap.add_argument("set", help="nombre del set, ej: 01-carnaval-carioca")
    ap.add_argument("--duracion", type=float, default=60.0,
                    help="segundos de tema a usar (default: 60)")
    ap.add_argument("--crossfade", type=float, default=4.0,
                    help="segundos de cruce entre temas (default: 4)")
    ap.add_argument("--solo", type=int, nargs="+", metavar="N",
                    help="re-analiza SOLO estos temas (1-based) y respeta "
                         "los ajustes manuales del resto")
    ap.add_argument("--nuevos", action="store_true",
                    help="analiza solo los audios que todavia no estan en la "
                         "receta; los que ya estan quedan con tus ajustes")
    args = ap.parse_args()

    carpeta = RAIZ / "musica" / args.set
    if not carpeta.is_dir():
        raise SystemExit(f"No existe {carpeta}")

    archivos = archivos_ordenados(args.set)
    if not archivos:
        raise SystemExit(f"No hay audio en {carpeta}")

    destino = RAIZ / "recetas" / f"{args.set}.json"

    # Modo quirurgico: toca solo los temas pedidos y deja intacto todo lo
    # demas. Sin esto, re-analizar por un tema roto te borra cada ajuste
    # manual que hiciste en los otros 23.
    if args.solo:
        if not destino.exists():
            raise SystemExit("No hay receta previa: corre un analisis completo primero")

        receta = json.loads(destino.read_text(encoding="utf-8"))
        pedidos = sorted(set(args.solo))
        fuera = [n for n in pedidos if not 1 <= n <= len(receta["temas"])]
        if fuera:
            raise SystemExit(f"Fuera de rango (hay {len(receta['temas'])} temas): {fuera}")

        for n in pedidos:
            archivo = RAIZ / receta["temas"][n - 1]["archivo"]

            if not archivo.exists():
                # El archivo no esta donde dice la receta. Lo buscamos por
                # el ID del video, que es su identidad real; nunca por
                # posicion en la carpeta, porque si reordenaste el
                # enganchado esa posicion ya no significa nada.
                buscado = receta["temas"][n - 1].get("id") or \
                    id_de(Path(receta["temas"][n - 1]["archivo"]))

                if not buscado:
                    raise SystemExit(
                        f"El tema {n} apunta a un archivo que no existe y su "
                        "entrada no tiene ID. Corre un analisis completo.")

                archivo = next((p for p in archivos if id_de(p) == buscado), None)
                if archivo is None:
                    raise SystemExit(
                        f"El tema {n} (id {buscado}) no esta bajado. "
                        "Baja el tema antes de re-analizarlo.")

            print(f"  [solo {n}] {archivo.stem[:60]}", flush=True)
            receta["temas"][n - 1] = analizar(archivo, args.duracion)

        destino.write_text(json.dumps(receta, indent=2, ensure_ascii=False), encoding="utf-8")
        print(f"\nRe-analizados {len(pedidos)} tema(s). El resto quedo como estaba.")
        return

    # Modo incremental: lo que ya estaba analizado se conserva tal cual (con
    # los tramos que ajustaste a mano) y solo se analiza lo nuevo. Es lo que
    # hace falta al agregar temas a un enganchado que ya venias puliendo;
    # re-analizar todo te borraria ese trabajo.
    previos = {}
    cruce = args.crossfade
    if args.nuevos and destino.exists():
        anterior = json.loads(destino.read_text(encoding="utf-8"))
        previos = {t["id"]: t for t in anterior.get("temas", []) if t.get("id")}
        cruce = anterior.get("crossfade_seg", cruce)

    temas, fallos = [], []
    conservados = 0
    for i, archivo in enumerate(archivos, 1):
        ya = previos.get(id_de(archivo))
        if ya:
            conservados += 1
            ya["archivo"] = str(archivo.relative_to(RAIZ)).replace("\\", "/")
            temas.append(ya)
            continue
        print(f"  [{i:02d}/{len(archivos)}] {archivo.stem[:60]}", flush=True)
        try:
            temas.append(analizar(archivo, args.duracion))
        except Exception as e:
            fallos.append(f"{archivo.name}: {type(e).__name__} {e}")
            print(f"       -> FALLO, lo salteo", flush=True)

    if args.nuevos:
        print(f"\n  {len(temas) - conservados} nuevos analizados, "
              f"{conservados} conservados con tus ajustes.", flush=True)

    receta = {
        "set": args.set,
        "salida": f"salida/{args.set}.m4a",
        "crossfade_seg": cruce,
        "normalizar_volumen": True,
        "temas": temas,
    }

    if fallos:
        print("\nNo pude analizar:")
        for f in fallos:
            print(f"  - {f}")

    destino.write_text(json.dumps(receta, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"\nReceta escrita en: recetas/{args.set}.json")
    print("Editala (inicio/fin/orden) y despues corre renderizar.py")


if __name__ == "__main__":
    main()
