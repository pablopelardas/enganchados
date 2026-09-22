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
import wave
from pathlib import Path

import numpy as np

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


# Mismos valores que la app de Android (Union.kt): las dos tienen que
# recortar igual el mismo tramo.
UMBRAL_SILENCIO = 0.006   # RMS, ~ -44 dBFS
VENTANA_S = 0.02
MARGEN_S = 0.05


def limites_sin_silencio(x, sr):
    """(desde, hasta) en cuadros, sin el silencio de las puntas.

    Muchos uploads de YouTube terminan con segundos mudos y otros arrancan
    igual. Si el tramo llega hasta ahi, el cruce funde silencio con silencio
    y en el medio del enganchado queda un hueco. Se deja un margen chico
    para no morder el ataque de la primera nota.
    """
    cuadros = len(x)
    ventana = max(1, int(sr * VENTANA_S))
    margen = int(sr * MARGEN_S)
    n = -(-cuadros // ventana)

    v = x.astype(np.float32) / 32768
    relleno = np.zeros((n * ventana - cuadros, x.shape[1]), dtype=np.float32)
    energia = np.concatenate([v, relleno]).reshape(n, ventana * x.shape[1])
    # el relleno de la ultima ventana no cuenta en el promedio
    muestras = np.full(n, ventana * x.shape[1])
    muestras[-1] = (cuadros - (n - 1) * ventana) * x.shape[1]
    suenan = np.flatnonzero(np.sqrt((energia ** 2).sum(axis=1) / muestras) > UMBRAL_SILENCIO)

    if len(suenan) == 0:
        return 0, cuadros
    desde = max(0, suenan[0] * ventana - margen)
    hasta = min(cuadros, (suenan[-1] + 1) * ventana + margen)
    return int(desde), int(hasta)


def recortar_silencio(wav):
    """Aplica limites_sin_silencio sobre un wav de 16 bits, en el lugar."""
    with wave.open(str(wav), "rb") as f:
        params = f.getparams()
        x = np.frombuffer(f.readframes(params.nframes), dtype=np.int16)
    x = x.reshape(-1, params.nchannels)
    desde, hasta = limites_sin_silencio(x, params.framerate)
    if (desde, hasta) == (0, len(x)):
        return
    with wave.open(str(wav), "wb") as f:
        f.setparams(params)
        f.writeframes(x[desde:hasta].tobytes())


def largo_wav(wav):
    with wave.open(str(wav), "rb") as f:
        return f.getnframes() / f.getframerate()


def cruce_efectivo(largos, crossfade):
    """Nunca mas de la mitad del tramo mas corto: se lo comeria entero."""
    return min(crossfade, min(largos) / 2)


def cruces_de(largos, crossfade):
    """Segundo donde empieza cada cruce, con los largos REALES de los tramos.

    Antes se deducia de la receta (fin - inicio), pero un tramo que llega al
    final del tema sale mas corto de lo pedido, y los saltos del reproductor
    caian cada vez mas lejos de la transicion.
    """
    d = cruce_efectivo(largos, crossfade)
    cruces, acumulado = [], largos[0]
    for largo in largos[1:]:
        cruces.append(acumulado - d)
        acumulado += largo - d
    return cruces


def archivo_cruces(salida):
    return salida.with_suffix(".cruces.json")


def extraer(tema, destino, crossfade):
    """Corta el tramo pedido, le saca el silencio, lo nivela y lo deja en wav.

    Pide un poco mas de audio del que se ve: el cruce se come `crossfade`
    segundos en cada union, asi que sin ese colchon perderiamos el final
    de cada tramo. Devuelve el largo real, que puede ser menor.
    """
    inicio = float(tema["inicio"])
    fin = min(float(tema["fin"]) + crossfade, float(tema["duracion_total"]))
    duracion = max(fin - inicio, 1.0)
    crudo = destino.with_name(f"_crudo-{destino.name}")

    # El silencio sale ANTES de nivelar: loudnorm levanta las partes bajas y
    # el ruido de fondo de una cola muda dejaria de parecer silencio.
    ffmpeg([
        "-ss", f"{inicio:.3f}",
        "-t", f"{duracion:.3f}",
        "-i", str(RAIZ / tema["archivo"]),
        "-vn",
        "-ac", "2", "-ar", str(SR), "-c:a", "pcm_s16le",
        str(crudo),
    ])
    recortar_silencio(crudo)
    ffmpeg(["-i", str(crudo), "-af", LOUDNORM, "-ar", str(SR), "-c:a", "pcm_s16le", str(destino)])
    crudo.unlink()
    return largo_wav(destino)


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
        partes, largos = [], []
        for i, tema in enumerate(temas):
            parte = tmp / f"{i:02d}.wav"
            largos.append(extraer(tema, parte, crossfade))
            partes.append(parte)
            print(f"  [{i+1:02d}/{len(temas)}] {tema['bpm']:>5.1f} BPM  {tema['titulo'][:50]}")

        entradas = []
        for parte in partes:
            entradas += ["-i", str(parte)]

        # Cadena de cruces: cada acrossfade une el resultado anterior con
        # el tema siguiente, solapandolos. Por eso no quedan huecos.
        # qsin es de igual potencia: el tri (lineal) deja un pozo de ~3 dB
        # en el medio del cruce y se siente que la musica "se cae".
        d = cruce_efectivo(largos, crossfade)
        filtros, previo = [], "[0:a]"
        for i in range(1, len(partes)):
            etiqueta = "[mix]" if i == len(partes) - 1 else f"[x{i}]"
            filtros.append(f"{previo}[{i}:a]acrossfade=d={d:.3f}:c1=qsin:c2=qsin{etiqueta}")
            previo = etiqueta

        print(f"\nCruzando con {d:.1f}s de solape...")
        ffmpeg([
            *entradas,
            "-filter_complex", ";".join(filtros),
            "-map", "[mix]",
            "-c:a", "aac", "-b:a", "192k",
            str(salida),
        ])
        # Donde quedo cada cruce, para que el reproductor salte ahi.
        archivo_cruces(salida).write_text(json.dumps(cruces_de(largos, crossfade)), encoding="utf-8")
        return sum(largos) - d * (len(largos) - 1)
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

    minutos = mezclar(temas, salida, crossfade) / 60
    mb = salida.stat().st_size / 1024 / 1024
    print(f"\nListo: {salida.relative_to(RAIZ)}")
    print(f"       ~{minutos:.1f} min  |  {mb:.1f} MB")


if __name__ == "__main__":
    main()
