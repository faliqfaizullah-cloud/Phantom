package com.phantom.recorder

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

object Share {
    fun audio(c: Context, f: File) {
        val uri = FileProvider.getUriForFile(c, c.packageName + ".fileprovider", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, f.nameWithoutExtension)
            clipData = ClipData.newRawUri(f.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        c.startActivity(Intent.createChooser(i, "Share recording"))
    }

    fun text(c: Context, title: String, body: String) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, "$title\n\n$body")
        }
        c.startActivity(Intent.createChooser(i, "Share transcript"))
    }

    fun copy(c: Context, body: String) {
        c.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Phantom transcript", body))
        Toast.makeText(c, "Transcript copied", Toast.LENGTH_SHORT).show()
    }
}
