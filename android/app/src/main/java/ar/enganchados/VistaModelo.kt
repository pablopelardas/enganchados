package ar.enganchados

import android.app.Application
import android.media.MediaPlayer
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ar.enganchados.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Resultados por pagina de busqueda. Cada pagina extra tarda un poco mas. */
private const val POR_PAGINA = 20

class VistaModelo(app: Application) : AndroidViewModel(app) {

    private val almacen = Almacen(app.filesDir)

    var enganchados by mutableStateOf(listOf<String>()); private set

    // neverEqualPolicy a proposito: la receta se muta en el lugar, asi que
    // con la politica por defecto (equals) el valor "nuevo" siempre es igual
    // al viejo y Compose no redibuja nada. Cada asignacion = redibujar.
    var actual by mutableStateOf<Enganchado?>(null, neverEqualPolicy()); private set

    val resultados = mutableStateListOf<Candidato>()
    var buscando by mutableStateOf(false); private set
    var consulta by mutableStateOf("")

    // Paginacion: la consulta queda fija mientras se scrollea. Si el usuario
    // edita el campo, "cargar mas" sigue trayendo lo de la busqueda original.
    private var consultaVigente = ""
    private var pagina = 0
    var hayMas by mutableStateOf(false); private set
    var cargandoMas by mutableStateOf(false); private set

    /** Seleccion multiple: para cargar varios temas de una banda de un saque. */
    val elegidos = mutableStateListOf<Candidato>()

    var trabajando by mutableStateOf<String?>(null); private set
    var progreso by mutableStateOf(0f); private set
    var aviso by mutableStateOf<String?>(null)

    var seleccionado by mutableStateOf(0)
    var duracionPropuesta by mutableStateOf(60.0)

    // -------------------------------------------------------- enganchados

    init {
        almacen.limpiarTemporales()
        refrescar()
        asegurarYtDlp()
    }

    /**
     * El yt-dlp que viene empaquetado en la libreria envejece: YouTube cambia
     * seguido y uno de hace meses ya no sabe extraer. Se actualiza una vez por
     * sesion, en segundo plano, apenas abre la app.
     */
    private var actualizacion: kotlinx.coroutines.Deferred<String>? = null

    private fun asegurarYtDlp() {
        if (actualizacion != null) return
        actualizacion = viewModelScope.async {
            YoutubeRepo.actualizar(getApplication())
        }
    }

    /** Buscar y bajar pasan por aca: sin yt-dlp al dia, fallan igual. */
    private suspend fun esperarYtDlp() {
        val pendiente = actualizacion ?: return
        if (!pendiente.isCompleted) {
            trabajando = "Actualizando yt-dlp"
            pendiente.await()
            trabajando = null
        }
    }

    private fun refrescar() { enganchados = almacen.listar() }

    fun crear(nombre: String) {
        val limpio = nombre.trim().replace(Regex("[^A-Za-z0-9 _-]"), "")
        if (limpio.isBlank()) return
        val e = Enganchado(limpio)
        almacen.guardar(e)
        refrescar()
        actual = e
        refrescarResultados(e)
    }

    fun abrir(nombre: String) {
        actual = almacen.cargar(nombre)
        seleccionado = 0
        refrescarResultados(actual)
    }

    /**
     * Lo exportado queda en disco entre sesiones: al abrir un enganchado se
     * busca, para poder escucharlo o compartirlo sin volver a mezclar.
     */
    private fun refrescarResultados(e: Enganchado?) {
        soltarMezcla()
        resultadoMezcla = e?.let { File(almacen.exportes, "${it.nombre}.m4a") }?.takeIf { it.exists() }
        resultadoZip = e?.let { File(almacen.exportes, "${it.nombre}.zip") }?.takeIf { it.exists() }
        crucesMezcla = e?.let { calcularCruces(it) } ?: emptyList()
    }

    fun guardar() { actual?.let { almacen.guardar(it) } }

    fun borrar(nombre: String) {
        if (actual?.nombre == nombre) {
            refrescarResultados(null)   // soltar el archivo antes de borrarlo
            actual = null
        }
        almacen.borrar(nombre)
        refrescar()
    }

    /**
     * Publica una copia NUEVA de la receta, con temas nuevos adentro.
     *
     * Compose no ve cambios dentro de objetos comunes: si se muta la lista en
     * el lugar, la pantalla puede quedar mostrando datos viejos aunque el
     * disco este bien. Con instancias nuevas no hay nada que "ver": cambian.
     */
    private fun publicar(e: Enganchado) {
        actual = e.copy(temas = e.temas.map { it.copy() }.toMutableList())
        guardarPronto()
    }

