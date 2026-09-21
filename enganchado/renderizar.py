"""
Renderiza el enganchado a partir de una receta.

No analiza nada: solo ejecuta lo que dice el JSON. Si algo no te gusta,
tocas la receta y volves a correr esto — son segundos, no minutos.

Uso:
    python enganchado/renderizar.py 01-carnaval-carioca
    python enganchado/renderizar.py 01-carnaval-carioca --preview 4
    python enganchado/renderizar.py 01-carnaval-carioca --stems
"""
import argparse
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

for flujo in (sys.stdout, sys.stderr):
    try:
        flujo.reconfigure(encoding="utf-8", errors="replace")
    except AttributeError:
        pass

# La raiz de los DATOS: el repo al correr desde el codigo, o
# Documentos/Enganchados en la app empaquetada. Ver rutas.py.
from rutas import DATOS as RAIZ
from rutas import SIN_VENTANA, herramienta
SR = 44100

# -14 LUFS es el estandar de streaming. Lo importante no es el numero
# exacto sino que TODOS los temas queden igual: sin esto el enganchado
# salta de susurro a bocinazo entre tema y tema.
LOUDNORM = "loudnorm=I=-14:TP=-1.5:LRA=11"


def ffmpeg(args):
    # el ffmpeg que trae la app si esta; si no, el del sistema (rutas.py)
    subprocess.run([herramienta("ffmpeg"), "-v", "error", "-y", *args],
                   check=True, **SIN_VENTANA)


def extraer(tema, destino, crossfade):
    """Corta el tramo pedido, lo nivela y lo deja en wav.

    Pide un poco mas de audio del que se ve: el cruce se come `crossfade`
    segundos en cada union, asi que sin ese colchon perderiamos el final
    de cada tramo.
    """
    inicio = float(tema["inicio"])
    fin = min(float(tema["fin"]) + crossfade, float(tema["duracion_total"]))
    duracion = max(fin - inicio, 1.0)

    ffmpeg([
        "-ss", f"{inicio:.3f}",
        "-t", f"{duracion:.3f}",
        "-i", str(RAIZ / tema["archivo"]),
        "-vn",
        "-af", LOUDNORM,
        "-ac", "2", "-ar", str(SR),
        str(destino),
    ])
    return duracion


def item_rpp(colocado, crossfade):
    """Un <ITEM> de Reaper: un tramo puesto en la linea de tiempo."""
    entra = 0.0 if colocado["primero"] else crossfade
    sale = 0.0 if colocado["ultimo"] else crossfade
    titulo = colocado["titulo"].replace('"', "'")
    return [
        "    <ITEM",
        f"      POSITION {colocado['posicion']:.6f}",
        f"      LENGTH {colocado['largo']:.6f}",
        "      SOFFS 0",
        f"      FADEIN 1 {entra:.6f} 0 1 0 0 0",
        f"      FADEOUT 1 {sale:.6f} 0 1 0 0 0",
        f'      NAME "{titulo}"',
        "      <SOURCE WAVE",
        f'        FILE "{colocado["archivo"]}"',
        "      >",
        "    >",
    ]


def escribir_rpp(destino, bpm, colocados, crossfade):
    """Genera un proyecto de Reaper con los tramos ya posicionados.

    El .rpp es texto plano, por eso se puede escribir desde aca. Los tramos
    alternan entre dos pistas (A y B) para que los solapes queden visibles
    y editables por separado, mas una pista vacia para tu beat.
    """
    lineas = ['<REAPER_PROJECT 0.1 "7.0" 0', f"  TEMPO {bpm:.2f} 4 4"]

    for nombre, resto in (("A", 0), ("B", 1)):
        lineas += ["  <TRACK", f'    NAME "{nombre}"']
        for i, colocado in enumerate(colocados):
            if i % 2 == resto:
                lineas += item_rpp(colocado, crossfade)
        lineas.append("  >")

    lineas += ["  <TRACK", '    NAME "BEAT"', "  >", ">"]
    destino.write_text("\n".join(lineas) + "\n", encoding="utf-8")


