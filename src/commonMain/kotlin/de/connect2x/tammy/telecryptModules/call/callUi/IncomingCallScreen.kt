package de.connect2x.tammy.telecryptModules.call.callUi

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.connect2x.tammy.telecryptModules.call.callRtc.CallCoordinator
import de.connect2x.tammy.telecryptModules.call.callRtc.IncomingCall
import de.connect2x.tammy.telecryptModules.call.callRtc.IncomingCallManager
import de.connect2x.messenger.compose.view.DI
import kotlinx.coroutines.launch
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.connect2x.messenger.compose.view.get
import de.connect2x.messenger.compose.view.theme.components.ThemedUserAvatar
import de.connect2x.tammy.telecryptModules.call.CallMode

/**
 * Full-screen incoming call overlay (Telegram-style).
 * Shows caller info and accept/decline buttons.
 */
@Composable
fun IncomingCallScreen() {
    val incomingCallManager: IncomingCallManager = DI.get()
    val callCoordinator: CallCoordinator = DI.get()
    val scope = rememberCoroutineScope()
    
    val incomingCall by incomingCallManager.incomingCall.collectAsState()
    
    incomingCall?.let { call ->
        Dialog(
            onDismissRequest = { /* Cannot dismiss by clicking outside */ },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            )
        ) {
            IncomingCallContent(
                call = call,
                onAcceptAudio = {
                    scope.launch {
                        val result = callCoordinator.joinCall(
                            matrixClient = call.matrixClient,
                            roomId = call.roomId,
                            roomName = call.roomName,
                            mode = CallMode.AUDIO,
                        )
                        if (result.ok) {
                            incomingCallManager.acceptCall()
                        }
                    }
                },
                onAcceptVideo = {
                    scope.launch {
                        val result = callCoordinator.joinCall(
                            matrixClient = call.matrixClient,
                            roomId = call.roomId,
                            roomName = call.roomName,
                            mode = CallMode.VIDEO,
                        )
                        if (result.ok) {
                            incomingCallManager.acceptCall()
                        }
                    }
                },
                onDecline = {
                    incomingCallManager.declineCall()
                },
            )
        }
    }
}

@Composable
private fun IncomingCallContent(
    call: IncomingCall,
    onAcceptAudio: () -> Unit,
    onAcceptVideo: () -> Unit,
    onDecline: () -> Unit,
) {
    // Animated pulsing effect for the avatar
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha"
    )
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1A1A2E),
                        Color(0xFF16213E),
                        Color(0xFF0F3460),
                    )
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Status text
            Text(
                text = if (call.isDirect) "Incoming Call" else "Incoming Group Call",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
            )
            
            Spacer(modifier = Modifier.height(48.dp))
            
            // Pulsing ring around avatar
            Box(contentAlignment = Alignment.Center) {
                // Outer pulse ring
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .scale(pulseScale)
                        .alpha(pulseAlpha)
                        .clip(CircleShape)
                        .background(Color(0xFF00D9FF).copy(alpha = 0.3f))
                )
                
                // Inner ring
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF00D9FF).copy(alpha = 0.2f))
                )
                
                // Avatar placeholder
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF667EEA),
                                    Color(0xFF764BA2),
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = call.callerName.take(2).uppercase(),
                        color = Color.White,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Caller name
            Text(
                text = call.callerName,
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Room name (for group calls)
            if (!call.isDirect) {
                Text(
                    text = call.roomName,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            // Call status
            Text(
                text = "TeleCrypt Secure Call",
                color = Color(0xFF00D9FF),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            
            Spacer(modifier = Modifier.height(80.dp))
            
            // Action buttons row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Decline button
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    IconButton(
                        onClick = onDecline,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE53935)),
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = Color(0xFFE53935),
                            contentColor = Color.White,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.CallEnd,
                            contentDescription = "Decline",
                            modifier = Modifier.size(32.dp),
                            tint = Color.White,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Decline",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 14.sp,
                    )
                }
                
                // Accept Audio button
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    IconButton(
                        onClick = onAcceptAudio,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF43A047)),
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = Color(0xFF43A047),
                            contentColor = Color.White,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Phone,
                            contentDescription = "Accept Audio",
                            modifier = Modifier.size(32.dp),
                            tint = Color.White,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Audio",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 14.sp,
                    )
                }
                
                // Accept Video button
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    IconButton(
                        onClick = onAcceptVideo,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E88E5)),
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = Color(0xFF1E88E5),
                            contentColor = Color.White,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = "Accept Video",
                            modifier = Modifier.size(32.dp),
                            tint = Color.White,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Video",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}