    /**
     * Guarda a disco un instante despues del ultimo cambio.
     *
     * Arrastrar un borde de la onda dispara decenas de cambios por segundo;
     * escribir el JSON entero en cada uno trabaria justo el gesto que mas
     * importa que sea fluido. La pantalla se actualiza al toque igual: lo
     * unico que se demora es la escritura.
     */
    private var guardadoPendiente: kotlinx.coroutines.Job? = null

    private fun guardarPronto() {
        guardadoPendiente?.cancel()
        guardadoPendiente = viewModelScope.launch {
            delay(400)
            guardar()
        }
    }

    private fun tocar(bloque: Enganchado.() -> Unit) {
        val e = actual ?: return
        e.bloque()
        publicar(e)
    }

    /**
     * Aplica un cambio a UN tema de la receta VIGENTE, buscandolo por id.
     *
     * Las tareas largas (bajar, analizar) no pueden guardarse una referencia
     * al tema del principio: mientras corren, el usuario puede reordenar o
     * quitar cosas y esa referencia queda apuntando a una copia vieja.
     */
    private fun actualizarTema(id: String, cambio: Tema.() -> Unit) {
        val e = actual ?: return
        e.temas.firstOrNull { it.id == id }?.cambio() ?: return
        publicar(e)
    }

    // ------------------------------------------------------------ buscar

    fun buscar() {
        val q = consulta.trim()
        if (q.isBlank() || buscando) return
        consultaVigente = q
        pagina = 0
        hayMas = false
        resultados.clear()
        elegidos.clear()
        viewModelScope.launch {
            buscando = true
            esperarYtDlp()
            traerPagina(0)
            buscando = false
        }
    }

    /** La llama la lista al acercarse al final: scroll infinito. */
    fun cargarMas() {
        if (!hayMas || cargandoMas || buscando) return
        viewModelScope.launch {
            cargandoMas = true
            traerPagina(pagina + 1)
            cargandoMas = false
        }
    }

    private suspend fun traerPagina(p: Int) {
        val desde = p * POR_PAGINA + 1
        val hasta = (p + 1) * POR_PAGINA
        runCatching { YoutubeRepo.buscar(consultaVigente, desde, hasta) }
            .onSuccess { nuevos ->
                val ya = resultados.map { it.id }.toSet()
                val frescos = nuevos.filter { it.id !in ya }
                resultados.addAll(frescos)
                pagina = p
                // YouTube devuelve algo menos de lo pedido; si una pagina
                // no trae nada nuevo, se asume que no hay mas
                hayMas = frescos.isNotEmpty()
            }
            .onFailure {
                hayMas = false
                aviso = "No pude buscar: ${it.message}"
            }
    }

    fun alternarElegido(c: Candidato) {
        if (elegidos.any { it.id == c.id }) elegidos.removeAll { it.id == c.id }
        else elegidos.add(c)
    }

    fun estaElegido(c: Candidato) = elegidos.any { it.id == c.id }

    /** Agrega todos los marcados, en el orden en que se marcaron. */
    fun agregarElegidos() {
        if (elegidos.isEmpty()) return
        val ya = actual?.temas?.map { it.id }?.toSet() ?: emptySet()
        val nuevos = elegidos.filter { it.id !in ya }
        tocar { nuevos.forEach { temas.add(Tema(id = it.id, titulo = it.titulo)) } }
        val repetidos = elegidos.size - nuevos.size
        aviso = "Agregados ${nuevos.size}" +
            if (repetidos > 0) " ($repetidos ya estaban)" else ""
        limpiarBusqueda()
    }

    /** Agrega un solo candidato, todavia sin bajar. */
    fun agregar(c: Candidato) {
        tocar { temas.add(Tema(id = c.id, titulo = c.titulo)) }
        limpiarBusqueda()
    }

    private fun limpiarBusqueda() {
        resultados.clear()
        elegidos.clear()
        consulta = ""
        hayMas = false
    }

    fun reemplazar(indice: Int, c: Candidato) {
        tocar {
            temas.getOrNull(indice)?.let { viejo ->
                viejo.archivo?.let { File(it).delete() }
                temas[indice] = Tema(id = c.id, titulo = c.titulo)
            }
        }
        resultados.clear()
        consulta = ""
    }

