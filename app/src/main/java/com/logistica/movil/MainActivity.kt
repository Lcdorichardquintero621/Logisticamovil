@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.logistica.movil

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    Cat.DOCS to Color(0xFF29B6F6),
    Cat.OCULTOS to Color(0xFF607D8B)
)

private fun colorOf(c: Cat?): Color = c?.let { catColors[it] } ?: Color(0xFF607D8B)

private fun iconFor(c: Cat?): ImageVector = when (c) {
    Cat.FOTOS -> Icons.Default.Image
    Cat.VIDEOS -> Icons.Default.Videocam
    Cat.MUSICA -> Icons.Default.MusicNote
    Cat.DOCS -> Icons.Default.Description
    else -> Icons.Default.VisibilityOff
}

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
fun Logo(size: androidx.compose.ui.unit.Dp) {
    Surface(shape = RoundedCornerShape(22.dp), color = Color.White, shadowElevation = 4.dp) {
        Image(painterResource(R.drawable.ic_logo), null, Modifier.padding(size * 0.12f).size(size * 0.76f))
    }
}

@Composable
fun MainScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }

    var cats by remember { mutableStateOf(setOf(Cat.FOTOS)) }
    var results by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var groups by remember { mutableStateOf<List<List<FileItem>>>(emptyList()) }
    var dupMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var sort by remember { mutableStateOf(0) }
    var searched by remember { mutableStateOf(false) }
    var showMove by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val lines: List<Line> = remember(results, groups, dupMode, sort) {
        if (dupMode) groups.flatMapIndexed { i, g ->
            listOf<Line>(Line.Header("Grupo ${i + 1} · ${g.size} copias · ${Fx.fmtSize(g.first().size)} cada una")) +
                g.map { Line.Entry(it) }
        } else sortItems(results, sort).map { Line.Entry(it) }
    }
    val visible = remember(lines) { lines.filterIsInstance<Line.Entry>().map { it.item } }
    val extras = remember(groups) { groups.flatMap { it.drop(1) } }

    fun runSearch() {
        job?.cancel()
        selected = emptySet(); dupMode = false; groups = emptyList()
        job = scope.launch {
            busy = "Buscando archivos…"
            val r = Fx.scan(Environment.getExternalStorageDirectory(), cats) { n -> busy = "Buscando… $n encontrados" }
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
                                onClick = { visible.firstOrNull { it.path in selected }?.let { Fx.open(ctx, it) } },
                                enabled = selected.size == 1, modifier = Modifier.weight(1f)
                            ) { Icon(Icons.Default.OpenInNew, null); Spacer(Modifier.width(4.dp)); Text("Abrir") }
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

            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = navBottom + 16.dp)) {
                item {
                    Column(Modifier.padding(12.dp)) {
                        Text("¿Qué quieres buscar?", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Cat.values().forEach { c ->
                                val col = colorOf(c)
                                FilterChip(
                                    selected = c in cats,
                                    onClick = { cats = if (c in cats) cats - c else cats + c },
                                    label = { Text(c.label) },
                                    leadingIcon = { Icon(iconFor(c), null, tint = col, modifier = Modifier.size(18.dp)) },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = col.copy(alpha = .25f))
                                )
                            }
                        }
                        Text(
                            "Ocultos incluye cualquier archivo oculto (nombre o carpeta que empieza con punto), de cualquier formato.",
                            fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(top = 4.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { runSearch() }, enabled = cats.isNotEmpty() && busy == null,
                                modifier = Modifier.weight(1f)
                            ) { Icon(Icons.Default.Search, null); Spacer(Modifier.width(6.dp)); Text("Buscar") }
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

                items(lines, key = { l -> when (l) { is Line.Header -> "h" + l.text; is Line.Entry -> l.item.path } }) { line ->
                    when (line) {
                        is Line.Header -> Text(
                            line.text, fontWeight = FontWeight.Bold, color = Color(0xFF5B5BD6),
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp)
                        )
                        is Line.Entry -> FileRow(
                            item = line.item,
                            selected = line.item.path in selected,
                            onToggle = {
                                val p = line.item.path
                                selected = if (p in selected) selected - p else selected + p
                            },
                            onOpen = { Fx.open(ctx, line.item) }
                        )
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
}

@Composable
fun FileRow(item: FileItem, selected: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    val c = colorOf(item.cat)
    Card(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) c.copy(alpha = .15f) else Color.White),
        border = if (selected) BorderStroke(2.dp, c) else null,
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(c.copy(alpha = .2f)), contentAlignment = Alignment.Center) {
                Icon(iconFor(item.cat), null, tint = c)
            }
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
