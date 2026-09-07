package me.rerere.rikkahub.importadapter

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var statusView: TextView
    private lateinit var exportButton: Button
    private var convertedFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val title = TextView(this).apply {
            text = getString(R.string.title)
            textSize = 24f
            setTextColor(0xff202124.toInt())
        }
        val description = TextView(this).apply {
            text = getString(R.string.description)
            textSize = 16f
            setPadding(0, 20, 0, 24)
        }
        val selectButton = Button(this).apply {
            text = getString(R.string.select_backup)
            setOnClickListener { openBackupPicker() }
        }
        exportButton = Button(this).apply {
            text = getString(R.string.export_package)
            isEnabled = false
            setOnClickListener { createOutputFile() }
        }
        statusView = TextView(this).apply {
            text = getString(R.string.waiting)
            textSize = 15f
            setPadding(0, 24, 0, 0)
        }

        content.addView(title, matchParentWrapContent())
        content.addView(description, matchParentWrapContent())
        content.addView(selectButton, matchParentWrapContent())
        content.addView(exportButton, matchParentWrapContent())
        content.addView(statusView, matchParentWrapContent())

        setContentView(ScrollView(this).apply {
            addView(content)
        })
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Activity result API is sufficient for the small standalone adapter.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQUEST_SOURCE -> data?.data?.let(::convertSource)
            REQUEST_OUTPUT -> data?.data?.let(::saveOutput)
        }
    }

    private fun openBackupPicker() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*"),
            REQUEST_SOURCE,
        )
    }

    private fun convertSource(uri: Uri) {
        statusView.text = getString(R.string.converting)
        exportButton.isEnabled = false
        executor.execute {
            runCatching {
                val source = File.createTempFile("rikkahub-source-", ".bin", cacheDir)
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        source.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("Cannot read selected file")
                    val output = File(cacheDir, "rikkahub-transfer-${System.currentTimeMillis()}.rhk")
                    try {
                        val summary = RikkaHubTransferWriter.convert(source, output)
                        output to summary
                    } catch (error: Throwable) {
                        output.delete()
                        throw error
                    }
                } finally {
                    source.delete()
                }
            }.onSuccess { (output, summary) ->
                runOnUiThread {
                    convertedFile = output
                    exportButton.isEnabled = true
                    statusView.text = if (summary.warningCount + summary.errorCount == 0) {
                        getString(R.string.converted)
                    } else {
                        getString(
                            R.string.converted_with_warnings,
                            summary.conversationCount,
                            summary.warningCount,
                            summary.errorCount,
                        )
                    }
                }
            }.onFailure { error ->
                runOnUiThread {
                    statusView.text = getString(R.string.failed, error.message ?: error.javaClass.simpleName)
                }
            }
        }
    }

    private fun createOutputFile() {
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip")
                .putExtra(Intent.EXTRA_TITLE, "rikkahub-transfer-${System.currentTimeMillis()}.rhk"),
            REQUEST_OUTPUT,
        )
    }

    private fun saveOutput(uri: Uri) {
        val source = convertedFile ?: return
        runCatching {
            contentResolver.openOutputStream(uri)?.use { output ->
                FileInputStream(source).use { input -> input.copyTo(output) }
            } ?: error("Cannot write selected destination")
        }.onSuccess {
            statusView.text = getString(R.string.saved)
        }.onFailure { error ->
            statusView.text = getString(R.string.failed, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun matchParentWrapContent() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    companion object {
        private const val REQUEST_SOURCE = 10
        private const val REQUEST_OUTPUT = 11
    }
}
