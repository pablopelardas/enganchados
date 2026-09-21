package ar.enganchados.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Un candidato de la busqueda de YouTube, antes de bajarlo. */
data class Candidato(
    val id: String,
    val titulo: String,
    val canal: String,
    val duracion: Int,
    val vistas: Long,
) {
    val url get() = "https://www.youtube.com/watch?v=$id"
    val miniatura get() = "https://i.ytimg.com/vi/$id/mqdefault.jpg"
    val reloj get() = "%d:%02d".format(duracion / 60, duracion % 60)
}

/**
 * Un tema dentro del enganchado.
 *
 * `archivo` puede ser null: la fila existe en la lista aunque todavia no
 * se haya bajado el audio. `inicio`/`fin` son el tramo que suena.
 */
data class Tema(
    val id: String,
    var titulo: String,
    var archivo: String? = null,
    var bpm: Double = 0.0,
    var duracion: Double = 0.0,
    var inicio: Double = 0.0,
    var fin: Double = 0.0,
    var envolvente: FloatArray = FloatArray(0),
) {
    val bajado get() = archivo != null
    val largo get() = fin - inicio

    fun aJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("titulo", titulo)
        put("archivo", archivo ?: JSONObject.NULL)
        put("bpm", bpm)
        put("duracion", duracion)
        put("inicio", inicio)
        put("fin", fin)
        // La envolvente se guarda para no re-analizar solo para dibujar.
        put("envolvente", JSONArray().also { a -> envolvente.forEach { a.put(it.toDouble()) } })
    }

    // data class con FloatArray necesita esto a mano
    override fun equals(other: Any?) = other is Tema && other.id == id
    override fun hashCode() = id.hashCode()

    companion object {
        fun deJson(o: JSONObject): Tema {
            val arr = o.optJSONArray("envolvente")
            val env = FloatArray(arr?.length() ?: 0) { arr!!.getDouble(it).toFloat() }
            return Tema(
                id = o.getString("id"),
                titulo = o.getString("titulo"),
                archivo = if (o.isNull("archivo")) null else o.getString("archivo"),
                bpm = o.optDouble("bpm", 0.0),
                duracion = o.optDouble("duracion", 0.0),
                inicio = o.optDouble("inicio", 0.0),
                fin = o.optDouble("fin", 0.0),
                envolvente = env,
            )
        }
    }
}

/**
 * La receta: QUE temas, en QUE orden, y QUE tramo de cada uno.
 *
 * Igual que en la version de escritorio, este objeto es el contrato. El
 * analisis propone, el usuario corrige, y el render solo ejecuta.
 */
data class Enganchado(
    val nombre: String,
    val temas: MutableList<Tema> = mutableListOf(),
    var cruce: Double = 4.0,
) {
    val bajados get() = temas.count { it.bajado }
    val duracionTotal get() = temas.sumOf { it.largo } - cruce * maxOf(0, temas.size - 1)

    fun aJson(): JSONObject = JSONObject().apply {
        put("nombre", nombre)
        put("cruce", cruce)
        put("temas", JSONArray().also { a -> temas.forEach { a.put(it.aJson()) } })
    }

    companion object {
        fun deJson(o: JSONObject): Enganchado {
            val e = Enganchado(o.getString("nombre"), cruce = o.optDouble("cruce", 4.0))
            val arr = o.optJSONArray("temas") ?: JSONArray()
            for (i in 0 until arr.length()) e.temas.add(Tema.deJson(arr.getJSONObject(i)))
            return e
        }
    }
}

/**
 * Guarda y lee enganchados del almacenamiento privado de la app.
 *
 * Se usa el directorio privado a proposito: no pide permisos y el
 * sistema lo limpia si se desinstala la app.
 */
class Almacen(private val raiz: File) {

    private val recetas = File(raiz, "recetas").apply { mkdirs() }
    val audios = File(raiz, "audios").apply { mkdirs() }
    val exportes = File(raiz, "exportes").apply { mkdirs() }

    fun listar(): List<String> = recetas.listFiles()
        ?.filter { it.extension == "json" }
        ?.map { it.nameWithoutExtension }
        ?.sorted() ?: emptyList()

    fun cargar(nombre: String): Enganchado? {
        val f = File(recetas, "$nombre.json")
        if (!f.exists()) return null
        return runCatching { Enganchado.deJson(JSONObject(f.readText())) }.getOrNull()
    }

    fun guardar(e: Enganchado) {
        File(recetas, "${e.nombre}.json").writeText(e.aJson().toString(2))
    }

    /**
     * Borra el enganchado ENTERO: receta, audios bajados y todo lo exportado.
     *
     * La primera version solo borraba receta y audios, y dejaba la mezcla y
     * el ZIP. Eran la parte mas pesada (un ZIP de stems pasa los 100 MB) y
     * quedaban huerfanos, sin forma de llegar a ellos desde la app.
     */
    fun borrar(nombre: String) {
        File(recetas, "$nombre.json").delete()
        File(audios, nombre).deleteRecursively()
        File(exportes, "$nombre.m4a").delete()
        File(exportes, "$nombre.zip").delete()
        File(exportes, nombre).deleteRecursively()   // carpeta de stems a medio armar
    }

    /**
     * Restos de un render o export que se corto a la mitad: la app murio, se
     * quedo sin bateria, la cerraste. Se llama al abrir la app, cuando no
     * puede haber nada corriendo, asi que no hay riesgo de pisar trabajo vivo.
     */
    fun limpiarTemporales() {
        exportes.listFiles()?.forEach { f ->
            when {
                f.name.startsWith("_mezcla") -> f.delete()        // WAV intermedio de la mezcla
                f.isDirectory -> f.deleteRecursively()            // stems antes de empaquetar
            }
        }
    }

    fun carpetaDe(nombre: String) = File(audios, nombre).apply { mkdirs() }

    /** El audio se nombra por ID: dice QUE video es, nunca donde va. */
    fun archivoDe(enganchado: String, id: String) = File(carpetaDe(enganchado), "$id.m4a")
}
