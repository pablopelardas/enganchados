"""
De donde sale el orden del enganchado.

Regla unica del proyecto: el nombre del archivo dice QUE video es, nunca
donde va. El orden vive en _orden.txt (posicion -> ID, que escribe la
descarga) y en la receta. Por eso reordenar el enganchado no renombra
un solo archivo.

Lo usan analizar.py y servidor.py. Sin dependencias pesadas a proposito:
el server no tiene por que cargar librosa para listar archivos.
"""
import re
from pathlib import Path

# La raiz de los DATOS: el repo al correr desde el codigo, o
# Documentos/Enganchados en la app empaquetada. Ver rutas.py.
from rutas import DATOS as RAIZ
AUDIO = {".m4a", ".opus", ".webm", ".mp3", ".mp4"}


def id_de(archivo: Path) -> str | None:
    """El ID de YouTube que el nombre lleva adelante: '[abc123] Titulo.m4a'."""
    m = re.match(r"^\[([^\]]+)\]", archivo.name)
    return m.group(1) if m else None


def sin_id(nombre: str) -> str:
    """El titulo humano, sin el ID de adelante."""
    return re.sub(r"^\[[^\]]+\]\s*", "", nombre)


def archivos_ordenados(nombre_set: str) -> list[Path]:
    """Los audios del set EN EL ORDEN DEL ENGANCHADO."""
    carpeta = RAIZ / "musica" / nombre_set
    if not carpeta.is_dir():
        return []

    todos = sorted(p for p in carpeta.iterdir() if p.suffix.lower() in AUDIO)

    manifiesto = carpeta / "_orden.txt"
    if not manifiesto.exists():
        return todos   # set viejo sin migrar: cae al orden alfabetico

    por_id = {i: p for p in todos if (i := id_de(p))}

    posiciones = {}
    for linea in manifiesto.read_text(encoding="utf-8", errors="replace").splitlines():
        partes = linea.strip().split("|")
        # gana la ultima aparicion: un tema rebajado se vuelve a escribir
        if len(partes) == 2 and partes[0].isdigit():
            posiciones[int(partes[0])] = partes[1]

    ordenados = [por_id[i] for _, i in sorted(posiciones.items()) if i in por_id]
    if not ordenados:
        return todos

    # lo que esta en disco pero no en el manifiesto va al final, para no
    # perderlo en silencio
    sobrantes = [p for p in todos if p not in ordenados]
    return ordenados + sobrantes
