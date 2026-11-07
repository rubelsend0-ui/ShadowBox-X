package com.shadowbox.x.core.util

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.result.contract.ActivityResultContract
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.FragmentActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun Context.findActivity(): FragmentActivity {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is FragmentActivity) return c
        c = c.baseContext
    }
    error("No FragmentActivity")
}

fun formatBytes(b: Long): String {
    if (b < 1024) return "$b B"
    val u = arrayOf("KB", "MB", "GB", "TB")
    var v = b / 1024.0
    var i = 0
    while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
    return "%.1f %s".format(v, u[i])
}

fun formatDate(ms: Long): String = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))

object ImageUtil {
    fun rotation(open: () -> InputStream?): Int = try {
        open()?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
    } catch (e: Exception) { 0 }

    fun decode(open: () -> InputStream?, max: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth <= 0) null else {
            var s = 1
            while (o.outWidth / (s * 2) >= max || o.outHeight / (s * 2) >= max) s *= 2
            var bmp = open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }
            if (bmp != null) {
                val rot = rotation(open)
                val scale = max.toFloat() / maxOf(bmp.width, bmp.height)
                if (rot != 0 || scale < 1f) {
                    val m = Matrix()
                    if (scale < 1f) m.postScale(scale, scale)
                    if (rot != 0) m.postRotate(rot.toFloat())
                    bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                }
            }
            bmp
        }
    } catch (e: Throwable) { null }
}

object QrUtil {
    fun bitmap(text: String, size: Int = 640): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }
}

object ClipboardHelper {
    /** Copies as sensitive (hidden from clipboard previews) and auto-clears after [clearAfterSec] (0 = never). */
    fun copy(ctx: Context, scope: CoroutineScope, label: String, text: String, clearAfterSec: Int) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        val extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        clip.description.extras = extras
        cm.setPrimaryClip(clip)
        if (clearAfterSec > 0) {
            scope.launch {
                delay(clearAfterSec * 1000L)
                val cur = cm.primaryClip?.getItemAt(0)?.text?.toString()
                if (cur == text) {
                    if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip()
                    else cm.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            }
        }
    }
}

class CreateDocWithMime : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.second).putExtra(Intent.EXTRA_TITLE, input.first)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}
