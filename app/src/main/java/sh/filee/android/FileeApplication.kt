package sh.filee.android

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import sh.filee.android.core.conversion.JobQueue
import sh.filee.android.core.formats.FormatRegistry
import sh.filee.android.core.settings.UserDataStore
import sh.filee.android.engines.EngineRegistry
import java.io.File
import java.io.FileOutputStream

class FileeApplication : Application() {
    lateinit var dataStore: UserDataStore
        private set

    lateinit var engineRegistry: EngineRegistry
        private set

    lateinit var jobQueue: JobQueue
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        dataStore = UserDataStore(this)
        engineRegistry = EngineRegistry(this)
        val tempRoot = File(cacheDir, "filee_work").apply { mkdirs() }
        jobQueue = JobQueue(
            routePlanner = engineRegistry.routePlanner,
            tempRootDir = tempRoot,
            defaultOutputDir = dataStore.getDefaultOutputDirectory()
        )
    }

    companion object {
        lateinit var instance: FileeApplication
            private set
    }
}

/**
 * Helper for resolving Android `content://` and `file://` URIs into local files with their original
 * filenames and extensions intact, and sharing/opening output files via [FileProvider].
 */
object UriFileHelper {
    fun copyUrisToCache(context: Context, uris: List<Uri>): List<File> {
        val inboxDir = File(context.cacheDir, "shared_inbox_${System.currentTimeMillis()}").apply { mkdirs() }
        val result = mutableListOf<File>()
        for ((idx, uri) in uris.withIndex()) {
            runCatching {
                val fileName = resolveDisplayName(context, uri, idx)
                val destFile = File(inboxDir, fileName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
                if (destFile.exists() && destFile.length() > 0) {
                    result.add(destFile)
                }
            }
        }
        return result
    }

    private fun resolveDisplayName(context: Context, uri: Uri, index: Int): String {
        var name: String? = null
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (col >= 0) name = cursor.getString(col)
                    }
                }
            }
        }
        if (name.isNullOrBlank()) {
            name = uri.lastPathSegment?.substringAfterLast('/')
        }
        var cleanName = (name ?: "file_${index + 1}").replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (FormatRegistry.extensionOf(cleanName).isEmpty()) {
            val mime = context.contentResolver.getType(uri)
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "png"
            cleanName = "$cleanName.$ext"
        }
        return cleanName
    }

    fun openFile(context: Context, file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val ext = FormatRegistry.extensionOf(file.name)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun shareFiles(context: Context, files: List<File>) {
        if (files.isEmpty()) return
        runCatching {
            val uris = ArrayList(files.map {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
            })
            val intent = if (uris.size == 1) {
                val ext = FormatRegistry.extensionOf(files.first().name)
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
                Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            context.startActivity(
                Intent.createChooser(intent, "Share converted files").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
