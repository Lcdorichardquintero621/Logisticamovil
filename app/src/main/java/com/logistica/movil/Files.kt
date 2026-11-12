package com.logistica.movil

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Cat(val label: String) {
    FOTOS("Fotos"), VIDEOS("Videos"), MUSICA("Música"), DOCS("Documentos")
}

/** Una opción de búsqueda: un tipo de archivo, visible u oculto. */
data class Opt(val cat: Cat, val hidden: Boolean)

/** Grupos de la carpeta Media de WhatsApp. */
enum class WaGroup(val label: String) {
    VOZ("Notas de voz"), FOTOS("Fotos"), VIDEOS("Videos"), DOCS("Documentos"),
    STICKERS("Stickers"), AUDIOS("Audios"), GIFS("GIFs")
}

data class FileItem(
    val path: String,
    val name: String,
    val size: Long,
    val modified: Long,
    val ext: String,
    val cat: Cat?,
    val hidden: Boolean,
    val group: String? = null
) {
    val parent: String get() = File(path).parent ?: ""
}

object Fx {
    private val photos = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "dng", "tiff", "svg")
    private val videos = setOf("mp4", "mkv", "avi", "mov", "3gp", "webm", "flv", "wmv", "m4v", "mpeg", "mpg")
    private val music = setOf("mp3", "wav", "flac", "aac", "m4a", "ogg", "opus", "wma", "amr", "mid")
    private val docs = setOf(
        "pdf", "doc", "docx", "txt", "rtf", "odt", "md",
        "xls", "xlsx", "ods", "csv", "ppt", "pptx", "odp"
    )

    fun catOf(ext: String): Cat? = when (ext) {
        in photos -> Cat.FOTOS
        in videos -> Cat.VIDEOS
        in music -> Cat.MUSICA
        in docs -> Cat.DOCS
        else -> null
    }

    /** Busca archivos. Un archivo entra si su tipo y su condición (visible/oculto) están elegidos. */
    suspend fun scan(root: File, wanted: Set<Opt>, onProgress: (Int) -> Unit): List<FileItem> =
        withContext(Dispatchers.IO) {
            val out = ArrayList<FileItem>()

            fun walk(dir: File, hiddenCtx: Boolean, depth: Int) {
                if (depth > 40) return
                ensureActive()
                val list = dir.listFiles() ?: return
                for (f in list) {
                    if (f.isDirectory) {
                        if (dir.path.endsWith("/Android") && (f.name == "data" || f.name == "obb")) continue
                        walk(f, hiddenCtx || f.name.startsWith("."), depth + 1)
                    } else {
                        val ext = f.extension.lowercase()
                        val cat = catOf(ext) ?: continue
                        val hidden = hiddenCtx || f.name.startsWith(".")
                        if (Opt(cat, hidden) in wanted) {
                            out.add(FileItem(f.path, f.name, f.length(), f.lastModified(), ext, cat, hidden))
                            if (out.size % 50 == 0) onProgress(out.size)
                        }
                    }
                }
            }
            walk(root, false, 0)
            out
        }

    private fun waGroupOf(folder: String): WaGroup? {
        val n = folder.lowercase()
        return when {
            "voice" in n -> WaGroup.VOZ
            "sticker" in n -> WaGroup.STICKERS
            "animated" in n || "gif" in n -> WaGroup.GIFS
            "audio" in n -> WaGroup.AUDIOS
            "video" in n -> WaGroup.VIDEOS
            "image" in n -> WaGroup.FOTOS
            "document" in n -> WaGroup.DOCS
            else -> null
        }
    }

    /** Busca solo en la carpeta Media de WhatsApp (y WhatsApp Business), separada por grupos. */
    suspend fun scanWhatsApp(root: File, wanted: Set<WaGroup>, onProgress: (Int) -> Unit): List<FileItem> =
        withContext(Dispatchers.IO) {
            val out = ArrayList<FileItem>()
            val seen = HashSet<String>()
            val bases = listOf(
                "Android/media/com.whatsapp/WhatsApp/Media",
                "WhatsApp/Media",
                "Android/media/com.whatsapp.w4b/WhatsApp Business/Media",
                "WhatsApp Business/Media"
            )

            fun walk(dir: File, g: WaGroup, depth: Int) {
                if (depth > 10) return
                ensureActive()
                val list = dir.listFiles() ?: return
                for (f in list) {
                    if (f.isDirectory) {
                        walk(f, g, depth + 1)
                    } else if (f.name != ".nomedia") {
                        val key = try { f.canonicalPath } catch (e: Exception) { f.path }
                        if (!seen.add(key)) continue
                        val ext = f.extension.lowercase()
                        out.add(FileItem(f.path, f.name, f.length(), f.lastModified(), ext, catOf(ext), f.name.startsWith("."), g.label))
                        if (out.size % 50 == 0) onProgress(out.size)
                    }
                }
            }

            for (b in bases) {
                val dirs = File(root, b).listFiles() ?: continue
                for (d in dirs) {
                    if (!d.isDirectory) continue
                    val g = waGroupOf(d.name) ?: continue
                    if (g in wanted) walk(d, g, 0)
                }
            }
            out
        }

    /** Agrupa archivos idénticos (mismo tamaño y mismo contenido). Cada grupo queda ordenado: el más antiguo primero. */
    suspend fun findDuplicates(items: List<FileItem>, onProgress: (Int, Int) -> Unit): List<List<FileItem>> =
        withContext(Dispatchers.IO) {
            val bySize = items.filter { it.size > 0 }.groupBy { it.size }.filter { it.value.size > 1 }
            val total = bySize.values.sumOf { it.size }
            var done = 0
            val groups = ArrayList<List<FileItem>>()
            for ((_, sameSize) in bySize) {
                val byHash = HashMap<String, MutableList<FileItem>>()
                for (item in sameSize) {
                    ensureActive()
                    val h = hash(File(item.path))
                    if (h != null) byHash.getOrPut(h) { mutableListOf() }.add(item)
                    done++
                    onProgress(done, total)
                }
                byHash.values.filter { it.size > 1 }.forEach { g -> groups.add(g.sortedBy { it.modified }) }
            }
            groups.sortedByDescending { it.first().size * it.size }
        }

    private fun hash(f: File): String? = try {
        val md = MessageDigest.getInstance("SHA-1")
        f.inputStream().use { s ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = s.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        null
    }

    private fun uniqueDest(dir: File, name: String): File {
        var d = File(dir, name)
        if (!d.exists()) return d
        val base = d.nameWithoutExtension
        val ext = d.extension
        var i = 1
        while (true) {
            d = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
            if (!d.exists()) return d
            i++
        }
    }

    /** Mueve un archivo a una carpeta. Devuelve el archivo nuevo o null si falló. */
    fun move(src: File, dir: File): File? {
        val dest = uniqueDest(dir, src.name)
        return try {
            if (src.renameTo(dest)) dest
            else {
                src.copyTo(dest)
                if (src.delete()) dest else { dest.delete(); null }
            }
        } catch (e: Exception) {
            dest.delete()
            null
        }
    }

    fun rescan(ctx: Context, paths: List<String>) {
        if (paths.isNotEmpty()) MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null, null)
    }

    fun open(ctx: Context, item: FileItem) {
        try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", File(item.path))
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(item.ext) ?: "*/*"
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(ctx, "No hay una app para abrir este archivo", Toast.LENGTH_SHORT).show()
        }
    }

    fun fmtSize(b: Long): String {
        if (b < 1024) return "$b B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = b.toDouble()
        var i = -1
        while (v >= 1024 && i < 3) { v /= 1024; i++ }
        return String.format(Locale.getDefault(), "%.1f %s", v, units[i])
    }

    fun fmtDate(ms: Long): String =
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(ms))
}
