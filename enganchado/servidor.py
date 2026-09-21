"""
Servidor local del manager de enganchados.

Es una LENTE sobre los archivos, no un dueño de los datos: lee y escribe
sets/*.txt y recetas/*.json, y para las tareas pesadas invoca los mismos
scripts que corres a mano. Si este server se cae, seguis trabajando por
consola sin perder nada.

Uso:
    python enganchado/servidor.py          -> http://127.0.0.1:8000
    python enganchado/servidor.py --lan    -> tambien desde el celu
"""
import asyncio
import json
import os
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from orden import AUDIO, archivos_ordenados, id_de, sin_id

import rutas
from rutas import CONFIG_YTDLP, SIN_VENTANA, herramienta

# La raiz de los DATOS (sets, musica, recetas, salida): el repo al correr
# desde el codigo, o Documentos/Enganchados en la app. Ver rutas.py.
RAIZ = rutas.DATOS
WEB = rutas.CODIGO / "web"

# El Python del entorno virtual: Windows lo pone en Scripts\, macOS y Linux
# en bin/. Si no hay ninguno, el mismo que esta corriendo este server.
PYTHON = next(
    (str(p) for p in (RAIZ / ".venv" / "Scripts" / "python.exe",
                      RAIZ / ".venv" / "bin" / "python") if p.exists()),
    sys.executable,
)


def lanzar(script: str) -> list[str]:
    """Como correr analizar o renderizar en un proceso aparte.

    Desde el repo, con el Python del venv. En la app empaquetada no hay un
    Python suelto: el propio ejecutable de la app atiende "--tarea"
    (ver app.py). Seguir en un proceso aparte mantiene la misma salida en
    vivo por SSE, y si una tarea revienta no se lleva puesta la ventana.
    """
    if rutas.CONGELADO:
        return [sys.executable, "--tarea", script]
    return [PYTHON, str(rutas.CODIGO / f"{script}.py")]

# Los nombres de set vienen del cliente y se usan para armar rutas.
# Sin esto, un nombre como "../../etc" se escapa del proyecto.
NOMBRE_OK = re.compile(r"^[A-Za-z0-9_-]+$")

# El ID de un video de YouTube dentro de un link, en cualquiera de sus formas.
RE_ID_URL = re.compile(r"(?:[?&]v=|youtu\.be/|/shorts/)([A-Za-z0-9_-]{11})")
RE_BUSQUEDA = re.compile(r'^ytsearch\d*:\s*"?(.*?)"?\s*$')

from contextlib import asynccontextmanager


@asynccontextmanager
async def al_arrancar(_app):
    # en la app recien instalada, Documentos/Enganchados todavia no existe
    for sub in ("sets", "musica", "recetas", "salida"):
        (RAIZ / sub).mkdir(parents=True, exist_ok=True)
    alinear_todos_con_receta()
    yield


app = FastAPI(title="Manager de enganchados", lifespan=al_arrancar)


def validar(nombre: str) -> str:
    if not NOMBRE_OK.match(nombre):
        raise HTTPException(400, "Nombre de set invalido: solo letras, numeros, - y _")
    return nombre


def rel(p: Path) -> str:
    return str(p.relative_to(RAIZ)).replace("\\", "/")


# ============================================================ el modelo
#
# Un enganchado son tres archivos que tienen que coincidir:
#
#   sets/<set>.txt         que temas, en que orden (una linea por tema)
#   musica/<set>/_orden.txt   posicion -> ID del video bajado para esa linea
#   recetas/<set>.json     que tramo de cada tema, para los ya analizados
#
# La version anterior relacionaba fila y audio POR POSICION, y alcanzaba
# con quitar el tema 3 para que el 4 mostrara el audio del 5. Ahora cada
# fila se relaciona con su audio por ID de video, y toda operacion que
# cambie la lista reescribe los tres archivos juntos: hay un solo orden.

def ruta_lista(n: str) -> Path: return RAIZ / "sets" / f"{n}.txt"
def ruta_titulos(n: str) -> Path: return RAIZ / "sets" / f"{n}.titulos.json"
def ruta_receta(n: str) -> Path: return RAIZ / "recetas" / f"{n}.json"
def carpeta_musica(n: str) -> Path: return RAIZ / "musica" / n
def ruta_orden(n: str) -> Path: return carpeta_musica(n) / "_orden.txt"


