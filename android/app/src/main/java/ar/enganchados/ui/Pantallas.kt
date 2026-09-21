package ar.enganchados.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ar.enganchados.VistaModelo
import ar.enganchados.data.Analisis
import ar.enganchados.data.Candidato
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import coil.compose.AsyncImage
import kotlin.math.roundToInt

// ============================================================ 1. TEMAS

@Composable
fun PantallaTemas(vm: VistaModelo, onBuscarPara: (Int) -> Unit) {
    val e = vm.actual ?: return

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp, 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { vm.bajarTodos() },
                modifier = Modifier.weight(1f).height(48.dp),
                enabled = vm.trabajando == null && e.temas.any { !it.bajado },
            ) { Text("Bajar (${e.temas.count { !it.bajado }})") }

            OutlinedButton(
                onClick = { onBuscarPara(-1) },
                modifier = Modifier.weight(1f).height(48.dp),
            ) { Text("+ Agregar") }
        }

        if (e.temas.isEmpty()) {
            Vacio("Sin temas todavia.\nTocá \"+ Agregar\" y buscá el primero.")
            return
        }

        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(e.temas, key = { i, t -> "$i-${t.id}" }) { i, tema ->
                FilaTema(
                    numero = i + 1,
                    titulo = tema.titulo,
                    bajado = tema.bajado,
                    bpm = tema.bpm,
                    onCambiar = { onBuscarPara(i) },
                    onSubir = { vm.mover(i, -1) }.takeIf { i > 0 },
                    onBajar = { vm.mover(i, 1) }.takeIf { i < e.temas.lastIndex },
                    onQuitar = { vm.quitar(i) },
                )
                HorizontalDivider(color = Borde)
            }
        }
    }
}

@Composable
private fun FilaTema(
    numero: Int, titulo: String, bajado: Boolean, bpm: Double,
    onCambiar: () -> Unit, onSubir: (() -> Unit)?, onBajar: (() -> Unit)?,
    onQuitar: () -> Unit,
) {
    var confirmando by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "%02d".format(numero),
            color = Tenue, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
            modifier = Modifier.width(28.dp),
        )

        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(titulo, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (bajado) "bajado" else "sin bajar",
                    color = if (bajado) Ok else Mal, fontSize = 12.sp,
                )
                if (bpm > 0) Text("%.0f BPM".format(bpm), color = Tenue, fontSize = 12.sp)
            }
        }

        onSubir?.let {
            IconButton(it, Modifier.size(40.dp)) {
                Icon(Icons.Default.KeyboardArrowUp, "Subir", tint = Tenue)
            }
        }
        onBajar?.let {
            IconButton(it, Modifier.size(40.dp)) {
                Icon(Icons.Default.KeyboardArrowDown, "Bajar", tint = Tenue)
            }
        }
        IconButton(onCambiar, Modifier.size(40.dp)) {
            Icon(Icons.Default.Search, "Cambiar video", tint = Acento)
        }

        // Confirmacion en dos toques: un dialogo modal para cada borrado
        // corta el ritmo, y un toque solo borra sin querer.
        IconButton(
            onClick = { if (confirmando) onQuitar() else confirmando = true },
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                if (confirmando) Icons.Default.CheckCircle else Icons.Default.Close,
                if (confirmando) "Confirmar" else "Quitar",
                tint = Mal,
            )
        }
    }

    LaunchedEffect(confirmando) {
        if (confirmando) { kotlinx.coroutines.delay(3000); confirmando = false }
    }
}

// ======================================================== BUSCADOR

/**
 * Buscar en YouTube.
 *
 * Para AGREGAR es seleccion multiple: buscás una banda y marcás varios de
 * un saque. Para CAMBIAR el video de una fila es de a uno, porque una fila
 * es un tema.
 */
