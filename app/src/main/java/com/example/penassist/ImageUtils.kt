package com.example.penassist

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ImageUtils {

    private fun shotsDir(ctx: Context): File = File(ctx.cacheDir, "shots").apply { mkdirs() }

    /** Save a bitmap to the app cache (for passing between screens / sharing). */
    fun saveTemp(ctx: Context, bmp: Bitmap, prefix: String = "shot"): File {
        val f = File(shotsDir(ctx), "${prefix}_${System.currentTimeMillis()}.png")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    /** Remove cached screenshots older than 6 hours. */
    fun cleanOld(ctx: Context) {
        val limit = System.currentTimeMillis() - 6 * 60 * 60 * 1000L
        shotsDir(ctx).listFiles()?.filter { it.lastModified() < limit }?.forEach { it.delete() }
    }

    /** Save into Pictures/PenAssist (no storage permission needed on Android 10+). */
    fun saveToGallery(ctx: Context, bmp: Bitmap): Uri? {
        val name = "PenAssist_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PenAssist")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    private fun uriFor(ctx: Context, f: File): Uri =
        FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)

    private fun imageSendIntent(ctx: Context, f: File): Intent {
        val uri = uriFor(ctx, f)
        return Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("image", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun shareImage(ctx: Context, f: File) {
        val chooser = Intent.createChooser(imageSendIntent(ctx, f), "แชร์รูป")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(chooser)
    }

    /** Try Google app (Lens: search / translate image). Falls back to the share sheet. */
    fun searchWithLens(ctx: Context, f: File) {
        val direct = imageSendIntent(ctx, f).apply {
            setPackage("com.google.android.googlequicksearchbox")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ctx.startActivity(direct)
        } catch (e: ActivityNotFoundException) {
            val chooser = Intent.createChooser(imageSendIntent(ctx, f), "ค้นหา/แปลด้วย (เลือก Google Lens หรือ Translate)")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(chooser)
        }
    }
}