def parsear_lista(texto: str) -> tuple[list[str], list[str]]:
    """(comentarios, lineas de temas). Los comentarios se conservan todos,
    arriba: pierden su posicion pero guardar nunca borra lo que escribiste."""
    comentarios, lineas = [], []
    for l in texto.splitlines():
        t = l.strip()
        if not t:
            continue
        (comentarios if t.startswith("#") else lineas).append(t)
    return comentarios, lineas


def leer_lista(n: str) -> tuple[list[str], list[str]]:
    p = ruta_lista(n)
    return parsear_lista(p.read_text(encoding="utf-8")) if p.exists() else ([], [])


def leer_orden(n: str) -> dict[int, str]:
    """Posicion -> ID. Gana la ultima aparicion de cada posicion."""
    p = ruta_orden(n)
    orden: dict[int, str] = {}
    if p.exists():
        for l in p.read_text(encoding="utf-8", errors="replace").splitlines():
            partes = l.strip().split("|")
            if len(partes) == 2 and partes[0].isdigit():
                orden[int(partes[0])] = partes[1]
    return orden


def leer_titulos(n: str) -> dict[str, str]:
    p = ruta_titulos(n)
    return json.loads(p.read_text(encoding="utf-8")) if p.exists() else {}


def leer_receta(n: str) -> dict | None:
    p = ruta_receta(n)
    return json.loads(p.read_text(encoding="utf-8")) if p.exists() else None


def escribir_receta(n: str, receta: dict) -> None:
    p = ruta_receta(n)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(receta, indent=2, ensure_ascii=False), encoding="utf-8")


def id_de_url(linea: str) -> str | None:
    m = RE_ID_URL.search(linea)
    return m.group(1) if m else None


def audios_por_id(n: str) -> dict[str, Path]:
    c = carpeta_musica(n)
    if not c.is_dir():
        return {}
    return {i: p for p in c.iterdir()
            if p.suffix.lower() in AUDIO and (i := id_de(p))}


def ids_de_filas(n: str) -> tuple[list[str], list[str], list[str | None]]:
    """(comentarios, lineas, id de cada linea). Un link trae su ID adentro;
    una busqueda lo conoce recien cuando se bajo y quedo en _orden."""
    comentarios, lineas = leer_lista(n)
    orden = leer_orden(n)
    ids = [id_de_url(l) or orden.get(pos) for pos, l in enumerate(lineas, 1)]
    return comentarios, lineas, ids


def filas_de(n: str) -> list[dict]:
    _, lineas, ids = ids_de_filas(n)
    audios = audios_por_id(n)
    titulos = leer_titulos(n)
    receta = leer_receta(n) or {}
    analizados = {t["id"]: t for t in receta.get("temas", []) if t.get("id")}

    filas = []
    for pos, (linea, vid) in enumerate(zip(lineas, ids), 1):
        audio = audios.get(vid) if vid else None
        tema = analizados.get(vid) if vid else None
        busqueda = RE_BUSQUEDA.match(linea)

        if audio:
            titulo = sin_id(audio.stem)
        elif vid and vid in titulos:
            titulo = titulos[vid]
        else:
            titulo = busqueda.group(1) if busqueda else linea

        filas.append({
            "pos": pos,
            "texto": linea,
            "tipo": "busqueda" if busqueda else "link",
            "id": vid,
            "titulo": titulo,
            "bajado": audio is not None,
            "mb": round(audio.stat().st_size / 1024 / 1024, 1) if audio else 0,
            "analizado": tema is not None,
            "bpm": tema.get("bpm") if tema else None,
        })
    return filas


def aplicar(n: str, comentarios: list[str], lineas: list[str], ids: list[str | None]) -> None:
    """Escribe lista, orden y receta JUNTOS. Es el unico camino para
    cambiar un enganchado, asi que los tres archivos no pueden desalinearse."""
    p = ruta_lista(n)
    p.parent.mkdir(parents=True, exist_ok=True)
    cuerpo = comentarios + ([""] if comentarios else []) + lineas
    p.write_text("\n".join(cuerpo) + "\n", encoding="utf-8")

    carpeta_musica(n).mkdir(parents=True, exist_ok=True)
    ruta_orden(n).write_text(
        "".join(f"{pos:02d}|{vid}\n" for pos, vid in enumerate(ids, 1) if vid),
        encoding="utf-8",
    )

    # La receta sigue el orden de la lista y pierde los temas que ya no estan.
    receta = leer_receta(n)
    if receta is not None:
        por_id = {t["id"]: t for t in receta.get("temas", []) if t.get("id")}
        audios = audios_por_id(n)
        receta["temas"] = [por_id[v] for v in ids if v and v in por_id and v in audios]
        escribir_receta(n, receta)


