@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.logistica.movil

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private val granted = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        granted.value = hasAccess()
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF5B5BD6),
                    secondary = Color(0xFF8E54E9),
                    tertiary = Color(0xFFFF6FA5)
                )
            ) {
                if (granted.value) MainScreen() else PermissionScreen { requestAccess() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        granted.value = hasAccess()
    }

    private fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                1
            )
        }
    }
}

private val catColors = mapOf(
    Cat.FOTOS to Color(0xFFFF6FA5),
    Cat.VIDEOS to Color(0xFF8E54E9),
    Cat.MUSICA to Color(0xFFFFA726),
    Cat.DOCS to Color(0xFF29B6F6)
)

private fun colorOf(c: Cat?): Color = c?.let { catColors[it] } ?: Color(0xFF607D8B)

private fun iconFor(c: Cat?): ImageVector = when (c) {
    Cat.FOTOS -> Icons.Default.Image
    Cat.VIDEOS -> Icons.Default.Videocam
    Cat.MUSICA -> Icons.Default.MusicNote
    else -> Icons.Default.Description
}

private fun waColor(g: WaGroup): Color = when (g) {
    WaGroup.VOZ -> Color(0xFF25D366)
    WaGroup.FOTOS -> Color(0xFFFF6FA5)
    WaGroup.VIDEOS -> Color(0xFF8E54E9)
    WaGroup.DOCS -> Color(0xFF29B6F6)
    WaGroup.STICKERS -> Color(0xFFFFA726)
    WaGroup.AUDIOS -> Color(0xFF26A69A)
    WaGroup.GIFS -> Color(0xFFEF5350)
}

private fun waIcon(g: WaGroup): ImageVector = when (g) {
    WaGroup.VOZ -> Icons.Default.Mic
    WaGroup.FOTOS -> Icons.Default.Image
    WaGroup.VIDEOS -> Icons.Default.Videocam
    WaGroup.DOCS -> Icons.Default.Description
    WaGroup.STICKERS -> Icons.Default.EmojiEmotions
    WaGroup.AUDIOS -> Icons.Default.MusicNote
    WaGroup.GIFS -> Icons.Default.Gif
}

private fun <T> Set<T>.toggle(x: T): Set<T> = if (x in this) this - x else this + x

private sealed class Line {
    data class Header(val text: String) : Line()
    data class Entry(val item: FileItem) : Line()
}

private fun sortItems(l: List<FileItem>, s: Int): List<FileItem> = when (s) {
    1 -> l.sortedBy { it.name.lowercase() }
    2 -> l.sortedByDescending { it.size }
    else -> l.sortedByDescending { it.modified }
}

@Composable
fun PermissionScreen(onGrant: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFFF6F5FF)).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Logo(110.dp)
        Spacer(Modifier.height(20.dp))
        Text("LogisticaMovil", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            "Para encontrar y organizar tus fotos, videos, música, documentos y archivos ocultos, la app necesita permiso para acceder a todos los archivos del teléfono.",
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onGrant) { Text("Conceder acceso") }
    }
}

@Composable
fun Logo(size: Dp) {
    Surface(shape = RoundedCornerShape(22.dp), color = Color.White, shadowElevation = 4.dp) {
        Image(painterResource(R.drawable.ic_logo), null, Modifier.padding(size * 0.12f).size(size * 0.76f))
    }
}

