package edu.neu.aijiaoxue.ui.capture

import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import edu.neu.aijiaoxue.data.entity.Material
import edu.neu.aijiaoxue.data.model.MaterialType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** US09 可选的图片材料类型（AUDIO 由录音产生，不在这里选）。 */
val PHOTO_TYPES = listOf(MaterialType.PPT, MaterialType.BLACKBOARD, MaterialType.OTHER)

/**
 * US09 采集页上的“拍照 / 相册导入”卡片。
 * 拍照用系统相机（TakePicture），不需要相机权限，也不引入 CameraX；照片直接写进 FileStore 给的文件。
 */
@Composable
fun PhotoCaptureCard(
    enabled: Boolean,
    onPreparePhoto: () -> Uri?,
    onPhotoTaken: (Boolean) -> Unit,
    onImport: (Uri) -> Unit,
    onError: (String) -> Unit,
) {
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture(), onPhotoTaken)
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(onImport)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("PPT / 板书拍照", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val buttonModifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                Button(
                    onClick = {
                        val uri = onPreparePhoto() ?: return@Button
                        try {
                            takePicture.launch(uri)
                        } catch (e: ActivityNotFoundException) {
                            // US09 异常处理：相机调用失败时提示，按钮保持可用以便重试
                            onPhotoTaken(false)
                            onError("无法打开相机，请重试")
                        }
                    },
                    enabled = enabled,
                    modifier = buttonModifier,
                ) { Text("拍照") }
                OutlinedButton(
                    onClick = {
                        try {
                            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        } catch (e: ActivityNotFoundException) {
                            onError("无法打开相册，请重试")
                        }
                    },
                    enabled = enabled,
                    modifier = buttonModifier,
                ) { Text("相册导入") }
            }
        }
    }
}

/** US09 主流程 2：拍摄或导入后选择材料类型，可填写备注。 */
@Composable
fun PhotoTypeDialog(
    material: Material,
    isNew: Boolean,
    onSave: (MaterialType, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var type by rememberSaveable(material.id) { mutableStateOf(material.type) }
    var note by rememberSaveable(material.id) { mutableStateOf(material.note.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "照片已保存，选择材料类型" else "修改材料类型") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PhotoThumbnail(material, Modifier
                    .fillMaxWidth()
                    .height(160.dp))
                Column(Modifier.selectableGroup()) {
                    PHOTO_TYPES.forEach { t ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .selectable(selected = type == t, onClick = { type = t }, role = Role.RadioButton),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = type == t, onClick = null)
                            Text(t.label, Modifier.padding(start = 8.dp))
                        }
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（选填）") },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(type, note) }) { Text("保存") } },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (isNew) "稍后再选" else "取消") }
        },
    )
}

/** US09 主流程 3：点击查看大图。 */
@Composable
fun PhotoViewer(item: MaterialItem, editable: Boolean, onEdit: () -> Unit, onDismiss: () -> Unit) {
    val m = item.material
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                if (item.fileMissing) {
                    Text("文件缺失", color = Color.White)
                } else {
                    PhotoImage(m, maxSize = 2048, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(item.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    photoTimeLabel(m),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                m.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (editable) TextButton(onClick = onEdit) { Text("修改类型/备注") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

/** 材料列表里的缩略图。 */
@Composable
fun PhotoThumbnail(material: Material, modifier: Modifier = Modifier.size(56.dp)) {
    PhotoImage(material, maxSize = 320, contentScale = ContentScale.Crop, modifier = modifier)
}

@Composable
private fun PhotoImage(material: Material, maxSize: Int, contentScale: ContentScale, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, material.filePath, maxSize) {
        value = withContext(Dispatchers.IO) {
            PhotoFiles.decode(File(context.filesDir, material.filePath), maxSize)
        }
    }
    val b = bitmap
    if (b == null) {
        Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp)) }
    } else {
        Image(
            bitmap = b.asImageBitmap(),
            contentDescription = "${material.type.label}照片",
            contentScale = contentScale,
            modifier = modifier,
        )
    }
}

/** “拍摄于 10:15:30 · 课堂第 12:03” 或 “导入于 …”（US09 业务规则 1、2）。 */
fun photoTimeLabel(m: Material): String {
    val time = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(m.capturedAt))
    val verb = if (m.isImported) "导入于" else "拍摄于"
    return "$verb $time · 课堂第 ${formatDuration(m.offsetMs)}"
}

/** 图片解码与导入。按目标尺寸采样，避免大图撑爆内存；按 EXIF 方向旋正。 */
object PhotoFiles {
    private const val IMPORT_MAX_SIZE = 2560
    private const val JPEG_QUALITY = 90
    private const val TAG = "PhotoFiles"

    fun decode(file: File, maxSize: Int): Bitmap? {
        if (!file.exists() || file.length() == 0L) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSize) }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        val orientation = try {
            ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        return rotate(bitmap, orientation)
    }

    /**
     * US09 业务规则 2：相册导入。统一转成 JPEG 存进任务目录（相册里可能是 PNG/HEIC），
     * 长边不超过 2560，方向旋正后保存。读取失败返回 false。
     */
    fun importAsJpeg(context: Context, uri: Uri, out: File): Boolean {
        val resolver = context.contentResolver
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            // 只读尺寸时 decodeStream 恒返回 null，用 outWidth 判断能否解析
            val stream = resolver.openInputStream(uri) ?: return false
            stream.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) return false
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, IMPORT_MAX_SIZE)
            }
            val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return false
            val orientation = resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
            val upright = rotate(bitmap, orientation)
            FileOutputStream(out).use { upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        } catch (e: Exception) {
            Log.w(TAG, "相册导入失败", e)
            false
        }
    }

    /** 2 的幂次采样，保证解码后长边不超过 maxSize。 */
    private fun sampleSize(width: Int, height: Int, maxSize: Int): Int {
        var sample = 1
        while (maxOf(width, height) / sample > maxSize) sample *= 2
        return sample
    }

    private fun rotate(bitmap: Bitmap, orientation: Int): Bitmap {
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