def alinear_con_receta(n: str) -> bool:
    """Migra un enganchado del modelo viejo al de un solo orden.

    En la version anterior la lista y la receta tenian ordenes independientes:
    se podia reordenar en Tramos sin tocar la lista. El modelo nuevo toma el
    orden de la lista, asi que sin esto el orden armado en Tramos se perdia
    sin aviso la primera vez que se tocara el enganchado.

    Vale el orden de la receta, que es el enganchado que se armo; la lista
    era solo el orden de descarga. Los temas sin analizar quedan donde estan.
    Idempotente: si ya coinciden, no escribe nada.
    """
    receta = leer_receta(n)
    if not receta:
        return False
    orden_receta = [t["id"] for t in receta.get("temas", []) if t.get("id")]
    comentarios, lineas, ids = ids_de_filas(n)

    lugares = [i for i, v in enumerate(ids) if v in orden_receta]
    actual = [ids[i] for i in lugares]
    deseado = [v for v in orden_receta if v in actual]
    if actual == deseado:
        return False

    linea_de = {ids[i]: lineas[i] for i in lugares}
    for lugar, v in zip(lugares, deseado):
        lineas[lugar] = linea_de[v]
        ids[lugar] = v
    aplicar(n, comentarios, lineas, ids)
    return True


def alinear_todos_con_receta() -> None:
    carpeta = RAIZ / "sets"
    if not carpeta.is_dir():
        return
    for p in sorted(carpeta.glob("*.txt")):
        if NOMBRE_OK.match(p.stem) and alinear_con_receta(p.stem):
            print(f"  {p.stem}: lista alineada al orden de la receta")


def borrar_audio(n: str, vid: str | None, ids_restantes: list[str | None]) -> None:
    """Borra el audio de un video, salvo que otra fila lo siga usando."""
    if vid and vid not in ids_restantes:
        audio = audios_por_id(n).get(vid)
        if audio:
            audio.unlink(missing_ok=True)


def estado_de(n: str) -> dict:
    filas = filas_de(n)
    salida = RAIZ / "salida" / f"{n}.m4a"
    return {
        "nombre": n,
        "en_lista": len(filas),
        "descargados": sum(f["bajado"] for f in filas),
        "analizados": sum(f["analizado"] for f in filas),
        "tiene_receta": ruta_receta(n).exists(),
        "tiene_salida": salida.exists(),
        "salida_mb": round(salida.stat().st_size / 1024 / 1024, 1) if salida.exists() else 0,
        "tiene_stems": (RAIZ / "salida" / f"{n}-stems").is_dir(),
    }


# ============================================================== sets

@app.get("/api/sets")
def listar_sets():
    carpeta = RAIZ / "sets"
    carpeta.mkdir(parents=True, exist_ok=True)
    return [estado_de(p.stem) for p in sorted(carpeta.glob("*.txt"))]


class NuevoSet(BaseModel):
    nombre: str


@app.post("/api/sets")
def crear_set(body: NuevoSet):
    nombre = validar(body.nombre.strip())
    if ruta_lista(nombre).exists():
        raise HTTPException(409, f"El set '{nombre}' ya existe")
    aplicar(nombre, [f"# Enganchado {nombre}"], [], [])
    return estado_de(nombre)


@app.delete("/api/sets/{nombre}")
def borrar_set(nombre: str, todo: bool = True):
    """Borra el enganchado ENTERO: lista, receta, audios y exportes.
    Es irreversible: el front pide confirmacion con doble toque."""
    validar(nombre)
    borrados = []

    def sacar(p: Path):
        if p.is_dir():
            shutil.rmtree(p)
            borrados.append(rel(p) + "/")
        elif p.exists():
            p.unlink()
            borrados.append(rel(p))

    for p in (ruta_lista(nombre), ruta_titulos(nombre), ruta_receta(nombre)):
        sacar(p)
    if todo:
        sacar(carpeta_musica(nombre))
        for sufijo in (".m4a", "-preview.m4a", "-stems.zip", "-stems"):
            sacar(RAIZ / "salida" / f"{nombre}{sufijo}")

    if not borrados:
        raise HTTPException(404, f"No habia nada de '{nombre}'")
    return {"borrados": borrados}


