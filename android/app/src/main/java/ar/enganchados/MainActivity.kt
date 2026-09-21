package ar.enganchados

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import ar.enganchados.ui.*
import java.io.File

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TemaEnganchados {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Raiz(::compartir)
                }
            }
        }
    }

    /** Manda el archivo al share sheet: Drive, mail, cable, lo que haya. */
    private fun compartir(archivo: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.archivos", archivo)
        val tipo = if (archivo.extension == "zip") "application/zip" else "audio/mp4"
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = tipo
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Compartir ${archivo.name}"))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Raiz(onCompartir: (File) -> Unit) {
    val vm: VistaModelo = viewModel()
    var pestana by remember { mutableStateOf(0) }
    var buscandoPara by remember { mutableStateOf<Int?>(null) }
    var eligiendo by remember { mutableStateOf(false) }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.aviso) {
        vm.aviso?.let { snackbar.showSnackbar(it); vm.aviso = null }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(vm.actual?.nombre ?: "Enganchados", fontSize = 18.sp)
                        vm.trabajando?.let {
                            Text(it, fontSize = 12.sp, color = Acento)
                        }
                    }
                },
                actions = {
                    IconButton({ eligiendo = true }) {
                        Icon(Icons.Default.Menu, "Enganchados")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Panel),
            )
        },
        bottomBar = {
            if (vm.actual != null) {
                NavigationBar(containerColor = Panel) {
                    listOf(
                        Triple("Temas", Icons.AutoMirrored.Filled.List, 0),
                        Triple("Tramos", Icons.Default.Tune, 1),
                        Triple("Armar", Icons.Default.GraphicEq, 2),
                    ).forEach { (texto, icono, idx) ->
                        NavigationBarItem(
                            selected = pestana == idx,
                            onClick = { pestana = idx },
                            icon = { Icon(icono, texto) },
                            label = { Text(texto) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (vm.trabajando != null) {
                LinearProgressIndicator(
                    progress = { vm.progreso },
                    modifier = Modifier.fillMaxWidth(),
                    color = Acento,
                )
            }

            if (vm.actual == null) {
                Bienvenida(vm)
            } else when (pestana) {
                0 -> PantallaTemas(vm) { buscandoPara = it }
                1 -> PantallaTramos(vm)
                else -> PantallaRender(vm, onCompartir)
            }
        }
    }

    buscandoPara?.let { fila ->
        ModalBottomSheet(onDismissRequest = { buscandoPara = null }) {
            HojaBuscador(vm, fila) { buscandoPara = null }
        }
    }

    if (eligiendo) {
        ModalBottomSheet(onDismissRequest = { eligiendo = false }) {
            ListaEnganchados(vm) { eligiendo = false }
        }
    }
}

@Composable
private fun Bienvenida(vm: VistaModelo) {
    var nombre by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Enganchados", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Acento)
        Spacer(Modifier.height(8.dp))
        Text(
            "Buscá los temas, elegí qué pedazo suena de cada uno, " +
                "y armá el enganchado.",
            color = Tenue, fontSize = 14.sp,
        )

        if (!App.listo) {
            Spacer(Modifier.height(16.dp))
            Text(
                App.error ?: "yt-dlp no arrancó: buscar y bajar no van a andar.",
                color = Mal, fontSize = 13.sp,
            )
        }

        Spacer(Modifier.height(28.dp))
        OutlinedTextField(
            value = nombre,
            onValueChange = { nombre = it },
            label = { Text("nombre, ej: carnaval") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Button(
            { vm.crear(nombre); nombre = "" },
            Modifier.fillMaxWidth().height(52.dp),
            enabled = nombre.isNotBlank(),
        ) { Text("Empezar uno nuevo") }

        if (vm.enganchados.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("O seguí con uno:", color = Tenue, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            vm.enganchados.forEach { n ->
                TextButton({ vm.abrir(n) }, Modifier.fillMaxWidth()) { Text(n) }
            }
        }
    }
}

@Composable
private fun ListaEnganchados(vm: VistaModelo, onCerrar: () -> Unit) {
    Column(Modifier.padding(16.dp)) {
        Text("Tus enganchados", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(12.dp))

        LazyColumn(Modifier.heightIn(max = 340.dp)) {
            items(vm.enganchados) { n ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        { vm.abrir(n); onCerrar() },
                        Modifier.weight(1f),
                    ) { Text(n, color = if (n == vm.actual?.nombre) Acento else Tenue) }

                    // Doble toque: borrar se lleva la receta, los audios, la
                    // mezcla y el ZIP. Un toque distraido no puede costar eso.
                    var confirmando by remember(n) { mutableStateOf(false) }
                    LaunchedEffect(confirmando) {
                        if (confirmando) { kotlinx.coroutines.delay(3000); confirmando = false }
                    }
                    if (confirmando) {
                        TextButton({ vm.borrar(n) }) { Text("Borrar todo", color = Mal) }
                    } else {
                        IconButton({ confirmando = true }) {
                            Icon(Icons.Default.Delete, "Borrar", tint = Mal)
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = Borde)
        TextButton({ vm.actualizarYtDlp(); onCerrar() }, Modifier.fillMaxWidth()) {
            Text("Actualizar yt-dlp", color = Tenue)
        }
    }
}
