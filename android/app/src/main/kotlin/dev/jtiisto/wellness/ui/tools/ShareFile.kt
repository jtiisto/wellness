package dev.jtiisto.wellness.ui.tools

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hand a staged file to a share sheet.
 *
 * Three flags travel together and all three are needed. `EXTRA_STREAM` is what
 * the receiving app reads; the `ClipData` carries the same URI so the system
 * can propagate the grant to targets that inspect it there instead; and
 * `FLAG_GRANT_READ_URI_PERMISSION` is what makes either readable at all — the
 * provider is `exported="false"`, so without the grant every recipient gets a
 * SecurityException.
 *
 * `createChooser` rather than a direct `startActivity`: the file is an export
 * of personal data, and the user should be picking where it goes every time
 * rather than having a default remembered for them.
 */
fun shareStagedFile(context: Context, path: String, mimeType: String) {
    val file = File(path)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, null)
    // Only an Activity context can start one without NEW_TASK, and starting a
    // chooser in a new task would detach it from the app it belongs to.
    if (context.findActivity() == null) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}

/**
 * Compose hands out a themed wrapper rather than the Activity itself, so a
 * direct cast misses. Unwrapping is also what tells [shareStagedFile] whether
 * it needs `NEW_TASK`.
 */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