    fun quitar(indice: Int) {
        tocar {
            temas.getOrNull(indice)?.archivo?.let { File(it).delete() }
            temas.removeAt(indice)
        }
        if (seleccionado >= (actual?.temas?.size ?: 0)) seleccionado = 0
    }

    /** Mueve el tema seleccionado en Tramos; la seleccion viaja con el. */
    fun moverSeleccionado(direccion: Int) {
        val total = actual?.temas?.size ?: return
        val hacia = seleccionado + direccion
        if (hacia !in 0 until total) return
        mover(seleccionado, direccion)
        seleccionado = hacia
    }

    fun mover(desde: Int, direccion: Int) {
        tocar {
            val hacia = desde + direccion
            if (hacia in temas.indices) {
                val t = temas[desde]; temas[desde] = temas[hacia]; temas[hacia] = t
            }
        }
    }

    // ------------------------------------------------------------- bajar

    fun bajarTodos() {
        val e = actual ?: return
        val faltan = e.temas.filter { !it.bajado }
        if (faltan.isEmpty()) { aviso = "Ya estan todos bajados"; return }

        val nombre = e.nombre
        viewModelScope.launch {
            esperarYtDlp()
            faltan.forEachIndexed { i, tema ->
                trabajando = "Bajando ${i + 1}/${faltan.size}"
                progreso = 0f
                runCatching {
                    val destino = almacen.archivoDe(nombre, tema.id)
                    val f = YoutubeRepo.bajar(
                        Candidato(tema.id, tema.titulo, "", 0, 0), destino
                    ) { progreso = it / 100f }
                    // cada tema se marca apenas termina, no todos al final:
                    // la lista se va poniendo verde a medida que avanza
                    actualizarTema(tema.id) { archivo = f.absolutePath }
                }.onFailure {
                    Log.e(YoutubeRepo.TAG, "bajarTodos ${tema.id} fallo", it)
                    aviso = "Fallo ${tema.titulo}: ${it.message}"
                }
            }
            trabajando = null
        }
    }

    // --------------------------------------------------------- analizar

    /**
     * Por defecto analiza solo los que falten, respetando los tramos ya
     * ajustados a mano. Con [todos] rehace el set entero y PISA esos ajustes:
     * la UI lo pide con doble toque por eso mismo.
     */
    fun analizar(soloEste: Int? = null, todos: Boolean = false) {
        val e = actual ?: return
        val objetivo = when {
            soloEste != null -> listOfNotNull(e.temas.getOrNull(soloEste))
            todos -> e.temas.filter { it.bajado }
            else -> e.temas.filter { it.bajado && it.bpm <= 0.0 }
        }

        if (objetivo.isEmpty()) { aviso = "No hay nada para analizar"; return }

        val largo = duracionPropuesta
        viewModelScope.launch {
            objetivo.forEachIndexed { i, tema ->
                trabajando = "Analizando ${i + 1}/${objetivo.size}"
                progreso = i.toFloat() / objetivo.size
                val ruta = tema.archivo ?: return@forEachIndexed
                runCatching {
                    // el calculo pesado va fuera del hilo de la UI...
                    val (r, onda) = withContext(Dispatchers.Default) {
                        // solo la envolvente: el tema entero en PCM no entra en memoria
                        val env = Audio.envolvente(File(ruta))
                        val r = Analisis.analizarEnvolvente(env.rms, env.duracion, largo)
                        r to Analisis.paraDibujar(r.envolvente, 900)
                    }
                    // ...y el resultado se aplica a la receta vigente
                    actualizarTema(tema.id) {
                        bpm = r.bpm
                        duracion = r.duracion
                        inicio = r.inicio
                        fin = r.fin
                        envolvente = onda
                    }
                }.onFailure {
                    Log.e(YoutubeRepo.TAG, "analizar ${tema.id} fallo", it)
                    aviso = "No pude analizar ${tema.titulo}: ${it.message}"
                }
            }
            trabajando = null
        }
    }

    fun ajustarTramo(indice: Int, inicio: Double, fin: Double) {
        tocar {
            temas.getOrNull(indice)?.let { t ->
                val i = inicio.coerceIn(0.0, maxOf(0.0, t.duracion - 1))
                t.inicio = (i * 100).toInt() / 100.0
                t.fin = fin.coerceIn(i + 1, t.duracion).let { (it * 100).toInt() / 100.0 }
            }
        }
    }