# ============================================================ buscar

@app.get("/api/buscar")
async def buscar(q: str, desde: int = 1, hasta: int = 20):
    """Candidatos de YouTube SIN bajar nada, para que elijas vos.

    ytsearch1 agarra siempre el primero, y el primero no suele ser el
    bueno: covers, remixes, clases de gimnasia u otra banda.

    Paginacion con -I: yt-dlp no tiene offset real, asi que la pagina 3
    recorre por dentro las anteriores y cada pagina tarda un poco mas.
    """
    if not q.strip():
        raise HTTPException(400, "Falta que buscar")
    desde = max(1, desde)
    hasta = max(desde, min(hasta, desde + 49))

    proc = await asyncio.create_subprocess_exec(
        herramienta("yt-dlp"), f"ytsearch{hasta}:{q}", "-I", f"{desde}:{hasta}",
        "--flat-playlist", "--dump-json", "--no-warnings", "--quiet",
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.DEVNULL,
        **SIN_VENTANA,
    )
    crudo, _ = await proc.communicate()

    resultados = []
    for linea in crudo.decode("utf-8", "replace").splitlines():
        try:
            d = json.loads(linea)
        except json.JSONDecodeError:
            continue
        ident = d.get("id")
        if not ident:
            continue
        resultados.append({
            "id": ident,
            "titulo": d.get("title") or "(sin titulo)",
            "canal": d.get("channel") or d.get("uploader") or "?",
            "duracion": int(d.get("duration") or 0),
            "vistas": d.get("view_count") or 0,
            "miniatura": f"https://i.ytimg.com/vi/{ident}/mqdefault.jpg",
            "url": f"https://www.youtube.com/watch?v={ident}",
        })
    return resultados


# ============================================================= filas

@app.get("/api/sets/{nombre}/filas")
def listar_filas(nombre: str):
    validar(nombre)
    return filas_de(nombre)


class Elegido(BaseModel):
    url: str
    titulo: str = ""


class Agregar(BaseModel):
    temas: list[Elegido]


def recordar_titulos(n: str, elegidos: list[Elegido]) -> None:
    """Guarda el titulo que venia de la busqueda: asi una fila recien
    agregada muestra el nombre del tema, no un link pelado."""
    titulos = leer_titulos(n)
    for e in elegidos:
        vid = id_de_url(e.url)
        if vid and e.titulo:
            titulos[vid] = e.titulo
    ruta_titulos(n).write_text(json.dumps(titulos, indent=2, ensure_ascii=False),
                               encoding="utf-8")


@app.post("/api/sets/{nombre}/filas")
def agregar_filas(nombre: str, body: Agregar):
    """Agrega varios de un saque, al final. No duplica los que ya estan."""
    validar(nombre)
    comentarios, lineas, ids = ids_de_filas(nombre)
    agregados, repetidos = 0, 0
    for e in body.temas:
        vid = id_de_url(e.url)
        if vid and vid in ids:
            repetidos += 1
            continue
        lineas.append(e.url.strip())
        ids.append(vid)
        agregados += 1
    recordar_titulos(nombre, body.temas)
    aplicar(nombre, comentarios, lineas, ids)
    return {"agregados": agregados, "repetidos": repetidos}


@app.put("/api/sets/{nombre}/filas/{pos}")
def reemplazar_fila(nombre: str, pos: int, body: Elegido):
    """Cambia el video de UNA fila. El audio viejo se borra: si quedara, la
    fila no sabria cual de los dos le corresponde."""
    validar(nombre)
    comentarios, lineas, ids = ids_de_filas(nombre)
    if not 1 <= pos <= len(lineas):
        raise HTTPException(404, "No existe esa fila")

    viejo = ids[pos - 1]
    lineas[pos - 1] = body.url.strip()
    ids[pos - 1] = id_de_url(body.url)
    borrar_audio(nombre, viejo, ids)
    recordar_titulos(nombre, [body])
    aplicar(nombre, comentarios, lineas, ids)
    return {"ok": True}