@Composable
fun MainScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }

    var mode by remember { mutableStateOf(0) } // 0 = por tipo, 1 = WhatsApp
    var opts by remember { mutableStateOf(setOf(Opt(Cat.FOTOS, false))) }
    var waSel by remember { mutableStateOf(WaGroup.values().toSet()) }
    var results by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var groups by remember { mutableStateOf<List<List<FileItem>>>(emptyList()) }
    var dupMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var sort by remember { mutableStateOf(0) }
    var grid by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var showMove by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<FileItem?>(null) }

    val lines: List<Line> = remember(results, groups, dupMode, sort, mode) {
        when {
            dupMode -> groups.flatMapIndexed { i, g ->
                listOf<Line>(Line.Header("Grupo ${i + 1} · ${g.size} copias · ${Fx.fmtSize(g.first().size)} cada una")) +
                    g.map { Line.Entry(it) }
            }
            mode == 1 -> {
                val out = ArrayList<Line>()
                WaGroup.values().forEach { g ->
                    val its = results.filter { it.group == g.label }
                    if (its.isNotEmpty()) {
                        out.add(Line.Header("${g.label} · ${its.size} archivos · ${Fx.fmtSize(its.sumOf { it.size })}"))
                        sortItems(its, sort).forEach { out.add(Line.Entry(it)) }
                    }
                }
                out
            }
            else -> sortItems(results, sort).map { Line.Entry(it) }
        }
    }
    val visible = remember(lines) { lines.filterIsInstance<Line.Entry>().map { it.item } }
    val extras = remember(groups) { groups.flatMap { it.drop(1) } }

    fun switchMode(m: Int) {
        job?.cancel()
        mode = m; results = emptyList(); groups = emptyList(); dupMode = false
        selected = emptySet(); busy = null; searched = false
    }

    fun runSearch() {
        job?.cancel()
        selected = emptySet(); dupMode = false; groups = emptyList()
        job = scope.launch {
            busy = "Buscando archivos…"
            val root = Environment.getExternalStorageDirectory()
            val r = if (mode == 0) Fx.scan(root, opts) { n -> busy = "Buscando… $n encontrados" }
            else Fx.scanWhatsApp(root, waSel) { n -> busy = "Buscando… $n encontrados" }
            results = r; searched = true; busy = null
        }
    }

    fun runDuplicates() {
        job?.cancel()
        job = scope.launch {
            busy = "Analizando duplicados…"
            val g = Fx.findDuplicates(results) { d, t -> busy = "Comparando archivos… $d de $t" }
            groups = g; dupMode = g.isNotEmpty(); selected = emptySet(); busy = null
            snack.showSnackbar(if (g.isEmpty()) "No se encontraron duplicados" else "${g.size} grupos de duplicados encontrados")
        }
    }

    fun doDelete() {
        var paths = selected
        var kept = 0
        if (dupMode) {
            val fullySelected = groups.filter { g -> g.all { it.path in paths } }
            kept = fullySelected.size
            paths = paths - fullySelected.map { it.first().path }.toSet()
        }
        val toDelete = paths
        scope.launch {
            busy = "Eliminando…"
            val ok = withContext(Dispatchers.IO) { toDelete.filter { File(it).delete() } }.toSet()
            Fx.rescan(ctx, ok.toList())
            results = results.filter { it.path !in ok }
            groups = groups.map { g -> g.filter { it.path !in ok } }.filter { it.size > 1 }
            if (dupMode && groups.isEmpty()) dupMode = false
            selected = emptySet(); busy = null
            snack.showSnackbar(
                "${ok.size} eliminados" + if (kept > 0) " · se conservó una copia en $kept grupos" else ""
            )
        }
    }

    fun doMove(dir: File) {
        val paths = selected
        scope.launch {
            busy = "Moviendo…"
            val moved: Map<String, File> = withContext(Dispatchers.IO) {
                paths.mapNotNull { p -> Fx.move(File(p), dir)?.let { p to it } }.toMap()
            }
            fun upd(i: FileItem): FileItem = moved[i.path]?.let { i.copy(path = it.path, name = it.name) } ?: i
            results = results.map { upd(it) }
            groups = groups.map { g -> g.map { upd(it) } }
            Fx.rescan(ctx, moved.keys.toList() + moved.values.map { it.path })
            selected = emptySet(); busy = null
            snack.showSnackbar("${moved.size} movidos a ${dir.name}")
        }
    }

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val canSearch = busy == null && (if (mode == 0) opts.isNotEmpty() else waSel.isNotEmpty())

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            AnimatedVisibility(selected.isNotEmpty()) {
                Surface(tonalElevation = 8.dp, shadowElevation = 8.dp) {
                    Column(Modifier.navigationBarsPadding().padding(8.dp)) {
                        Text("${selected.size} seleccionados", fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = { visible.firstOrNull { it.path in selected }?.let { preview = it } },
                                enabled = selected.size == 1, modifier = Modifier.weight(1f)
                            ) { Icon(Icons.Default.Visibility, null); Spacer(Modifier.width(4.dp)); Text("Ver") }
                            FilledTonalButton(onClick = { showMove = true }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.DriveFileMove, null); Spacer(Modifier.width(4.dp)); Text("Mover")
                            }
                            Button(
                                onClick = { confirmDelete = true },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                                modifier = Modifier.weight(1f)
                            ) { Icon(Icons.Default.Delete, null); Spacer(Modifier.width(4.dp)); Text("Borrar") }
                        }
                    }
                }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().background(Color(0xFFF6F5FF))) {
            Box(
                Modifier.fillMaxWidth()
                    .background(Brush.horizontalGradient(listOf(Color(0xFF5B5BD6), Color(0xFF8E54E9), Color(0xFFFF6FA5))))
                    .statusBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Logo(64.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("LogisticaMovil", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text("Encuentra, organiza y limpia tus archivos", color = Color.White.copy(alpha = .9f), fontSize = 13.sp)
                    }
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(if (grid) 3 else 1),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = navBottom + 16.dp)
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                        TabRow(selectedTabIndex = mode, containerColor = Color.Transparent) {
                            Tab(selected = mode == 0, onClick = { switchMode(0) }, text = { Text("Por tipo") })
                            Tab(selected = mode == 1, onClick = { switchMode(1) }, text = { Text("WhatsApp") })
                        }
                        Spacer(Modifier.height(10.dp))

                        if (mode == 0) {
                            Text("¿Qué quieres buscar?", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Cat.values().forEach { c ->
                                val col = colorOf(c)
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                                    Box(Modifier.size(32.dp).clip(CircleShape).background(col.copy(alpha = .2f)), contentAlignment = Alignment.Center) {
                                        Icon(iconFor(c), null, tint = col, modifier = Modifier.size(18.dp))
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(c.label, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    FilterChip(
                                        selected = Opt(c, false) in opts,
                                        onClick = { opts = opts.toggle(Opt(c, false)) },
                                        label = { Text("Visibles") },
                                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = col.copy(alpha = .25f))
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    FilterChip(
                                        selected = Opt(c, true) in opts,
                                        onClick = { opts = opts.toggle(Opt(c, true)) },
                                        label = { Text("Ocultas") },
                                        leadingIcon = { Icon(Icons.Default.VisibilityOff, null, modifier = Modifier.size(16.dp)) },
                                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = col.copy(alpha = .25f))
                                    )
                                }
                            }
                            Text(
                                "Ocultas: archivos cuyo nombre o carpeta empieza con punto.",
                                fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(top = 2.dp)
                            )
                        } else {
                            Text("Carpeta Media de WhatsApp", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Spacer(Modifier.height(6.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                WaGroup.values().forEach { g ->
                                    val col = waColor(g)
                                    FilterChip(
                                        selected = g in waSel,
                                        onClick = { waSel = waSel.toggle(g) },
                                        label = { Text(g.label) },
                                        leadingIcon = { Icon(waIcon(g), null, tint = col, modifier = Modifier.size(18.dp)) },
                                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = col.copy(alpha = .25f))
                                    )
                                }
                            }
                            Text(
                                "Los enlaces no se guardan como archivos en la carpeta de WhatsApp, por eso no aparecen aquí.",
                                fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(top = 4.dp)
                            )
                        }

                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { runSearch() }, enabled = canSearch, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Search, null); Spacer(Modifier.width(6.dp)); Text("Buscar")
                            }
                            OutlinedButton(
                                onClick = { runDuplicates() }, enabled = results.isNotEmpty() && busy == null,
                                modifier = Modifier.weight(1f)
                            ) { Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Duplicados") }
                        }

                        busy?.let { msg ->
                            Card(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(msg)
                                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                                    TextButton(onClick = { job?.cancel(); busy = null }) { Text("Cancelar") }
                                }
                            }
                        }

                        if (dupMode) {
                            Card(
                                Modifier.fillMaxWidth().padding(top = 10.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("Duplicados: ${groups.size} grupos · ${extras.size} copias sobrantes (${Fx.fmtSize(extras.sumOf { it.size })})", fontWeight = FontWeight.Bold)
                                    Text("Siempre se conserva una copia de cada grupo (la más antigua).", fontSize = 12.sp)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                        Button(
                                            onClick = { selected = extras.map { it.path }.toSet(); confirmDelete = true },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                                        ) { Text("Eliminar copias extra") }
                                        TextButton(onClick = { dupMode = false; selected = emptySet() }) { Text("Ver todos") }
                                    }
                                }
                            }
                        }

                        if (visible.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Text("${visible.size} archivos · ${Fx.fmtSize(visible.sumOf { it.size })}", fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { selected = visible.map { it.path }.toSet() }) {
                                    Icon(Icons.Default.SelectAll, null); Spacer(Modifier.width(4.dp)); Text("Todo")
                                }
                                TextButton(onClick = { selected = emptySet() }) {
                                    Icon(Icons.Default.Close, null); Spacer(Modifier.width(4.dp)); Text("Ninguno")
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Vista:", fontSize = 13.sp)
                                FilterChip(selected = !grid, onClick = { grid = false }, label = { Text("Lista") })
                                FilterChip(selected = grid, onClick = { grid = true }, label = { Text("Miniaturas") })
                            }
                            if (!dupMode) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Ordenar:", fontSize = 13.sp)
                                    listOf("Fecha", "Nombre", "Tamaño").forEachIndexed { i, t ->
                                        FilterChip(selected = sort == i, onClick = { sort = i }, label = { Text(t) })
                                    }
                                }
                            }
                        } else if (busy == null) {
                            Text(
                                if (searched) "No se encontraron archivos con esa selección." else "Elige qué buscar y toca Buscar.",
                                color = Color.Gray, modifier = Modifier.padding(top = 24.dp).fillMaxWidth(), textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                items(
                    lines,
                    key = { l -> when (l) { is Line.Header -> "h" + l.text; is Line.Entry -> l.item.path } },
                    span = { l -> if (grid && l is Line.Entry) GridItemSpan(1) else GridItemSpan(maxLineSpan) }
                ) { line ->
                    when (line) {
                        is Line.Header -> Text(
                            line.text, fontWeight = FontWeight.Bold, color = Color(0xFF5B5BD6),
                            modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp)
                        )
                        is Line.Entry -> {
                            val p = line.item.path
                            val toggle = { selected = if (p in selected) selected - p else selected + p }
                            if (grid) GridCell(line.item, p in selected, toggle) { preview = line.item }
                            else FileRow(line.item, p in selected, toggle, { preview = line.item }) { Fx.open(ctx, line.item) }
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("¿Eliminar ${selected.size} archivo(s)?") },
            text = {
                Text(
                    "Esta acción no se puede deshacer." +
                        if (dupMode) "\nSe conservará al menos una copia de cada grupo de duplicados." else ""
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; doDelete() }) { Text("Eliminar", color = Color(0xFFE53935)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } }
        )
    }

    if (showMove) {
        FolderPicker(onDismiss = { showMove = false }, onPick = { showMove = false; doMove(it) })
    }

    preview?.let { item ->
        PreviewDialog(item, onClose = { preview = null }, onOpen = { Fx.open(ctx, item) })
    }
}

