package com.asterion.compiler.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import com.asterion.compiler.R
import com.asterion.compiler.app.AsterionApplication

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val contentReady = mutableStateOf(false)
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { !contentReady.value }

        super.onCreate(savedInstanceState)
        setContent {
            val container = (application as AsterionApplication).container
            val compilerViewModel: CompilerViewModel = viewModel(factory = CompilerViewModel.factory(container))

            AppWithSplash(
                viewModel = compilerViewModel,
                onComposeVisible = { contentReady.value = true },
            )
        }
    }
}

@Composable
private fun AppWithSplash(
    viewModel: CompilerViewModel,
    onComposeVisible: () -> Unit,
) {
    var showSplash by remember { mutableStateOf(false) }
    val splashPainter = painterResource(id = R.drawable.asterion_brand_mark)
    val infiniteTransition = rememberInfiniteTransition()
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
    )
    val splashAlpha by animateFloatAsState(
        targetValue = if (showSplash) 1f else 0f,
        animationSpec = tween(durationMillis = 500, easing = LinearEasing),
    )

    LaunchedEffect(Unit) {
        withFrameNanos {
            showSplash = true
            onComposeVisible()
        }
    }

    LaunchedEffect(showSplash) {
        if (showSplash) {
            kotlinx.coroutines.delay(2000)
            showSplash = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AsterionCompilerApp(viewModel = viewModel)

        if (splashAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF080808))
                    .alpha(splashAlpha),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = splashPainter,
                    contentDescription = "Asterion logo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(138.dp)
                        .scale(pulseScale),
                )
            }
        }
    }
}