@Composable
fun HojaBuscador(vm: VistaModelo, paraFila: Int, onCerrar: () -> Unit) {
    val multiple = paraFila < 0
    val teclado = LocalSoftwareKeyboardController.current
    val lista = rememberLazyListState()

    // Buscar esconde el teclado: si no, tapa justo los resultados.
    val lanzar = {
        teclado?.hide()
        vm.buscar()
    }

    // Scroll infinito: cuando se ven los ultimos 3, se pide la pagina que sigue.
    val cercaDelFinal by remember {
        derivedStateOf {
            val ultimo = lista.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            ultimo >= lista.layoutInfo.totalItemsCount - 3
        }
    }
    LaunchedEffect(cercaDelFinal, vm.resultados.size) {
        if (cercaDelFinal && vm.resultados.isNotEmpty()) vm.cargarMas()
    }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            if (multiple) "Agregar temas" else "Cambiar el video del tema ${paraFila + 1}",
            fontWeight = FontWeight.Bold, fontSize = 18.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (multiple) "Marcá todos los que quieras. Escuchalos en YouTube antes: " +
                "el primer resultado suele ser un cover o un remix."
            else "El primer resultado suele ser un cover o un remix. Escuchalo antes de elegir.",
            color = Tenue, fontSize = 13.sp,
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = vm.consulta,
            onValueChange = { vm.consulta = it },
            label = { Text("artista, tema o banda") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            // el boton de "buscar" del teclado tambien lanza la busqueda
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { lanzar() }),
            trailingIcon = {
                IconButton({ lanzar() }) {
                    Icon(Icons.Default.Search, "Buscar", tint = Acento)
                }
            },
        )

        Spacer(Modifier.height(8.dp))
        if (vm.buscando) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Acento)

        LazyColumn(
            state = lista,
            modifier = Modifier.weight(1f, fill = false).heightIn(max = 460.dp),
        ) {
            items(vm.resultados, key = { it.id }) { c ->
                TarjetaCandidato(
                    c = c,
                    multiple = multiple,
                    elegido = vm.estaElegido(c),
                    onTocar = {
                        if (multiple) vm.alternarElegido(c)
                        else { vm.reemplazar(paraFila, c); onCerrar() }
                    },
                )
            }

            if (vm.cargandoMas) {
                item {
                    Box(Modifier.fillMaxWidth().padding(12.dp), Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = Acento)
                    }
                }
            } else if (!vm.hayMas && vm.resultados.isNotEmpty()) {
                item {
                    Text(
                        "No hay más resultados",
                        color = Tenue, fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (multiple) {
            Button(
                onClick = { vm.agregarElegidos(); onCerrar() },
                enabled = vm.elegidos.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(
                    if (vm.elegidos.isEmpty()) "Marcá los que quieras"
                    else "Agregar ${vm.elegidos.size}"
                )
            }
        }

        TextButton(onCerrar, Modifier.fillMaxWidth()) { Text("Cancelar", color = Tenue) }
    }
}

@Composable
private fun TarjetaCandidato(
    c: Candidato,
    multiple: Boolean,
    elegido: Boolean,
    onTocar: () -> Unit,
) {
    val abrir = LocalUriHandlerSeguro()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (elegido) Acento.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onTocar)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (multiple) {
            Checkbox(
                checked = elegido,
                onCheckedChange = { onTocar() },
                colors = CheckboxDefaults.colors(checkedColor = Acento),
            )
        }

        AsyncImage(
            model = c.miniatura, contentDescription = null,
            modifier = Modifier.width(88.dp).height(50.dp).clip(RoundedCornerShape(6.dp)),
        )
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(c.titulo, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
            Text("${c.canal} · ${c.reloj}", color = Tenue, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        // Escucharlo en YouTube antes de elegir: es LA razon de que exista
        // la eleccion manual en vez de agarrar el primero.
        IconButton({ abrir(c.url) }, Modifier.size(40.dp)) {
            Icon(Icons.Default.PlayArrow, "Ver en YouTube", tint = Tenue)
        }

        if (!multiple) {
            Button(onTocar, Modifier.height(40.dp)) { Text("Usar") }
        }
    }
}


// ======================================================== 2. TRAMOS

@Composable
fun PantallaTramos(vm: VistaModelo) {
    val e = vm.actual ?: return
    val bajados = e.temas.filter { it.bajado }

    if (bajados.isEmpty()) {
        Vacio("Primero bajá los temas.\nDespués acá elegís qué pedazo suena de cada uno.")
        return
    }

    val i = vm.seleccionado.coerceIn(0, e.temas.lastIndex)
    val tema = e.temas[i]

    // Analizar de a uno sirve para rehacer un tema; para arrancar un
    // enganchado nuevo hace falta analizar todos de un saque.
    val pendientes = e.temas.count { it.bajado && it.bpm <= 0.0 }

    // Cambiar de tema frena lo que sonaba: si no, seguis escuchando el
    // anterior mientras mirás la onda del nuevo.
    LaunchedEffect(i) { vm.pausar() }

    // Scroll vertical: con un titulo en dos lineas en un celular chico, lo
    // de abajo quedaba afuera de la pantalla sin forma de alcanzarlo. Los
    // gestos no se pisan: la onda toma lo horizontal, el scroll lo vertical.
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {

        if (pendientes > 0) {
            Button(
                { vm.analizar() },
                Modifier.fillMaxWidth().height(48.dp),
                enabled = vm.trabajando == null,
            ) { Text("Analizar todos ($pendientes)") }
        } else {
            // Rehacer todo pisa los ajustes a mano: pide un segundo toque.
            var confirmando by remember { mutableStateOf(false) }
            LaunchedEffect(confirmando) {
                if (confirmando) { kotlinx.coroutines.delay(3000); confirmando = false }
            }
            OutlinedButton(
                {
                    if (confirmando) { vm.analizar(todos = true); confirmando = false }
                    else confirmando = true
                },
                Modifier.fillMaxWidth().height(48.dp),
                enabled = vm.trabajando == null,
            ) {
                Text(
                    if (confirmando) "Pisa tus ajustes · tocá de nuevo" else "Re-analizar todos",
                    color = if (confirmando) Mal else Tenue,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(e.temas) { idx, t ->
                FilterChip(
                    selected = idx == i,
                    onClick = { vm.seleccionado = idx },
                    enabled = t.bajado,
                    label = { Text("${idx + 1}") },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tema.titulo, fontWeight = FontWeight.Bold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                Text(
                    if (tema.bpm > 0) "%.0f BPM · dura %ds".format(tema.bpm, tema.duracion.toInt())
                    else "sin analizar",
                    color = Tenue, fontSize = 13.sp,
                )
            }
            // Reordenar desde aca: el orden se decide escuchando los tramos,
            // no mirando una lista de titulos.
            IconButton({ vm.moverSeleccionado(-1) }, enabled = i > 0) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Mover antes")
            }
            Text("${i + 1}/${e.temas.size}", color = Tenue, fontSize = 13.sp,
                fontFamily = FontFamily.Monospace)
            IconButton({ vm.moverSeleccionado(1) }, enabled = i < e.temas.lastIndex) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Mover después")
            }
        }

        Spacer(Modifier.height(16.dp))

        if (tema.bpm <= 0) {
            Button(
                { vm.analizar(i) },
                Modifier.fillMaxWidth().height(52.dp),
                enabled = vm.trabajando == null,
            ) { Text("Analizar este tema") }
        } else {
            Onda(
                envolvente = tema.envolvente,
                duracion = tema.duracion,
                inicio = tema.inicio,
                fin = tema.fin,
                posicion = if (vm.sonando) vm.posicion else null,
                onCambio = { ini, fin -> vm.ajustarTramo(i, ini, fin) },
            )

            Spacer(Modifier.height(12.dp))
            Text(
                "%.1fs → %.1fs   (%ds · %d compases)".format(
                    tema.inicio, tema.fin, tema.largo.toInt(),
                    Analisis.compasesEn(tema.inicio, tema.fin, tema.bpm),
                ),
                color = Acento, fontFamily = FontFamily.Monospace, fontSize = 14.sp,
            )

            Spacer(Modifier.height(16.dp))

            // Cada boton muestra "Pausar" solo si lo que suena es lo suyo.
            val suenaTramo = vm.sonando && vm.modoTramo
            val suenaEntero = vm.sonando && !vm.modoTramo
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    { vm.tocarTramo(i) },
                    Modifier.weight(1f).height(52.dp),
                ) {
                    Icon(if (suenaTramo) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (suenaTramo) "Pausar" else "Tramo")
                }
                OutlinedButton(
                    { vm.tocarEntero(i) },
                    Modifier.weight(1f).height(52.dp),
                ) {
                    Icon(if (suenaEntero) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (suenaEntero) "Pausar" else "Tema entero")
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                { vm.pegarAlCompas(i) },
                Modifier.fillMaxWidth().height(48.dp),
            ) { Text("Ajustar a compases enteros") }
            Text(
                "Redondea el largo para que el corte caiga donde termina una frase " +
                    "musical, y no a mitad de un compás. El arranque no se mueve.",
                color = Tenue, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(Modifier.height(8.dp))
            TextButton({ vm.analizar(i) }, Modifier.fillMaxWidth()) {
                Text("Re-analizar solo este", color = Tenue)
            }
        }
    }
}

private enum class Agarre { NADA, INICIO, FIN, TODO }

/**
 * La onda con el tramo seleccionado.
 *
 * - Las manijas de los costados estiran o achican el tramo.
 * - Arrastrar desde adentro del tramo lo mueve entero, sin cambiar el largo.
 *
 * Dos detalles que hacen que se sienta fluido:
 *
 * 1. El detector de gestos NO se reinicia cuando cambian inicio/fin. Si
 *    fueran claves de pointerInput, cada cuadro del arrastre cancelaria el
 *    gesto y habria que volver a agarrar: el tramo avanzaba a saltitos.
 * 2. Todo se calcula desde donde estaba el tramo AL TOCAR, mas el
 *    desplazamiento acumulado. Sumarle cada movimiento al valor actual
 *    pierde recorrido, porque los eventos del dedo llegan mas rapido que
 *    el redibujado.
 */
@Composable
private fun Onda(
    envolvente: FloatArray,
    duracion: Double,
    inicio: Double,
    fin: Double,
    posicion: Double?,
    onCambio: (Double, Double) -> Unit,
) {
    // Los valores vivos se leen desde adentro del gesto sin reiniciarlo.
    val iniVivo by rememberUpdatedState(inicio)
    val finVivo by rememberUpdatedState(fin)
    val cambiar by rememberUpdatedState(onCambio)

    var agarre by remember { mutableStateOf(Agarre.NADA) }
    val radioAgarre = with(LocalDensity.current) { 28.dp.toPx() }

    Box(
        Modifier
            .fillMaxWidth()
            .height(170.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Panel)
            .pointerInput(duracion) {
                var ancho = 1f
                var baseIni = 0.0
                var baseFin = 0.0
                var acumulado = 0f

                detectHorizontalDragGestures(
                    onDragStart = { pos ->
                        ancho = size.width.toFloat().coerceAtLeast(1f)
                        baseIni = iniVivo
                        baseFin = finVivo
                        acumulado = 0f

                        val xIni = (baseIni / duracion * ancho).toFloat()
                        val xFin = (baseFin / duracion * ancho).toFloat()
                        val dIni = kotlin.math.abs(pos.x - xIni)
                        val dFin = kotlin.math.abs(pos.x - xFin)

                        agarre = when {
                            // las manijas tienen prioridad, con zona generosa
                            dIni <= radioAgarre && dIni <= dFin -> Agarre.INICIO
                            dFin <= radioAgarre -> Agarre.FIN
                            pos.x in xIni..xFin -> Agarre.TODO
                            // afuera del tramo: se estira el borde mas cercano
                            pos.x < xIni -> Agarre.INICIO
                            else -> Agarre.FIN
                        }
                    },
                    onDragEnd = { agarre = Agarre.NADA },
                    onDragCancel = { agarre = Agarre.NADA },
                    onHorizontalDrag = { cambio, dx ->
                        cambio.consume()
                        acumulado += dx
                        val dt = acumulado / ancho * duracion

                        when (agarre) {
                            Agarre.INICIO -> cambiar(
                                (baseIni + dt).coerceIn(0.0, baseFin - 1.0), baseFin)
                            Agarre.FIN -> cambiar(
                                baseIni, (baseFin + dt).coerceIn(baseIni + 1.0, duracion))
                            Agarre.TODO -> {
                                val largo = baseFin - baseIni
                                val nuevoIni = (baseIni + dt).coerceIn(0.0, duracion - largo)
                                cambiar(nuevoIni, nuevoIni + largo)
                            }
                            Agarre.NADA -> Unit
                        }
                    },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val xIni = (inicio / duracion * size.width).toFloat()
            val xFin = (fin / duracion * size.width).toFloat()

            // el tramo: mas marcado si lo estas moviendo entero
            drawRect(
                color = Acento.copy(alpha = if (agarre == Agarre.TODO) 0.30f else 0.16f),
                topLeft = Offset(xIni, 0f),
                size = Size((xFin - xIni).coerceAtLeast(1f), size.height),
            )

            val n = envolvente.size
            if (n > 0) {
                val paso = size.width / n
                for (k in 0 until n) {
                    val x = k * paso
                    val alto = (envolvente[k] * size.height * 0.82f).coerceAtLeast(1f)
                    drawRect(
                        color = if (x in xIni..xFin) Acento else Color(0xFF3B4252),
                        topLeft = Offset(x, (size.height - alto) / 2),
                        size = Size(paso.coerceAtLeast(1f), alto),
                    )
                }
            }

            manija(xIni, agarre == Agarre.INICIO)
            manija(xFin, agarre == Agarre.FIN)

            posicion?.let { p ->
                val x = (p / duracion * size.width).toFloat()
                drawRect(Color.White, Offset(x - 1f, 0f), Size(2f, size.height))
            }
        }
    }

    Text(
        "Estirá desde las manijas · arrastrá desde el medio para mover el tramo entero",
        color = Tenue, fontSize = 12.sp,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/**
 * Una manija: linea de borde mas una pastilla con rayitas en el medio.
 * Las rayitas son la convencion universal de "esto se agarra".
 */
private fun DrawScope.manija(x: Float, activa: Boolean) {
    val color = if (activa) Color.White else AcentoFuerte
    val linea = 3.dp.toPx()
    drawRect(color, Offset(x - linea / 2, 0f), Size(linea, size.height))

    val ancho = 16.dp.toPx()
    val alto = 46.dp.toPx()
    val arriba = (size.height - alto) / 2
    drawRoundRect(
        color = color,
        topLeft = Offset(x - ancho / 2, arriba),
        size = Size(ancho, alto),
        cornerRadius = CornerRadius(ancho / 2, ancho / 2),
    )

    val raya = Color(0xFF1A1206)
    val largoRaya = alto * 0.42f
    for (d in listOf(-3.dp.toPx(), 3.dp.toPx())) {
        drawRect(
            color = raya,
            topLeft = Offset(x + d - 0.75.dp.toPx(), arriba + (alto - largoRaya) / 2),
            size = Size(1.5.dp.toPx(), largoRaya),
        )
    }
}


// ======================================================== 3. RENDER

@Composable
fun PantallaRender(vm: VistaModelo, onCompartir: (java.io.File) -> Unit) {
    val e = vm.actual ?: return
    val listos = e.temas.count { it.bajado && it.bpm > 0 }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(16.dp)) {
                Text(e.nombre, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(
                    "$listos de ${e.temas.size} listos · " +
                        "%d min estimados".format((e.duracionTotal / 60).toInt()),
                    color = Tenue, fontSize = 13.sp,
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Cruce: %.1fs".format(e.cruce), color = Tenue, fontSize = 14.sp)
        }
        Slider(
            value = e.cruce.toFloat(),
            onValueChange = { vm.cambiarCruce(it.toDouble()) },
            valueRange = 0f..12f,
            steps = 23,
        )

        Button(
            { vm.renderizar() },
            Modifier.fillMaxWidth().height(56.dp),
            enabled = vm.trabajando == null && listos >= 2,
        ) { Text("Armar el enganchado", fontSize = 16.sp) }

        vm.resultadoMezcla?.let { f ->
            ReproductorMezcla(vm)
            OutlinedButton(
                { onCompartir(f) },
                Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp))
                Text("Compartir ${f.name}  (%.1f MB)".format(f.length() / 1024.0 / 1024))
            }
        }

        HorizontalDivider(color = Borde)

        Text(
            "Para ponerle un beat encima hace falta llevar los pedazos a la PC. " +
                "El ZIP trae cada tramo suelto más el proyecto de Reaper armado.",
            color = Tenue, fontSize = 13.sp,
        )

        OutlinedButton(
            { vm.exportarReaper() },
            Modifier.fillMaxWidth().height(52.dp),
            enabled = vm.trabajando == null && listos >= 1,
        ) { Text("Exportar stems + Reaper") }

        vm.resultadoZip?.let { f ->
            Button(
                { onCompartir(f) },
                Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp))
                Text("Mandar ZIP  (%.1f MB)".format(f.length() / 1024.0 / 1024))
            }
        }
    }
}

/**
 * Escuchar el resultado sin salir de la app.
 *
 * Los botones de los costados saltan de cruce en cruce: para evaluar un
 * enganchado no hace falta escuchar todo de corrido, lo que puede salir mal
 * son las transiciones.
 */
@Composable
private fun ReproductorMezcla(vm: VistaModelo) {
    LaunchedEffect(vm.resultadoMezcla) { vm.cargarMezcla() }

    // Mientras arrastrás la barra, la posicion que llega del reproductor no
    // la pisa: si no, el dedo y la barra se pelean y salta para atras.
    var arrastrando by remember { mutableStateOf<Float?>(null) }
    val duracion = vm.durMezcla.toFloat().coerceAtLeast(0.1f)
    val mostrada = arrastrando ?: vm.posMezcla.toFloat().coerceIn(0f, duracion)

    Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(16.dp)) {
            Text("Escuchar el resultado", fontWeight = FontWeight.Bold)

            Slider(
                value = mostrada,
                onValueChange = { arrastrando = it },
                onValueChangeFinished = {
                    arrastrando?.let { vm.buscarEnMezcla(it.toDouble()) }
                    arrastrando = null
                },
                valueRange = 0f..duracion,
            )

            Row(Modifier.fillMaxWidth()) {
                Text(reloj(mostrada.toDouble()), color = Tenue, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace)
                Spacer(Modifier.weight(1f))
                Text(reloj(vm.durMezcla), color = Tenue, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace)
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val hayCruces = vm.crucesMezcla.isNotEmpty()
                IconButton({ vm.irACruce(-1) }, Modifier.size(52.dp), enabled = hayCruces) {
                    Icon(Icons.Default.SkipPrevious, "Cruce anterior")
                }
                Spacer(Modifier.width(16.dp))
                FilledIconButton({ vm.tocarMezcla() }, Modifier.size(64.dp)) {
                    Icon(
                        if (vm.sonandoMezcla) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (vm.sonandoMezcla) "Pausar" else "Escuchar",
                        Modifier.size(32.dp),
                    )
                }
                Spacer(Modifier.width(16.dp))
                IconButton({ vm.irACruce(1) }, Modifier.size(52.dp), enabled = hayCruces) {
                    Icon(Icons.Default.SkipNext, "Próximo cruce")
                }
            }

            if (vm.crucesMezcla.isNotEmpty()) {
                Text(
                    "⏮ ⏭ saltan a 3 s antes de cada cruce entre temas " +
                        "(${vm.crucesMezcla.size} en total): ahí se escucha si la transición quedó bien.",
                    color = Tenue, fontSize = 12.sp,
                )
            }
        }
    }
}

private fun reloj(segundos: Double): String {
    val s = segundos.toInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

// ========================================================== auxiliares

@Composable
private fun Vacio(texto: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(texto, color = Tenue, fontSize = 15.sp)
    }
}

/** Abre una URL en el navegador sin acoplar las pantallas al contexto. */
@Composable
private fun LocalUriHandlerSeguro(): (String) -> Unit {
    val handler = androidx.compose.ui.platform.LocalUriHandler.current
    return { url -> runCatching { handler.openUri(url) } }
}