@Composable
fun Thumb(item: FileItem, modifier: Modifier) {
    val ctx = LocalContext.current
    val c = colorOf(item.cat)
    if (item.cat == Cat.FOTOS || item.cat == Cat.VIDEOS) {
        val req = remember(item.path) { ImageRequest.Builder(ctx).data(File(item.path)).size(300).crossfade(true).build() }
        Box(modifier.background(c.copy(alpha = .15f)), contentAlignment = Alignment.Center) {
            AsyncImage(model = req, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (item.cat == Cat.VIDEOS) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    } else {
        Box(modifier.background(c.copy(alpha = .2f)), contentAlignment = Alignment.Center) {
            Icon(iconFor(item.cat), null, tint = c, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
fun FileRow(item: FileItem, selected: Boolean, onToggle: () -> Unit, onPreview: () -> Unit, onOpen: () -> Unit) {
    val c = colorOf(item.cat)
    Card(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) c.copy(alpha = .15f) else Color.White),
        border = if (selected) BorderStroke(2.dp, c) else null,
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(item, Modifier.size(60.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onPreview))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (item.ext.uppercase().ifEmpty { "—" }) + " · " + Fx.fmtSize(item.size) + " · " +
                        Fx.fmtDate(item.modified) + if (item.hidden) " · oculto" else "",
                    fontSize = 12.sp
                )
                Text(item.parent, fontSize = 11.sp, color = Color.Gray, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
            IconButton(onClick = onOpen) { Icon(Icons.Default.OpenInNew, "Abrir", tint = c) }
        }
    }
}

@Composable
fun GridCell(item: FileItem, selected: Boolean, onToggle: () -> Unit, onPreview: () -> Unit) {
    val c = colorOf(item.cat)
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.padding(3.dp).aspectRatio(1f).clip(shape)
            .then(if (selected) Modifier.border(3.dp, c, shape) else Modifier)
            .clickable(onClick = onToggle)
    ) {
        Thumb(item, Modifier.fillMaxSize())
        Text(
            item.name, color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 4.dp, vertical = 2.dp)
        )
        Icon(
            if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
            tint = if (selected) c else Color.White,
            modifier = Modifier.align(Alignment.TopStart).padding(4.dp).size(22.dp)
                .background(if (selected) Color.White else Color(0x55000000), CircleShape)
        )
        Box(
            Modifier.align(Alignment.TopEnd).padding(4.dp).size(26.dp).clip(CircleShape)
                .background(Color(0x99000000)).clickable(onClick = onPreview),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.Visibility, "Vista previa", tint = Color.White, modifier = Modifier.size(16.dp)) }
    }
}