@app.delete("/api/sets/{nombre}/filas/{pos}")
def quitar_fila(nombre: str, pos: int):
    """Quitar saca el tema Y su audio, igual que en el celular: dejar el
    archivo colgado no le sirve a nadie."""
    validar(nombre)
    comentarios, lineas, ids = ids_de_filas(nombre)
    if not 1 <= pos <= len(lineas):
        raise HTTPException(404, "No existe esa fila")

    del lineas[pos - 1]
    viejo = ids.pop(pos - 1)
    borrar_audio(nombre, viejo, ids)
    aplicar(nombre, comentarios, lineas, ids)
    return {"ok": True}


class Mover(BaseModel):
    desde: int        # posicion 1-based
    direccion: int    # -1 sube, +1 baja


@app.post("/api/sets/{nombre}/mover")
def mover_fila(nombre: str, body: Mover):
    """Un solo orden para todo: mover en Temas o en Tramos cambia la lista,
    el registro de audios y la receta a la vez."""
    validar(nombre)
    comentarios, lineas, ids = ids_de_filas(nombre)
    a = body.desde - 1
    b = a + body.direccion
    if not (0 <= a < len(lineas) and 0 <= b < len(lineas)):
        raise HTTPException(400, "Movimiento fuera de rango")
    lineas[a], lineas[b] = lineas[b], lineas[a]
    ids[a], ids[b] = ids[b], ids[a]
    aplicar(nombre, comentarios, lineas, ids)
    return {"ok": True}


# ========================================================= texto crudo

class Contenido(BaseModel):
    contenido: str


@app.get("/api/sets/{nombre}/lista")
def lista_cruda(nombre: str):
    validar(nombre)
    p = ruta_lista(nombre)
    if not p.exists():
        raise HTTPException(404, "No existe la lista")
    return {"contenido": p.read_text(encoding="utf-8")}


@app.put("/api/sets/{nombre}/lista")
def guardar_lista_cruda(nombre: str, body: Contenido, confirmar: bool = False):
    """Editar como texto, para pegar muchos de una.

    Frena los recortes masivos: una lista puede perderse por un clic
    distraido y el error se nota recien al renderizar. Y conserva que audio
    va con cada linea aunque las reordenes a mano, buscandolas por su texto.
    """
    validar(nombre)
    _, lineas_nuevas = parsear_lista(body.contenido)
    _, lineas_viejas, ids_viejos = ids_de_filas(nombre)

    if not confirmar and len(lineas_viejas) >= 5 and len(lineas_nuevas) < len(lineas_viejas) * 0.7:
        raise HTTPException(
            409,
            f"Esa lista pasa de {len(lineas_viejas)} a {len(lineas_nuevas)} temas. "
            "Si es a proposito, confirmalo; si no, revisa antes de guardar.")

    por_texto = {l: v for l, v in zip(lineas_viejas, ids_viejos) if v}
    comentarios, lineas = parsear_lista(body.contenido)
    ids = [id_de_url(l) or por_texto.get(l) for l in lineas]
    aplicar(nombre, comentarios, lineas, ids)
    return estado_de(nombre)


# ============================================================ receta

@app.get("/api/sets/{nombre}/receta")
def obtener_receta(nombre: str):
    validar(nombre)
    receta = leer_receta(nombre)
    if receta is None:
        raise HTTPException(404, "Todavia no hay receta: analiza los temas")
    return receta


@app.put("/api/sets/{nombre}/receta")
async def guardar_receta(nombre: str, receta: dict):
    validar(nombre)
    if "temas" not in receta:
        raise HTTPException(400, "La receta no tiene 'temas'")
    escribir_receta(nombre, receta)
    return {"guardado": True, "temas": len(receta["temas"])}


# ============================================================= audio

@app.get("/api/sets/{nombre}/audio/{indice}")
def audio_fuente(nombre: str, indice: int):
    """El tema original completo. El indice es la POSICION EN LA RECETA:
    se resuelve por la receta y nunca por el orden del disco."""
    validar(nombre)
    temas = (leer_receta(nombre) or {}).get("temas", [])
    if not 0 <= indice < len(temas):
        raise HTTPException(404, "No existe ese tema")
    archivo = RAIZ / temas[indice]["archivo"]
    if not archivo.exists():
        raise HTTPException(404, "El audio de ese tema ya no esta")
    return FileResponse(archivo, media_type="audio/mp4")


