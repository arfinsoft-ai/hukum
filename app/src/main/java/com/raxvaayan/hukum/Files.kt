package com.raxvaayan.hukum

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/** Minimal file provider: content://<pkg>.files/{cache|vault}/<name> → app-private file (for camera output + WhatsApp share). */
class Files : ContentProvider() {
    companion object {
        fun uri(c: Context, f: File): Uri {
            val dir = if (f.parentFile?.name == "vault") "vault" else "cache"
            return Uri.parse("content://${c.packageName}.files/$dir/${Uri.encode(f.name)}")
        }
    }

    private fun file(u: Uri): File {
        val c = context!!; val seg = u.pathSegments
        require(seg.size == 2 && !seg[1].contains("/") && !seg[1].contains("..")) { "bad path" }
        val base = if (seg[0] == "vault") File(c.filesDir, "vault") else c.cacheDir
        return File(base, seg[1])
    }

    override fun onCreate() = true
    override fun getType(u: Uri) = "image/jpeg"
    override fun openFile(u: Uri, mode: String): ParcelFileDescriptor {
        val f = file(u)
        val m = if (mode.contains("w")) ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
        else ParcelFileDescriptor.MODE_READ_ONLY
        return ParcelFileDescriptor.open(f, m)
    }
    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor {
        val f = file(u)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(f.name, f.length())) }
    }
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