    fun pegarAlCompas(indice: Int) {
        val t = actual?.temas?.getOrNull(indice) ?: return
        val (i, f) = Analisis.pegarAlCompas(t.inicio, t.fin, t.bpm)
        ajustarTramo(indice, i, f)
    }

    fun cambiarCruce(v: Double) = tocar { cruce = v.coerceIn(0.0, 15.0) }

    // ------------------------------------------------- reproducir el tramo

    private var reproductor: MediaPlayer? = null
    var sonando by mutableStateOf(false); private set
    var posicion by mutableStateOf(0.0); private set

    /**
     * Que se esta escuchando: el tramo naranja o el tema completo. Cada boton
     * pausa solo lo suyo; tocar el otro cambia de modo sin pasar por pausa.
     */
    var modoTramo by mutableStateOf(true); private set

    /** Solo el tramo: arranca en el borde izquierdo y frena en el derecho. */
    fun tocarTramo(indice: Int) = reproducir(indice, soloTramo = true)

    /**
     * El tema completo desde el principio. Mientras suena, la linea blanca
     * recorre la onda y se ve donde esta el estribillo: sirve para elegir.
     */
    fun tocarEntero(indice: Int) = reproducir(indice, soloTramo = false)

    private fun reproducir(indice: Int, soloTramo: Boolean) {
        val t = actual?.temas?.getOrNull(indice) ?: return
        val archivo = t.archivo ?: return

        if (sonando) {
            val mismoBoton = modoTramo == soloTramo
            pausar()
            if (mismoBoton) return   // mismo boton = pausa; el otro = cambia de modo
        }
        if (sonandoMezcla) {   // la mezcla queda en pausa, sin perder la posicion
            reproductorMezcla?.pause()
            sonandoMezcla = false
        }
        modoTramo = soloTramo

        runCatching {
            reproductor?.release()
            reproductor = MediaPlayer().apply {
                setDataSource(archivo)
                prepare()
                seekTo(if (soloTramo) (t.inicio * 1000).toInt() else 0)
                setOnCompletionListener { sonando = false }
                start()
            }
            sonando = true

            viewModelScope.launch {
                while (sonando) {
                    val p = (reproductor?.currentPosition ?: 0) / 1000.0
                    posicion = p
                    // el borde se lee EN VIVO: si lo moves mientras suena,
                    // frena donde esta ahora y no donde estaba al arrancar
                    val fin = actual?.temas?.getOrNull(indice)?.fin ?: t.fin
                    if (modoTramo && p >= fin) { pausar(); break }
                    delay(80)
                }
            }
        }.onFailure { aviso = "No pude reproducir: ${it.message}" }
    }

    fun pausar() {
        runCatching { reproductor?.pause() }
        sonando = false
    }

    override fun onCleared() {
        guardar()   // si quedo un guardado diferido en el aire, que no se pierda
        reproductor?.release()
        reproductorMezcla?.release()
        reproductor = null
        super.onCleared()
    }

    // ----------------------------------------------------- escuchar la mezcla

    // Reproductor propio, separado del de los tramos: si compartieran uno,
    // escuchar un tramo te cortaria la mezcla y te perderia la posicion.
    private var reproductorMezcla: MediaPlayer? = null
    var sonandoMezcla by mutableStateOf(false); private set
    var posMezcla by mutableStateOf(0.0); private set
    var durMezcla by mutableStateOf(0.0); private set

    /** Segundo donde arranca cada cruce entre temas, dentro de la mezcla. */
    var crucesMezcla by mutableStateOf(listOf<Double>()); private set

    /**
     * Donde empieza cada transicion. Mezcla.renderizar escribe cada tramo
     * menos su cola de cruce, asi que el tema siguiente entra exactamente
     * cuando termina el largo visible del anterior.
     */
    private fun calcularCruces(e: Enganchado): List<Double> {
        var t = 0.0
        return e.temas.filter { it.bajado && it.largo > 0.5 }
            .dropLast(1)
            .map { tema -> t += tema.largo; t }
    }

    /** Prepara el reproductor sin arrancarlo, para saber cuanto dura. */
    fun cargarMezcla() {
        if (reproductorMezcla != null) return
        val f = resultadoMezcla ?: return
        runCatching {
            MediaPlayer().apply {
                setDataSource(f.absolutePath)
                prepare()
                setOnCompletionListener { sonandoMezcla = false }
            }
        }.onSuccess {
            reproductorMezcla = it
            durMezcla = it.duration / 1000.0
        }.onFailure { aviso = "No pude abrir la mezcla: ${it.message}" }
    }