@app.get("/api/sets/{nombre}/salida")
def audio_salida(nombre: str, descargar: bool = False):
    validar(nombre)
    archivo = RAIZ / "salida" / f"{nombre}.m4a"
    if not archivo.exists():
        raise HTTPException(404, "Todavia no se armo el enganchado")
    if descargar:
        return FileResponse(archivo, media_type="audio/mp4", filename=f"{nombre}.m4a")
    return FileResponse(archivo, media_type="audio/mp4")


def armar_zip(nombre: str) -> Path:
    """Stems + proyecto de Reaper en un ZIP, para llevar a la PC.

    Sin comprimir a proposito: el WAV casi no comprime (medido, 6%) y
    comprimirlo tardaba. Se rearma solo si los stems son mas nuevos.
    """
    stems = RAIZ / "salida" / f"{nombre}-stems"
    if not stems.is_dir():
        raise HTTPException(404, "Todavia no se exportaron los stems")
    zp = RAIZ / "salida" / f"{nombre}-stems.zip"
    archivos = sorted(p for p in stems.iterdir() if p.is_file())
    if not zp.exists() or any(p.stat().st_mtime > zp.stat().st_mtime for p in archivos):
        with zipfile.ZipFile(zp, "w", zipfile.ZIP_STORED) as z:
            for p in archivos:
                z.write(p, p.name)
    return zp


@app.get("/api/sets/{nombre}/zip")
def descargar_zip(nombre: str):
    validar(nombre)
    zp = armar_zip(nombre)
    return FileResponse(zp, media_type="application/zip", filename=f"{nombre}-stems.zip")


# ======================================================== app de escritorio

@app.get("/api/info")
def info():
    """Si esto corre como app instalada o como web desde el repo.

    En la app, "descargar" no tiene sentido: los archivos ya estan en tu
    compu. Ahi el front muestra "Mostrar en la carpeta" en su lugar.
    """
    return {"modo": "app" if rutas.CONGELADO else "web", "datos": str(RAIZ)}


def mostrar_en_carpeta(p: Path) -> None:
    """Abre el explorador de archivos con el archivo seleccionado."""
    if os.name == "nt":
        if p.is_file():
            # explorer espera /select pegado a la ruta; como lista de
            # argumentos subprocess la comilla mal y abre "Documentos"
            subprocess.Popen(f'explorer /select,"{p}"')
        else:
            os.startfile(p)  # noqa: S606 — carpeta propia de la app
    elif sys.platform == "darwin":
        subprocess.Popen(["open", "-R", str(p)] if p.is_file() else ["open", str(p)])
    else:
        subprocess.Popen(["xdg-open", str(p.parent if p.is_file() else p)])


@app.post("/api/mostrar")
def mostrar(request: Request, set: str | None = None, que: str = "carpeta"):
    # Abre ventanas en ESTA maquina: solo si el pedido viene de ella misma.
    # En modo --lan, alguien de la red no puede abrirte exploradores.
    if request.client is None or request.client.host not in ("127.0.0.1", "::1"):
        raise HTTPException(403, "Solo desde esta computadora")

    if que == "carpeta":
        objetivo = RAIZ
    else:
        validar(set or "")
        if que == "m4a":
            objetivo = RAIZ / "salida" / f"{set}.m4a"
        elif que == "zip":
            objetivo = armar_zip(set)
        else:
            raise HTTPException(400, "Que mostrar: carpeta, m4a o zip")
        if not objetivo.exists():
            raise HTTPException(404, "Todavia no existe")

    mostrar_en_carpeta(objetivo)
    return {"ok": True}


# Ganchos que conecta app.py cuando hay ventana propia. Desde el repo (modo
# web) quedan en None y cambiar la carpeta no se ofrece.
elegir_carpeta = None      # () -> str | None: abre el selector del sistema
pedir_reinicio = None      # () -> None: cierra la ventana y relanza la app

SUBCARPETAS = ("sets", "musica", "recetas", "salida")


def solo_local(request: Request) -> None:
    if request.client is None or request.client.host not in ("127.0.0.1", "::1"):
        raise HTTPException(403, "Solo desde esta computadora")


@app.get("/api/carpeta-datos")
def ver_carpeta_datos():
    return {"datos": str(RAIZ), "puede_cambiar": elegir_carpeta is not None}


def tiene_enganchados(carpeta: Path) -> bool:
    return any((carpeta / "sets").glob("*.txt")) if (carpeta / "sets").is_dir() else False