def exportar_stems(temas, nombre_set, crossfade):
    carpeta = RAIZ / "salida" / f"{nombre_set}-stems"
    carpeta.mkdir(parents=True, exist_ok=True)

    print(f"Exportando {len(temas)} tramos sueltos...")
    colocados, posicion = [], 0.0
    for i, tema in enumerate(temas):
        archivo = f"{i:02d}.wav"
        largo = extraer(tema, carpeta / archivo, crossfade)
        colocados.append({
            "archivo": archivo,
            "titulo": tema["titulo"],
            "posicion": posicion,
            "largo": largo,
            "primero": i == 0,
            "ultimo": i == len(temas) - 1,
        })
        print(f"  [{i+1:02d}/{len(temas)}] {archivo}  @ {posicion/60:>5.1f} min  {tema['titulo'][:46]}")
        # el siguiente arranca ANTES de que termine este: ese solape es
        # el cruce. Sin eso quedarian pegados y sin transicion.
        posicion += largo - crossfade

    bpms = sorted(t["bpm"] for t in temas)
    mediana = bpms[len(bpms) // 2]
    escribir_rpp(carpeta / f"{nombre_set}.rpp", mediana, colocados, crossfade)

    print(f"\nStems:    salida/{nombre_set}-stems/  ({len(temas)} wav)")
    print(f"Proyecto: salida/{nombre_set}-stems/{nombre_set}.rpp  (tempo {mediana:.0f} BPM)")
    print("\nAbri el .rpp con Reaper, o arrastra los wav a cualquier DAW.")


def mezclar(temas, salida, crossfade):
    tmp = Path(tempfile.mkdtemp(prefix="enganchado-"))
    try:
        print(f"Cortando y nivelando {len(temas)} tramos...")
        partes = []
        for i, tema in enumerate(temas):
            parte = tmp / f"{i:02d}.wav"
            extraer(tema, parte, crossfade)
            partes.append(parte)
            print(f"  [{i+1:02d}/{len(temas)}] {tema['bpm']:>5.1f} BPM  {tema['titulo'][:50]}")

        entradas = []
        for parte in partes:
            entradas += ["-i", str(parte)]

        # Cadena de cruces: cada acrossfade une el resultado anterior con
        # el tema siguiente, solapandolos. Por eso no quedan huecos.
        filtros, previo = [], "[0:a]"
        for i in range(1, len(partes)):
            etiqueta = "[mix]" if i == len(partes) - 1 else f"[x{i}]"
            filtros.append(f"{previo}[{i}:a]acrossfade=d={crossfade}:c1=tri:c2=tri{etiqueta}")
            previo = etiqueta

        print(f"\nCruzando con {crossfade}s de solape...")
        ffmpeg([
            *entradas,
            "-filter_complex", ";".join(filtros),
            "-map", "[mix]",
            "-c:a", "aac", "-b:a", "192k",
            str(salida),
        ])
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main(argv=None):
    ap = argparse.ArgumentParser(description="Renderiza un enganchado desde su receta")
    ap.add_argument("set", help="nombre del set, ej: 01-carnaval-carioca")
    ap.add_argument("--crossfade", type=float, default=None,
                    help="segundos de cruce (default: el de la receta)")
    ap.add_argument("--preview", type=int, default=0,
                    help="renderiza solo los primeros N temas, para probar rapido")
    ap.add_argument("--ordenar-por-bpm", action="store_true",
                    help="reordena de menor a mayor BPM en vez de respetar la receta")
    ap.add_argument("--stems", action="store_true",
                    help="exporta los tramos sueltos + proyecto de Reaper en vez de la mezcla")
    args = ap.parse_args(argv)

    receta_path = RAIZ / "recetas" / f"{args.set}.json"
    if not receta_path.exists():
        raise SystemExit(f"No existe {receta_path}. Corre analizar.py primero.")

    receta = json.loads(receta_path.read_text(encoding="utf-8"))
    crossfade = args.crossfade if args.crossfade is not None else float(receta["crossfade_seg"])
    temas = list(receta["temas"])

    if args.ordenar_por_bpm:
        temas.sort(key=lambda t: t["bpm"])
    if args.preview:
        temas = temas[:args.preview]

    if len(temas) < 2:
        raise SystemExit("Hacen falta al menos 2 temas")

    if args.stems:
        exportar_stems(temas, args.set, crossfade)
        return

    salida = RAIZ / receta["salida"]
    if args.preview:
        salida = salida.with_name(f"{salida.stem}-preview{salida.suffix}")
    salida.parent.mkdir(parents=True, exist_ok=True)

    mezclar(temas, salida, crossfade)

    # La duracion sale de la receta: cada union se come un cruce, pero el
    # ultimo tramo conserva su cola. Sin ffprobe, que seria un binario mas
    # para empaquetar solo para mostrar este numero.
    minutos = (sum(t["fin"] - t["inicio"] for t in temas) + crossfade) / 60
    mb = salida.stat().st_size / 1024 / 1024
    print(f"\nListo: {salida.relative_to(RAIZ)}")
    print(f"       ~{minutos:.1f} min  |  {mb:.1f} MB")


if __name__ == "__main__":
    main()
