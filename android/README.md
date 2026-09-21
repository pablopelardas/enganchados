# Enganchados — app Android

El celular hace todo: busca, baja, analiza, mezcla y exporta. Sin PC y sin
servidor.

## Abrirlo

1. Android Studio → **Open** → elegí esta carpeta (`android/`).
2. Va a pedir generar el Gradle Wrapper y bajar dependencias. Dale que sí.
3. Conectá el celular con depuración USB → **Run**.

No hay wrapper commiteado a propósito (el `.jar` es binario). Android Studio
lo crea solo; si preferís consola: `gradle wrapper`.

## Cómo se usa

**Temas** → buscás, escuchás el candidato en YouTube antes de elegir, y armás
la lista. Después *Bajar*.

**Tramos** → *Analizar* propone el pedazo de mayor energía sostenida, que casi
siempre es el estribillo y de paso esquiva las intros. Arrastrás los bordes
con el dedo, *Al compás* redondea a compases enteros, *Escuchar* reproduce
solo la selección.

**Armar** → el `.m4a` para la fiesta, o el ZIP con los stems más el proyecto
de Reaper para seguirlo en la PC.

## Cómo está armado

```
data/
  Audio.kt      decodificar y codificar con MediaCodec
  Analisis.kt   BPM, compases y propuesta de tramo
  Mezcla.kt     el render final, en streaming
  Reaper.kt     stems + .rpp + ZIP
  YoutubeRepo.kt   buscar y bajar con yt-dlp
  Modelos.kt    la receta y su persistencia
ui/             las tres pantallas
```

### Dos decisiones que conviene entender

**No se usa ffmpeg para mezclar.** El módulo de ffmpeg que trae
`youtubedl-android` está para que yt-dlp lo use por dentro; no hay garantía de
que exponga ejecución arbitraria. Todo el procesamiento va con `MediaCodec`,
que siempre está.

**La mezcla nunca se junta entera en memoria.** Un enganchado de 25 minutos en
estéreo son más de medio giga de PCM: el sistema mata la app. Se escribe a
disco a medida que avanza y solo se retiene la cola del cruce.

### Dónde difiere de la versión de escritorio

| | Escritorio | Acá |
|---|---|---|
| Análisis | librosa | port propio, sin dependencias |
| Nivelación | `loudnorm` (EBU R128) | RMS a nivel objetivo |
| Mezcla | ffmpeg `acrossfade` | PCM a mano |

La nivelación por RMS es más simple que R128, pero resuelve lo que importa en
una fiesta: que todos los temas suenen parejo.

## Si algo falla

- **"yt-dlp no arrancó"** en la pantalla de inicio → la extracción de binarios
  falló. Revisá que `abiFilters` incluya la arquitectura de tu celular.
- **Las descargas fallan de golpe** → YouTube cambió algo. Menú → *Actualizar
  yt-dlp*, sin reinstalar la app.
- **El análisis tarda** → es esperable: decodifica el tema completo. Un tema de
  4 minutos son unos segundos en un celular moderno.

## Sobre el uso

Descargar de YouTube va en contra de sus Términos de Servicio. Esta app es
para uso personal y no está publicada en ninguna tienda. Lo que esté permitido
más allá de eso depende de tu país y de para qué la uses.