@Composable
fun PreviewDialog(item: FileItem, onClose: () -> Unit, onOpen: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF111111)) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(item.name, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            item.ext.uppercase() + " · " + Fx.fmtSize(item.size) + " · " + Fx.fmtDate(item.modified),
                            color = Color(0xFFBBBBBB), fontSize = 12.sp
                        )
                    }
                    TextButton(onClick = onOpen) { Text("Abrir") }
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Cerrar", tint = Color.White) }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when {
                        item.cat == Cat.FOTOS -> AsyncImage(
                            model = File(item.path), contentDescription = null,
                            contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()
                        )
                        item.cat == Cat.VIDEOS -> VideoPreview(item.path)
                        item.cat == Cat.MUSICA -> AudioPreview(item)
                        item.ext == "pdf" -> PdfPreview(item.path)
                        item.ext == "txt" || item.ext == "csv" || item.ext == "md" -> TextPreview(item.path)
                        else -> Text(
                            "Sin vista previa para este formato.\nToca Abrir para verlo con otra app.",
                            color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun VideoPreview(path: String) {
    val holder = remember { arrayOfNulls<VideoView>(1) }
    DisposableEffect(path) { onDispose { try { holder[0]?.stopPlayback() } catch (e: Exception) { } } }
    AndroidView(
        factory = { c ->
            VideoView(c).apply {
                val mc = MediaController(c)
                mc.setAnchorView(this)
                setMediaController(mc)
                setVideoPath(path)
                setOnPreparedListener { start() }
                holder[0] = this
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
fun AudioPreview(item: FileItem) {
    val mp = remember { MediaPlayer() }
    var ready by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var pos by remember { mutableStateOf(0f) }
    DisposableEffect(item.path) {
        try {
            mp.setDataSource(item.path)
            mp.setOnPreparedListener { ready = true }
            mp.setOnCompletionListener { playing = false; pos = 0f }
            mp.setOnErrorListener { _, _, _ -> failed = true; true }
            mp.prepareAsync()
        } catch (e: Exception) {
            failed = true
        }
        onDispose { try { mp.release() } catch (e: Exception) { } }
    }
    LaunchedEffect(playing) {
        while (playing) {
            try {
                val d = mp.duration
                if (d > 0) pos = mp.currentPosition.toFloat() / d
            } catch (e: Exception) { }
            delay(250)
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
        Icon(Icons.Default.MusicNote, null, tint = Color(0xFFFFA726), modifier = Modifier.size(96.dp))
        Text(item.name, color = Color.White, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        if (failed) {
            Text("No se pudo reproducir este audio aquí. Prueba con Abrir.", color = Color.White, textAlign = TextAlign.Center)
        } else {
            FilledIconButton(
                onClick = {
                    if (playing) { mp.pause(); playing = false } else { mp.start(); playing = true }
                },
                enabled = ready, modifier = Modifier.size(64.dp)
            ) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(progress = { pos }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun PdfPreview(path: String) {
    var page by remember { mutableStateOf(0) }
    var count by remember { mutableStateOf(1) }
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(path, page) {
        val r: Pair<Int, Bitmap>? = withContext(Dispatchers.IO) {
            try {
                val fd = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
                val rd = PdfRenderer(fd)
                try {
                    val n = rd.pageCount
                    val p = rd.openPage(page.coerceIn(0, n - 1))
                    val b = Bitmap.createBitmap(p.width * 2, p.height * 2, Bitmap.Config.ARGB_8888)
                    b.eraseColor(android.graphics.Color.WHITE)
                    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    p.close()
                    n to b
                } finally {
                    rd.close()
                    fd.close()
                }
            } catch (e: Exception) {
                null
            }
        }
        if (r == null) failed = true else { count = r.first; bmp = r.second }
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val b = bmp
            if (failed) Text("No se pudo mostrar este PDF. Prueba con Abrir.", color = Color.White)
            else if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else CircularProgressIndicator()
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (page > 0) page-- }, enabled = page > 0) { Text("◀ Anterior") }
            Text("Página ${page + 1} de $count", color = Color.White)
            TextButton(onClick = { if (page < count - 1) page++ }, enabled = page < count - 1) { Text("Siguiente ▶") }
        }
    }
}

@Composable
fun TextPreview(path: String) {
    var text by remember { mutableStateOf("Cargando…") }
    LaunchedEffect(path) {
        text = withContext(Dispatchers.IO) {
            try {
                File(path).bufferedReader().use { r ->
                    val buf = CharArray(6000)
                    val n = r.read(buf)
                    if (n > 0) String(buf, 0, n) else "(archivo vacío)"
                }
            } catch (e: Exception) {
                "No se pudo leer el archivo"
            }
        }
    }
    Text(
        text, color = Color.White, fontSize = 13.sp,
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)
    )
}

@Composable
fun FolderPicker(onDismiss: () -> Unit, onPick: (File) -> Unit) {
    val root = Environment.getExternalStorageDirectory()
    var cur by remember { mutableStateOf(root) }
    var refresh by remember { mutableStateOf(0) }
    var newName by remember { mutableStateOf("") }
    val dirs = remember(cur, refresh) {
        cur.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mover a…") },
        text = {
            Column {
                Text(cur.path, fontSize = 12.sp, color = Color.Gray)
                LazyColumn(Modifier.height(260.dp)) {
                    if (cur.path != root.path) {
                        item {
                            Row(
                                Modifier.fillMaxWidth().clickable { cur = cur.parentFile ?: root }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) { Icon(Icons.Default.ArrowUpward, null); Spacer(Modifier.width(8.dp)); Text("Subir un nivel") }
                        }
                    }
                    items(dirs, key = { it.path }) { d ->
                        Row(
                            Modifier.fillMaxWidth().clickable { cur = d }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Folder, null, tint = Color(0xFFFFB300))
                            Spacer(Modifier.width(8.dp))
                            Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName, onValueChange = { newName = it }, singleLine = true,
                        label = { Text("Nueva carpeta") }, modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        if (newName.isNotBlank() && File(cur, newName.trim()).mkdirs()) { newName = ""; refresh++ }
                    }) { Icon(Icons.Default.CreateNewFolder, "Crear") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(cur) }) { Text("Mover aquí") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}
