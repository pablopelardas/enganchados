"""
Analiza los temas de un set y PROPONE una receta de enganchado.

No mezcla nada: solo mide y sugiere. La receta que escribe es un JSON
pensado para que vos lo edites a mano antes de renderizar.

Es el mismo algoritmo que la app de Android, portado a numpy. Antes usaba
librosa, que arrastra scipy, numba y llvmlite: cientos de MB solo para
medir el BPM, inviables para empaquetar la app. Asi, ademas, celular y
escritorio analizan igual.

Uso:
    python enganchado/analizar.py 01-carnaval-carioca
    python enganchado/analizar.py 01-carnaval-carioca --duracion 45
"""
import argparse
import json
import subprocess
import sys
from pathlib import Path

# La consola de Windows es cp1252 y varios titulos traen emojis/acentos.
for flujo in (sys.stdout, sys.stderr):
    try:
        flujo.reconfigure(encoding="utf-8", errors="replace")
    except AttributeError:
        pass

import numpy as np

from orden import archivos_ordenados, id_de, sin_id
from rutas import SIN_VENTANA, herramienta

SR = 22050          # suficiente para detectar beats; mas alto solo tarda mas
HOP = 512           # ~43 cuadros por segundo
COMPAS = 4          # 4/4 — vale para todos los generos de estos sets
BPM_MIN, BPM_MAX = 82.0, 164.0

# La raiz de los DATOS: el repo al correr desde el codigo, o
# Documentos/Enganchados en la app empaquetada. Ver rutas.py.
from rutas import DATOS as RAIZ


def envolvente(archivo: Path) -> tuple[np.ndarray, float]:
    """Energia (RMS) por cuadro de HOP muestras, y la duracion en segundos.

    ffmpeg decodifica a PCM mono y se lee DE A PEDAZOS: el tema entero nunca
    esta en memoria. Es lo unico que necesita el analisis.
    """
    proc = subprocess.Popen(
        [herramienta("ffmpeg"), "-v", "error", "-i", str(archivo),
         "-ac", "1", "-ar", str(SR), "-f", "s16le", "-"],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, **SIN_VENTANA,
    )
    cuadro = HOP * 2                      # bytes por cuadro (16 bits)
    partes, resto, muestras = [], b"", 0
    assert proc.stdout is not None
    while True:
        datos = proc.stdout.read(cuadro * 512)
        if not datos:
            break
        datos = resto + datos
        util = len(datos) - len(datos) % cuadro
        resto = datos[util:]
        x = np.frombuffer(datos[:util], dtype="<i2").astype(np.float32) / 32768.0
        muestras += len(x)
        if len(x):
            partes.append(np.sqrt((x.reshape(-1, HOP) ** 2).mean(axis=1)))
    proc.wait()
    if proc.returncode != 0:
        raise RuntimeError(f"ffmpeg no pudo leer {archivo.name}")
    muestras += len(resto) // 2
    rms = np.concatenate(partes) if partes else np.zeros(0, np.float32)
    return rms, muestras / SR


def plegar_bpm(bpm: float, minimo=BPM_MIN, maximo=BPM_MAX) -> float:
    """Corrige errores de octava del detector de beats.

    Los estimadores de tempo eligen mal el nivel metrico muy seguido:
    reportan mitad o doble de tiempo. Ningun tema bailable real vive fuera de
    82-164 BPM, asi que duplicamos o dividimos hasta caer en la banda.
    """
    if bpm <= 0:
        return 0.0
    while bpm < minimo:
        bpm *= 2
    while bpm > maximo:
        bpm /= 2
    return bpm


def estimar_bpm(onsets: np.ndarray) -> float:
    """BPM por autocorrelacion de la curva de onsets."""
    if len(onsets) < 32:
        return 120.0
    por_seg = SR / HOP
    lag_min = max(1, round(por_seg * 60 / 200))
    lag_max = min(len(onsets) // 2, round(por_seg * 60 / 50))
    if lag_max <= lag_min:
        return 120.0
    c = onsets - onsets.mean()
    puntajes = [np.dot(c[:-lag], c[lag:]) / (len(c) - lag) for lag in range(lag_min, lag_max + 1)]
    mejor = lag_min + int(np.argmax(puntajes))
    return 60 * por_seg / mejor


def grilla_de_compases(onsets: np.ndarray, bpm: float, duracion: float) -> list[float]:
    """Arranques de compas.

    Con el BPM se sabe cada cuanto cae un beat; falta la FASE. Se prueban
    corrimientos y gana el que mas energia de onset acumula: ahi estan los
    golpes de verdad.
    """
    por_seg = SR / HOP
    por_beat = 60 / bpm * por_seg if bpm > 0 else 0
    if por_beat < 1 or not len(onsets):
        return [0.0]
    mejor_fase, mejor = 0, -1.0
    for fase in range(max(1, round(por_beat))):
        idx = np.arange(fase, len(onsets), por_beat).astype(int)
        # arange con paso decimal puede generar un elemento de mas, justo
        # afuera del arreglo, por imprecision de coma flotante
        idx = idx[idx < len(onsets)]
        suma = float(onsets[idx].sum())
        if suma > mejor:
            mejor_fase, mejor = fase, suma
    paso = 60 / bpm * COMPAS
    return list(np.arange(mejor_fase / por_seg, duracion, paso)) or [0.0]


def proponer_tramo(rms: np.ndarray, compases: list[float], duracion: float,
                   largo: float) -> tuple[float, float]:
    """Elige el tramo de mayor energia sostenida y lo pega al compas.

    El estribillo casi siempre es la parte mas energica del tema, asi que
    esto ademas esquiva solo las intros habladas y los fade-in largos.
    """
    if not len(rms):
        return 0.0, round(min(largo, duracion), 2)
    por_seg = SR / HOP
    acumulado = np.concatenate([[0.0], np.cumsum(rms, dtype=np.float64)])
    ventana = max(1, int(largo * por_seg))

    candidatos = [c for c in compases if c + largo <= duracion]
    if not candidatos:
        return 0.0, round(min(largo, duracion), 2)

    def energia(inicio: float) -> float:
        a = int(inicio * por_seg)
        b = min(a + ventana, len(rms))
        return (acumulado[b] - acumulado[a]) / (b - a) if b > a else -1.0

    mejor = max(candidatos, key=energia)
    # el corte tambien cae en compas, para que la transicion no quede coja
    fin = min((c for c in compases if c >= mejor + largo), default=mejor + largo)
    return round(float(mejor), 2), round(float(min(fin, duracion)), 2)


def analizar(archivo: Path, duracion: float) -> dict:
    rms, total = envolvente(archivo)
    onsets = np.maximum(0.0, np.diff(rms)) if len(rms) > 1 else np.zeros(0)
    bpm_crudo = estimar_bpm(onsets)
    bpm = plegar_bpm(bpm_crudo)
    compases = grilla_de_compases(onsets, bpm, total)
    inicio, fin = proponer_tramo(rms, compases, total, duracion)

    return {
        # el ID es la identidad del tema; el orden lo pone la receta
        "id": id_de(archivo),
        "archivo": str(archivo.relative_to(RAIZ)).replace("\\", "/"),
        "titulo": sin_id(archivo.stem),
        "bpm": round(bpm, 1),
        "bpm_crudo": round(bpm_crudo, 1),
        "duracion_total": round(total, 2),
        "inicio": inicio,
        "fin": fin,
        "crossfade_siguiente": None,   # None = usa el global de la receta
    }


def main(argv=None):
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
    args = ap.parse_args(argv)

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
