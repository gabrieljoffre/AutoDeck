package com.autodeck.headunit.ui.screens

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.autodeck.headunit.R
import com.autodeck.headunit.ui.theme.AutoDeckColors
import kotlinx.coroutines.delay

private enum class BootPhase {
    VIDEO,
    CREDIT,
    DONE
}

@Composable
fun BootSplashOverlay(
    onFinished: () -> Unit
) {
    var phase by remember { mutableStateOf(BootPhase.VIDEO) }
    var creditVisible by remember { mutableStateOf(false) }
    var overlayExiting by remember { mutableStateOf(false) }

    val mediaPlayerRef = remember { arrayOfNulls<MediaPlayer>(1) }
    val surfaceRef = remember { arrayOfNulls<Surface>(1) }

    val creditAlpha by animateFloatAsState(
        targetValue = if (creditVisible && !overlayExiting) 1f else 0f,
        animationSpec = tween(durationMillis = if (overlayExiting) 550 else 500),
        label = "BootCreditAlpha"
    )
    val overlayAlpha by animateFloatAsState(
        targetValue = if (overlayExiting) 0f else 1f,
        animationSpec = tween(durationMillis = 550),
        label = "BootOverlayAlpha"
    )

    fun finishImmediately() {
        if (phase == BootPhase.DONE) return
        phase = BootPhase.DONE
        runCatching { mediaPlayerRef[0]?.stop() }
        onFinished()
    }

    // Timeout de segurança caso o vídeo trave em algum codec
    LaunchedEffect(phase) {
        if (phase == BootPhase.VIDEO) {
            delay(18_000L)
            if (phase == BootPhase.VIDEO) {
                phase = BootPhase.CREDIT
            }
        } else if (phase == BootPhase.CREDIT) {
            creditVisible = true
            delay(2_500L) // 500ms fade-in + 2000ms leitura
            overlayExiting = true
            delay(550L)
            finishImmediately()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                mediaPlayerRef[0]?.reset()
                mediaPlayerRef[0]?.release()
            }
            mediaPlayerRef[0] = null
            runCatching { surfaceRef[0]?.release() }
            surfaceRef[0] = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(overlayAlpha)
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                finishImmediately()
            }
    ) {
        if (phase == BootPhase.VIDEO) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    var videoW = 1920
                    var videoH = 1080

                    fun applyCenterCrop(tv: TextureView) {
                        val vw = tv.width
                        val vh = tv.height
                        if (vw <= 0 || vh <= 0 || videoW <= 0 || videoH <= 0) return
                        val videoAspect = videoW.toFloat() / videoH.toFloat()
                        val viewAspect = vw.toFloat() / vh.toFloat()
                        val scaleX = if (videoAspect > viewAspect) videoAspect / viewAspect else 1f
                        val scaleY = if (videoAspect < viewAspect) viewAspect / videoAspect else 1f
                        val matrix = Matrix()
                        matrix.setScale(scaleX, scaleY, vw / 2f, vh / 2f)
                        tv.setTransform(matrix)
                    }

                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                                val surface = Surface(st)
                                surfaceRef[0] = surface
                                try {
                                    val mp = MediaPlayer()
                                    mediaPlayerRef[0] = mp
                                    val afd = ctx.resources.openRawResourceFd(R.raw.boot_civic_intro)
                                    mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                                    afd.close()
                                    mp.setSurface(surface)
                                    mp.isLooping = false
                                    mp.setOnVideoSizeChangedListener { _, w, h ->
                                        if (w > 0 && h > 0) {
                                            videoW = w
                                            videoH = h
                                            post { applyCenterCrop(this@apply) }
                                        }
                                    }
                                    mp.setOnPreparedListener { player ->
                                        applyCenterCrop(this@apply)
                                        player.start()
                                    }
                                    mp.setOnCompletionListener {
                                        if (phase == BootPhase.VIDEO) {
                                            phase = BootPhase.CREDIT
                                        }
                                    }
                                    mp.setOnErrorListener { _, _, _ ->
                                        if (phase == BootPhase.VIDEO) {
                                            phase = BootPhase.CREDIT
                                        }
                                        true
                                    }
                                    mp.prepareAsync()
                                } catch (_: Exception) {
                                    if (phase == BootPhase.VIDEO) {
                                        phase = BootPhase.CREDIT
                                    }
                                }
                            }

                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                                applyCenterCrop(this@apply)
                            }

                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                runCatching { mediaPlayerRef[0]?.release() }
                                mediaPlayerRef[0] = null
                                runCatching { surfaceRef[0]?.release() }
                                surfaceRef[0] = null
                                return true
                            }

                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                        }
                    }
                }
            )
        }

        if (phase == BootPhase.CREDIT) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp)
                    .alpha(creditAlpha),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "HONDA CIVIC • AUTODECK",
                    color = AutoDeckColors.TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 3.sp
                )
                Spacer(modifier = Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .width(96.dp)
                        .height(2.dp)
                        .background(AutoDeckColors.AccentCyan)
                )
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    text = "Desenvolvido por Gabriel Joffre",
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}