    fun tocarMezcla() {
        cargarMezcla()
        val mp = reproductorMezcla ?: return
        if (sonandoMezcla) {
            mp.pause()
            sonandoMezcla = false
            return
        }
        pausar()   // si sonaba un tramo, que no se superpongan
        mp.start()
        sonandoMezcla = true
        viewModelScope.launch {
            while (sonandoMezcla) {
                posMezcla = (reproductorMezcla?.currentPosition ?: 0) / 1000.0
                delay(200)
            }
        }
    }

    fun buscarEnMezcla(segundo: Double) {
        cargarMezcla()
        val mp = reproductorMezcla ?: return
        val s = segundo.coerceIn(0.0, durMezcla)
        mp.seekTo((s * 1000).toInt())
        posMezcla = s
    }

    /**
     * Salta a unos segundos ANTES del cruce siguiente o anterior.
     *
     * Para evaluar un enganchado no hace falta escuchar 14 minutos de
     * corrido: lo que puede salir mal son las transiciones. El margen deja
     * oir como venia el tema antes de que entre el otro.
     */
    fun irACruce(direccion: Int) {
        val cruces = crucesMezcla
        if (cruces.isEmpty()) return
        val margen = 3.0
        val ahora = posMezcla
        val destino = if (direccion > 0)
            cruces.firstOrNull { it - margen > ahora + 0.5 }
        else
            cruces.lastOrNull { it - margen < ahora - 1.0 }
        destino ?: return
        buscarEnMezcla((destino - margen).coerceAtLeast(0.0))
        if (!sonandoMezcla) tocarMezcla()
    }

    private fun soltarMezcla() {
        reproductorMezcla?.release()
        reproductorMezcla = null
        sonandoMezcla = false
        posMezcla = 0.0
        durMezcla = 0.0
    }

    // ------------------------------------------------- render y exportar

    var resultadoMezcla by mutableStateOf<File?>(null); private set
    var resultadoZip by mutableStateOf<File?>(null); private set

    fun renderizar() {
        val e = actual ?: return
        // El archivo se va a pisar: se suelta el reproductor Y se esconde el
        // resultado. Si no, mientras se arma seguia visible el reproductor
        // apuntando a un m4a a medio escribir.
        soltarMezcla()
        resultadoMezcla = null
        viewModelScope.launch {
            trabajando = "Mezclando"
            runCatching {
                val destino = File(almacen.exportes, "${e.nombre}.m4a")
                Mezcla.renderizar(
                    e, almacen, destino,
                    avance = { i, n ->
                        trabajando = "Mezclando $i/$n"
                        progreso = i.toFloat() / n
                    },
                    // El ultimo paso es el mas largo: sin porcentaje, un
                    // "Mezclando 5/5" quieto durante minutos parece un cuelgue.
                    codificando = { f ->
                        trabajando = "Codificando ${(f * 100).toInt()}%"
                        progreso = f
                    },
                )
            }.onSuccess {
                resultadoMezcla = it
                // los cruces salen de la receta CON la que se mezclo, no de la
                // que quede si despues seguis tocando tramos
                crucesMezcla = calcularCruces(e)
                cargarMezcla()
                aviso = "Listo: ${it.name}"
            }.onFailure { aviso = "Fallo la mezcla: ${it.message}" }
            trabajando = null
        }
    }

    fun exportarReaper() {
        val e = actual ?: return
        // Mismo criterio que la mezcla: mientras se rearma, el ZIP viejo no
        // se ofrece. Compartirlo a mitad de escritura manda un archivo roto.
        resultadoZip = null
        viewModelScope.launch {
            trabajando = "Exportando stems"
            runCatching {
                Reaper.exportar(
                    e, almacen,
                    avance = { i, n ->
                        trabajando = "Exportando $i/$n"
                        progreso = i.toFloat() / n
                    },
                    empaquetando = { trabajando = "Armando el ZIP" },
                )
            }.onSuccess {
                resultadoZip = it
                aviso = "ZIP listo: ${it.name}"
            }.onFailure { aviso = "Fallo el export: ${it.message}" }
            trabajando = null
        }
    }

    fun actualizarYtDlp() {
        viewModelScope.launch {
            trabajando = "Actualizando yt-dlp"
            aviso = YoutubeRepo.actualizar(getApplication())
            trabajando = null
        }
    }
}