@app.post("/api/carpeta-datos")
async def cambiar_carpeta_datos(request: Request):
    """Cambia donde se guardan los enganchados.

    Si la carpeta nueva esta vacia, se MUDAN los enganchados: cambiar de
    carpeta no puede parecer que los borro. Si ya tiene enganchados (un
    backup, otra compu), se usa tal cual, sin mezclar.
    Se aplica al reiniciar la app: todas las rutas se fijan al arrancar.
    """
    solo_local(request)
    if elegir_carpeta is None:
        raise HTTPException(400, "Solo en la app instalada")

    from fastapi.concurrency import run_in_threadpool
    elegida = await run_in_threadpool(elegir_carpeta)
    if not elegida:
        return {"cambiado": False}

    nueva = Path(elegida)
    # Si elegis una carpeta con otras cosas adentro (Musica, Escritorio...),
    # no se desparraman sets/ y musica/ ahi: se crea Enganchados adentro.
    if nueva.name != "Enganchados" and any(nueva.iterdir()) and not tiene_enganchados(nueva):
        nueva = nueva / "Enganchados"

    actual = RAIZ.resolve()
    destino = nueva.resolve()
    if destino == actual:
        return {"cambiado": False}
    if actual in destino.parents or destino in actual.parents:
        raise HTTPException(400, "Elegí una carpeta que no esté adentro de la actual (ni al revés)")

    movido = False
    if not tiene_enganchados(destino):
        destino.mkdir(parents=True, exist_ok=True)

        def mudar():
            for sub in SUBCARPETAS:
                if (actual / sub).exists() and not (destino / sub).exists():
                    shutil.move(str(actual / sub), str(destino / sub))

        await run_in_threadpool(mudar)
        movido = True

    rutas.guardar_datos(destino)
    return {"cambiado": True, "datos": str(destino), "movido": movido}


@app.post("/api/reiniciar")
def reiniciar(request: Request):
    solo_local(request)
    if pedir_reinicio is None:
        raise HTTPException(400, "Solo en la app instalada")
    pedir_reinicio()
    return {"ok": True}


# ============================================================ tareas

def sse(**datos) -> str:
    return f"data: {json.dumps(datos, ensure_ascii=False)}\n\n"


async def transmitir(cmd: list[str]):
    """Corre el comando y manda cada linea al navegador en vivo (SSE)."""
    proc = await asyncio.create_subprocess_exec(
        *cmd,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.STDOUT,
        cwd=str(RAIZ),
        # sin buffer: si no, la app empaquetada manda la salida toda junta al
        # final y el avance en pantalla queda quieto hasta que termina
        env={**os.environ, "PYTHONUNBUFFERED": "1"},
        **SIN_VENTANA,
    )
    assert proc.stdout is not None
    async for cruda in proc.stdout:
        linea = cruda.decode("utf-8", "replace").rstrip()
        if linea:
            yield sse(linea=linea)
    await proc.wait()
    yield sse(fin=True, codigo=proc.returncode)


async def bajar_faltantes(nombre: str):
    """Baja SOLO las filas sin audio, igual que en el celular.

    Se hace aca y no con bajar.ps1 porque aca se sabe a que fila corresponde
    cada descarga en el momento en que termina, y se registra en _orden.
    El script de consola sigue andando para quien lo use a mano.
    """
    filas = [f for f in filas_de(nombre) if not f["bajado"]]
    if not filas:
        yield sse(linea="Ya estan todos bajados")
        yield sse(fin=True, codigo=0)
        return

    carpeta = carpeta_musica(nombre)
    carpeta.mkdir(parents=True, exist_ok=True)
    fallidos = 0

    for k, f in enumerate(filas, 1):
        yield sse(linea=f"[{k:02d}/{len(filas)}] {f['titulo'][:70]}")
        proc = await asyncio.create_subprocess_exec(
            herramienta("yt-dlp"), f["texto"],
            "--config-locations", str(CONFIG_YTDLP),
            "--paths", str(carpeta),
            "-o", "[%(id)s] %(title)s.%(ext)s",
            # el ID de lo que efectivamente bajo: para una busqueda es la
            # unica forma de saber que video le toco a esta fila
            "--print", "after_move:ID=%(id)s",
            "--no-simulate", "--no-warnings",
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.STDOUT,
            cwd=str(RAIZ),
            **SIN_VENTANA,
        )
        assert proc.stdout is not None
        vid = None
        async for cruda in proc.stdout:
            linea = cruda.decode("utf-8", "replace").strip()
            if linea.startswith("ID="):
                vid = linea[3:]
            elif "ERROR" in linea:
                yield sse(linea="    " + linea)
        await proc.wait()

        vid = vid or f["id"]
        if vid and vid in audios_por_id(nombre):
            # se registra la fila por posicion ACTUAL de su texto: si alguien
            # toco la lista mientras bajaba, igual cae donde corresponde
            comentarios, lineas, ids = ids_de_filas(nombre)
            if f["texto"] in lineas:
                ids[lineas.index(f["texto"])] = vid
                aplicar(nombre, comentarios, lineas, ids)
        else:
            fallidos += 1
            yield sse(linea="    no se pudo bajar (¿dura mas de 15 minutos o ya no existe?)")

    yield sse(linea=f"\n{len(filas) - fallidos} bajados" +
                    (f", {fallidos} fallaron" if fallidos else ""))
    yield sse(fin=True, codigo=1 if fallidos else 0)


