@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bookshelf.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File

@Composable
fun CoverScannerScreen(
    onBack: () -> Unit,
    onCaptured: (String) -> Unit
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Распознать обложку") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        if (granted) {
            CoverCamera(
                modifier = Modifier.fillMaxSize().padding(padding),
                onCaptured = onCaptured
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Для распознавания обложки нужен доступ к камере.")
                Button(
                    onClick = { launcher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text("Разрешить камеру")
                }
            }
        }
    }
}

@Composable
private fun CoverCamera(
    modifier: Modifier,
    onCaptured: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchEnabled by remember { mutableStateOf(false) }
    var torchAvailable by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        providerFuture.addListener({
            runCatching {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
                torchAvailable = camera?.cameraInfo?.hasFlashUnit() == true
            }.onFailure { errorText = "Не удалось открыть камеру: ${it.message}" }
        }, mainExecutor)

        onDispose {
            runCatching { providerFuture.get().unbindAll() }
        }
    }

    LaunchedEffect(torchEnabled, camera) {
        runCatching { camera?.cameraControl?.enableTorch(torchEnabled) }
    }

    Box(modifier.background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.72f)
                .height(430.dp)
                .border(2.dp, Color.White.copy(alpha = 0.92f), RoundedCornerShape(22.dp))
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.58f))
                .padding(PaddingValues(horizontal = 24.dp, vertical = 18.dp)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Поместите всю лицевую сторону обложки в рамку. Название и автор должны быть читаемы.",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium
            )
            errorText?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
            Button(
                enabled = !capturing,
                onClick = {
                    capturing = true
                    errorText = null
                    val file = File.createTempFile("bookshelf_cover_", ".jpg", context.cacheDir)
                    val options = ImageCapture.OutputFileOptions.Builder(file).build()
                    imageCapture.takePicture(
                        options,
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                capturing = false
                                onCaptured(file.absolutePath)
                            }

                            override fun onError(exception: ImageCaptureException) {
                                capturing = false
                                errorText = "Не удалось сделать снимок: ${exception.message}"
                                file.delete()
                            }
                        }
                    )
                },
                modifier = Modifier.padding(top = 14.dp)
            ) {
                Icon(Icons.Outlined.CameraAlt, contentDescription = null)
                Text(if (capturing) "Снимаем…" else "Снять обложку", modifier = Modifier.padding(start = 8.dp))
            }
            if (torchAvailable) {
                IconButton(onClick = { torchEnabled = !torchEnabled }) {
                    Icon(
                        if (torchEnabled) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff,
                        contentDescription = "Фонарик",
                        tint = Color.White
                    )
                }
            }
        }
    }
}
