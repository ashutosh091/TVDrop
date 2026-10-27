package dev.ashutosh.tvdrop

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.MimeTypeMap
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** File list on the TV: OK on a file → install (APK) or open, or delete. */
class MainActivity : Activity() {
    private lateinit var adapter: Adapter
    private var pending: File? = null   // APK whose installer is showing
    private val listener = { runOnUiThread { refresh() } }

    // Backup for boxes whose installer doesn't return a result: the package appearing means it worked.
    private val installed = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val file = pending ?: return
            val pkg = intent.data?.schemeSpecificPart ?: return
            if (pkg == packageOf(file)) { pending = null; askDelete(file) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<TextView>(R.id.address).text = Server.address() ?: getString(R.string.no_network)

        val url = findViewById<EditText>(R.id.url)
        url.setOnEditorActionListener { v, action, _ ->
            val text = v.text.toString().trim()
            if (action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_NULL) {
                if (text.startsWith("http")) { Downloads.download(text); v.text = "" }
                true
            } else false
        }

        adapter = Adapter()
        val list = findViewById<ListView>(R.id.list)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> choose(adapter.getItem(position)!!) }

        registerReceiver(installed, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        })
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        intent.getStringExtra(EXTRA_OPEN)?.let { name ->
            intent.removeExtra(EXTRA_OPEN)
            Downloads.get(name)?.takeIf { it.status == Downloads.Status.DONE }?.let { open(it) }
        }
        if (intent.action == Intent.ACTION_VIEW) intent.dataString?.takeIf { it.startsWith("http") }?.let {
            intent.data = null
            Downloads.download(it)
        }
    }

    override fun onResume() {
        super.onResume()
        Downloads.listen(listener)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        Downloads.unlisten(listener)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(installed)
    }

    private fun refresh() {
        val items = Downloads.list()
        adapter.setNotifyOnChange(false)
        adapter.clear()
        adapter.addAll(items)
        adapter.notifyDataSetChanged()
        findViewById<View>(R.id.empty).visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        val list = findViewById<ListView>(R.id.list)
        if (items.isNotEmpty() && !list.hasFocus() && !findViewById<View>(R.id.url).hasFocus()) list.requestFocus()
    }

    private fun choose(item: Downloads.Item) {
        val actions = when {
            item.busy -> listOf(getString(R.string.cancel) to { Downloads.cancel(item.name) })
            item.status == Downloads.Status.FAILED -> listOf(getString(R.string.delete) to { Downloads.delete(item.name); Unit })
            else -> listOf(
                getString(if (item.isApk) R.string.install else R.string.open) to { open(item) },
                getString(R.string.delete) to { Downloads.delete(item.name); Unit },
            )
        }
        AlertDialog.Builder(this).setTitle(item.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
            .show()
    }

    private fun open(item: Downloads.Item) {
        val file = item.file
        if (!file.isFile) return
        if (item.isApk) install(file) else {
            val ext = file.extension.lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri(file), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }.onFailure { toast("No app can open .$ext") }
        }
    }

    private fun install(file: File) {
        pending = file
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            toast(getString(R.string.allow_sources))
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")), REQ_SOURCES
            )
            return
        }
        @Suppress("DEPRECATION")
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setData(uri(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            .putExtra(Intent.EXTRA_RETURN_RESULT, true)
        runCatching { startActivityForResult(intent, REQ_INSTALL) }.onFailure { toast(getString(R.string.install_failed)) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val file = pending ?: return
        when (requestCode) {
            REQ_SOURCES -> if (Build.VERSION.SDK_INT >= 26 && packageManager.canRequestPackageInstalls()) install(file)
            REQ_INSTALL -> when (resultCode) {
                RESULT_OK -> { pending = null; askDelete(file) }
                RESULT_CANCELED -> { pending = null; toast(getString(R.string.install_cancelled)) }
                else -> { pending = null; toast(getString(R.string.install_failed)) }
            }
        }
    }

    private fun askDelete(file: File) {
        if (!file.isFile) return
        val label = runCatching {
            packageManager.getPackageArchiveInfo(file.path, 0)?.applicationInfo?.let {
                it.sourceDir = file.path; it.publicSourceDir = file.path
                packageManager.getApplicationLabel(it).toString()
            }
        }.getOrNull() ?: file.name
        val prefs = getSharedPreferences("tvdrop", MODE_PRIVATE)
        val delete = { Downloads.delete(file.name); toast(getString(R.string.deleted, file.name)) }
        if (prefs.getBoolean("autoDelete", false)) return delete()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.installed_title, label))
            .setMessage(getString(R.string.installed_msg, file.name, Downloads.human(file.length())))
            .setPositiveButton(R.string.delete) { _, _ -> delete() }
            .setNeutralButton(R.string.always_delete) { _, _ -> prefs.edit().putBoolean("autoDelete", true).apply(); delete() }
            .setNegativeButton(R.string.keep, null)
            .show()
    }

    private fun packageOf(file: File): String? =
        runCatching { packageManager.getPackageArchiveInfo(file.path, 0)?.packageName }.getOrNull()

    private fun uri(file: File): Uri = FileProvider.getUriForFile(this, "$packageName.files", file)

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private inner class Adapter : ArrayAdapter<Downloads.Item>(this, R.layout.item_file) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_file, parent, false)
            val item = getItem(position)!!
            view.findViewById<TextView>(R.id.name).text = item.name
            view.findViewById<TextView>(R.id.sub).text = when (item.status) {
                Downloads.Status.DOWNLOADING, Downloads.Status.UPLOADING ->
                    getString(if (item.status == Downloads.Status.UPLOADING) R.string.uploading else R.string.downloading) +
                        "  ${Downloads.human(item.done)}" + (if (item.total > 0) " / ${Downloads.human(item.total)}" else "")
                Downloads.Status.FAILED -> getString(R.string.failed, item.error)
                Downloads.Status.DONE -> Downloads.human(item.total)
            }
            val bar = view.findViewById<ProgressBar>(R.id.bar)
            bar.visibility = if (item.busy) View.VISIBLE else View.GONE
            bar.isIndeterminate = item.busy && item.total <= 0
            bar.progress = item.percent()
            return view
        }
    }

    companion object {
        private const val EXTRA_OPEN = "open"
        private const val REQ_INSTALL = 1
        private const val REQ_SOURCES = 2

        /** Intent that brings the app up and starts installing/opening [name] (used by the web page). */
        fun open(ctx: Context, name: String): Intent =
            Intent(ctx, MainActivity::class.java).putExtra(EXTRA_OPEN, name)
    }
}
