# Enganchados

Arma enganchados (medleys) a partir de temas de YouTube: los baja, mide el
BPM de cada uno, propone qué tramo usar, y los pega sin huecos.

## Descargar

**[→ Bajar la última versión](https://github.com/pablopelardas/enganchados/releases/latest)**

Para Windows y Mac. Se instala como cualquier programa: no hace falta saber
nada de computación ni instalar nada más. En la página de descarga están los
pasos, incluido qué hacer con el aviso de seguridad que aparece la primera vez.

---

Viene en dos formas que hacen lo mismo:

- **Escritorio** (Windows, macOS): la app de arriba.
- **Android**: una app nativa, en [`android/`](android/README.md).

---

## Para qué sirve

Un enganchado bueno no es pegar temas uno atrás del otro. Cada tramo tiene
que arrancar donde arranca la parte buena, los cortes tienen que caer sobre
el compás, no pueden quedar silencios entre tema y tema, y el volumen tiene
que ser parejo — si no, salta de susurro a bocinazo.

Eso es lo mecánico y lo hace la herramienta. Lo que no puede hacer es elegir
el tramo con criterio: eso lo ponés vos, y para eso está el editor.

---

## Correrlo desde el código

Esto es para quien quiera tocar el código. Para usarla, alcanza con la
[descarga de arriba](#descargar).

### Windows

1. Instalá [Python](https://www.python.org/downloads/) 3.10 o más nuevo.
   **Importante**: durante la instalación, tildá *"Add Python to PATH"*.
2. Descargá o cloná este repo.
3. Doble clic en **`Enganchados.bat`**.

### macOS

1. Instalá [Homebrew](https://brew.sh) si no lo tenés.
2. Descargá o cloná este repo.
3. Doble clic en **`Enganchados.command`**.

Si lo bajaste como ZIP, la primera vez macOS puede decir que no lo puede
abrir porque no viene de un desarrollador identificado. Hacé **clic derecho →
Abrir** y confirmá. Eso pasa una sola vez.

### Linux

1. Instalá Python 3.10+, `ffmpeg`, `yt-dlp` y `deno` con tu gestor de paquetes.
2. Cloná este repo y corré `./Enganchados.command`.

---

La primera vez tarda unos minutos: instala lo que falte y las dependencias de
Python. Las siguientes veces abre al toque en <http://127.0.0.1:8000>.

Si algo falla, corré el instalador a mano (`instalar.ps1` en Windows,
`./instalar.sh` en macOS y Linux) y leé los mensajes.

---

## Cómo se usa

Tres pestañas abajo, igual que en el celular. El menú **≡** de arriba cambia
de enganchado, crea uno nuevo o lo borra.

### Temas

- **+ Agregar** → busca en YouTube y te muestra candidatos con miniatura,
  canal y duración. Marcá **todos los que quieras** de un saque. Antes de
  elegir, escuchalos en YouTube (▶): la búsqueda automática agarra el primer
  resultado, y el primero suele ser un cover, un remix o directamente otra
  banda.
- En cada fila: **↑ ↓** para reordenar, **🔍** para cambiar el video, y **✕**
  para quitarla (con un segundo toque para confirmar). Quitar también borra el
  audio.
- **Bajar** trae solo lo que falta.

Para pegar muchos de una, abajo está **Editar la lista como texto**.

### Tramos

**Analizar pendientes** propone un tramo para cada tema nuevo: busca la parte
de mayor energía sostenida, que casi siempre es el estribillo. Como efecto
lateral, eso esquiva solo las intros habladas y los fade-in largos de los
videos oficiales. Lo que ya ajustaste no se toca.

Después ajustás a oreja:

- Estirá el tramo desde las **manijas**, o arrastralo **desde el medio** para
  moverlo entero.
- Escribí el número exacto en *Desde* / *Hasta* para lo fino.
- **Ajustar a compases enteros** redondea el largo para que el corte caiga donde
  termina una frase musical.
- **Tramo** reproduce solo la selección (también con la barra espaciadora).
  **Tema entero** reproduce desde el principio, para ver dónde está el
  estribillo.
- **‹ ›** mueve el tema en el orden del enganchado.

Todo se guarda solo.

### Armar

- **Armar el enganchado** → el `.m4a` final. Se escucha ahí mismo, y **⏮ ⏭**
  saltan a 3 segundos antes de cada cruce entre temas: para evaluar un
  enganchado no hace falta escucharlo entero, lo que puede salir mal son las
  transiciones.
- **Exportar stems + Reaper** → un ZIP con cada tramo suelto como `.wav` y un
  proyecto de [Reaper](https://www.reaper.fm/) con todo ya ubicado en la línea
  de tiempo, por si querés ponerle un beat encima.

---

## Desde el celular, sin la app

La interfaz es la misma en una pantalla chica. Para abrirla desde el teléfono
en la misma red WiFi, agregale `--lan` a la línea del servidor en
`Enganchados.bat` / `Enganchados.command`. Al arrancar te imprime la dirección.

**Ojo**: en modo `--lan` no hay usuario ni contraseña, así que cualquiera en tu
red puede entrar y borrar cosas. Usalo en tu casa, nunca en una red pública.

---

## Cómo está armado

Tres archivos por enganchado, y la clave es que siempre coincidan:

```
sets/<set>.txt           qué temas, en qué orden
musica/<set>/_orden.txt  qué video quedó bajado para cada línea
recetas/<set>.json       qué tramo de cada tema
```

La **receta** es el contrato. El algoritmo llega al 80% —sin huecos, tramos
sobre compás, volumen parejo—; el 20% que falta es gusto. Por eso es un JSON
editable a mano y el render es un paso aparte: cambiás un número y volvés a
armar, sin volver a analizar nada.

Los archivos son la fuente de verdad, no la interfaz: si el servidor se rompe,
abrís el JSON con cualquier editor y seguís.

### Los scripts

| Archivo | Qué hace |
|---|---|
| `enganchado/servidor.py` | La app web |
| `enganchado/analizar.py` | Mide BPM y propone tramos |
| `enganchado/renderizar.py` | Mezcla, o exporta stems + Reaper |
| `enganchado/orden.py` | De dónde sale el orden del enganchado |
| `bajar.ps1` | Descarga por consola (solo Windows) |

Todos andan por línea de comandos:

```
# Windows
.venv\Scripts\python.exe enganchado\analizar.py mi-set --nuevos

# macOS / Linux
.venv/bin/python enganchado/analizar.py mi-set --nuevos
```

### Dos detalles de diseño

**Cada fila encuentra su audio por el ID del video, nunca por posición.** Los
audios se guardan como `[ID_DE_YOUTUBE] Título.m4a`: el nombre dice **qué**
video es, nunca **dónde** va. Cuando la relación era por posición, quitar el
tema 3 hacía que el 4 mostrara el audio del 5.

**Quitar, reemplazar y mover los hace el servidor, que reescribe los tres
archivos juntos.** Hay un único orden: el que ves en Temas es el de Tramos y
el de la mezcla.

---

## Calidad del audio

YouTube **no tiene audio sin pérdida**: su máximo ronda los 130 kbps (AAC u
Opus). Por eso se baja el stream nativo y se copia tal cual, sin re-encodear a
mp3. Convertirlo a "320 kbps" no agrega información, solo pérdida y peso.

---

## Sobre el uso

Esta herramienta corre en tu máquina y usa [yt-dlp](https://github.com/yt-dlp/yt-dlp).
Descargar contenido de YouTube va en contra de sus Términos de Servicio, y lo
que esté permitido más allá de eso depende de tu país y de para qué lo uses.
Es tu responsabilidad.

No está pensada ni preparada para correr como servicio público, y por la misma
razón no está publicada en ninguna tienda de apps.

---

## Publicar una versión nueva

Las apps de Windows y Mac las arma GitHub solo. Alcanza con marcar la versión:

```
git tag v0.2.0
git push origin v0.2.0
```

Unos 15 minutos después aparece en [Releases](https://github.com/pablopelardas/enganchados/releases)
con los tres archivos. El proceso está en `.github/workflows/release.yml`, y
para armarla a mano en tu máquina: `python empaquetar/construir.py`.

---

## Licencia

MIT. Hacé lo que quieras con el código.
