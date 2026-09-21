"""
Dibuja el icono de la app: barras de ecualizador sobre el naranja de la
interfaz (el mismo motivo que la pestana "Armar").

Se genera en cada armado en vez de guardar imagenes en el repo: Windows usa
.ico y macOS .icns, y los dos salen de este mismo dibujo.
"""
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw

NARANJA = (255, 179, 64, 255)
OSCURO = (26, 18, 6, 255)


def dibujar(tam: int = 1024) -> Image.Image:
    img = Image.new("RGBA", (tam, tam), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # margen para que no quede mas grande que los iconos del sistema
    m = round(tam * 0.09)
    d.rounded_rectangle([m, m, tam - m, tam - m], radius=round(tam * 0.22), fill=NARANJA)

    alturas = [0.36, 0.64, 0.48, 0.82, 0.54]
    ancho, hueco = tam * 0.085, tam * 0.055
    x = (tam - (len(alturas) * ancho + (len(alturas) - 1) * hueco)) / 2
    for h in alturas:
        alto = tam * 0.60 * h
        arriba = tam / 2 - alto / 2
        d.rounded_rectangle([x, arriba, x + ancho, arriba + alto],
                            radius=round(ancho / 2), fill=OSCURO)
        x += ancho + hueco
    return img


def generar(carpeta: Path) -> Path:
    """Devuelve el icono en el formato del sistema en el que corre."""
    carpeta.mkdir(parents=True, exist_ok=True)
    grande = dibujar()

    if sys.platform == "darwin":
        # .icns se arma con iconutil (viene en todo macOS) desde un iconset
        iconset = carpeta / "icono.iconset"
        iconset.mkdir(exist_ok=True)
        for base in (16, 32, 128, 256, 512):
            for escala in (1, 2):
                px = base * escala
                nombre = f"icon_{base}x{base}{'@2x' if escala == 2 else ''}.png"
                grande.resize((px, px), Image.LANCZOS).save(iconset / nombre)
        destino = carpeta / "icono.icns"
        subprocess.run(["iconutil", "-c", "icns", str(iconset), "-o", str(destino)], check=True)
        return destino

    destino = carpeta / "icono.ico"
    grande.save(destino, sizes=[(16, 16), (24, 24), (32, 32), (48, 48),
                                (64, 64), (128, 128), (256, 256)])
    return destino


if __name__ == "__main__":
    print(generar(Path(__file__).resolve().parent))
