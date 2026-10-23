package app.vazie.keep.qr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.vazie.keep.R
import app.vazie.keep.ui.designsystem.KeepButton
import app.vazie.keep.ui.designsystem.KeepButtonSize
import app.vazie.keep.ui.designsystem.KeepButtonStyle
import app.vazie.keep.ui.designsystem.KeepDesign
import app.vazie.keep.ui.designsystem.KeepIconButton
import app.vazie.keep.ui.designsystem.KeepShapes
import app.vazie.keep.ui.icons.VazieIcons
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun QrScannerScreen(onResult: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    val colors = KeepDesign.colors
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (granted) {
                CameraPreview(onResult)
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(260.dp)
                        .clip(KeepShapes.card)
                        .border(BorderStroke(3.dp, colors.accent), KeepShapes.card),
                )
            } else if (asked) {
                Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.qr_camera_denied), color = Color.White, style = KeepDesign.type.body, textAlign = TextAlign.Center)
                    KeepButton(
                        stringResource(R.string.qr_open_settings),
                        { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) },
                        style = KeepButtonStyle.Tonal,
                        size = KeepButtonSize.Medium,
                        fullWidth = false,
                    )
                }
            }
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp)) {
                KeepIconButton(VazieIcons.Close, stringResource(R.string.action_cancel), onDismiss, tint = Color.White)
            }
            Text(
                stringResource(R.string.qr_hint),
                color = Color.White,
                style = KeepDesign.type.body,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 32.dp, vertical = 64.dp),
            )
        }
    }
}

@Composable
private fun CameraPreview(onResult: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val delivered = remember { AtomicBoolean(false) }
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        providerFuture.addListener({
            val bound = providerFuture.get()
            provider = bound
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(executor) { image -> analyze(image, delivered) { text -> mainExecutor.execute { onResult(text) } } } }
            bound.unbindAll()
            bound.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, mainExecutor)
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }
    val label = stringResource(R.string.qr_preview)
    AndroidView({ previewView }, Modifier.fillMaxSize().semantics { contentDescription = label })
}

private fun analyze(image: ImageProxy, delivered: AtomicBoolean, onFound: (String) -> Unit) {
    try {
        if (delivered.get()) return
        val plane = image.planes[0]
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val buffer = plane.buffer
        val luminance = ByteArray(width * height)
        for (row in 0 until height) {
            buffer.position(row * rowStride)
            buffer.get(luminance, row * width, width)
        }
        val text = QrDecoder.fromLuminance(luminance, width, height)
        if (text != null && delivered.compareAndSet(false, true)) onFound(text)
    } finally {
        image.close()
    }
}