def comando_de(accion: str, nombre: str, opciones: dict) -> list[str]:
    if accion == "analizar":
        cmd = [*lanzar("analizar"), nombre]
        if opciones.get("duracion"):
            cmd += ["--duracion", str(opciones["duracion"])]
        modo = opciones.get("modo", "nuevos")
        if modo == "solo" and opciones.get("solo"):
            cmd += ["--solo", *[str(int(n)) for n in opciones["solo"]]]
        elif modo == "nuevos":
            cmd.append("--nuevos")   # respeta lo que ya ajustaste
        return cmd                   # "todos": re-analiza y pisa los ajustes
    if accion == "renderizar":
        cmd = [*lanzar("renderizar"), nombre]
        if opciones.get("stems"):
            cmd.append("--stems")
        if opciones.get("crossfade") is not None:
            cmd += ["--crossfade", str(opciones["crossfade"])]
        return cmd
    raise HTTPException(400, f"Accion desconocida: {accion}")


def respuesta_sse(generador):
    return StreamingResponse(
        generador,
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@app.post("/api/actualizar-ytdlp")
async def actualizar_ytdlp():
    """YouTube cambia seguido y un yt-dlp viejo deja de poder bajar."""
    return respuesta_sse(transmitir([herramienta("yt-dlp"), "-U"]))


# Tiene que ir ULTIMA entre las POST de /api/sets/{nombre}/...: atrapa
# cualquier accion, y si estuviera antes se tragaria /filas y /mover.
@app.post("/api/sets/{nombre}/{accion}")
async def correr_tarea(nombre: str, accion: str, opciones: dict | None = None):
    validar(nombre)
    if accion == "bajar":
        return respuesta_sse(bajar_faltantes(nombre))
    return respuesta_sse(transmitir(comando_de(accion, nombre, opciones or {})))


# ========================================================== estaticos

# Sin mkdir: en la app empaquetada esta carpeta es de solo lectura.
app.mount("/", StaticFiles(directory=str(WEB), html=True), name="web")


def ip_local() -> str:
    """La IP de esta maquina en la red de casa, para abrirlo del celular."""
    import socket
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))   # no manda nada, solo elige la interfaz
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


if __name__ == "__main__":
    import argparse
    import uvicorn

    ap = argparse.ArgumentParser(description="Manager de enganchados")
    ap.add_argument("--lan", action="store_true",
                    help="tambien accesible desde el celular en la misma red")
    ap.add_argument("--puerto", type=int, default=8000)
    args = ap.parse_args()

    host = "0.0.0.0" if args.lan else "127.0.0.1"

    print(f"\n  Manager de enganchados")
    print(f"  Esta maquina:  http://127.0.0.1:{args.puerto}")
    if args.lan:
        print(f"  Desde el celu: http://{ip_local()}:{args.puerto}")
        print("\n  OJO: en modo --lan cualquiera en tu red puede entrar y")
        print("  borrar sets. No tiene usuario ni clave. Usalo en tu casa,")
        print("  nunca en una red publica ni abierto a internet.")
    print()

    # access log prendido a proposito: sin el no hay forma de saber quien
    # escribio que cuando algo se pisa
    uvicorn.run(app, host=host, port=args.puerto, log_level="info", access_log=True)
