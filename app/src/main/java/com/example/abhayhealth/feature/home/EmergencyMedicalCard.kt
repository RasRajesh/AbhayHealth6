package com.example.abhayhealth.feature.home

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Emergency
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.example.abhayhealth.domain.model.PatientProfile
import kotlinx.coroutines.delay

/**
 * Single Emergency SOS Button Card.
 * Requires pressing and holding for 5 seconds to dial emergency (108 / Emergency Contact).
 */
@Composable
fun EmergencyMedicalCard(
    patientProfile: PatientProfile?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val emergencyNumber = patientProfile?.emergencyContactPhone?.ifBlank { null } ?: "108"
    val contactLabel = if (patientProfile?.emergencyContactPhone.isNullOrBlank()) {
        "108 Ambulance"
    } else {
        patientProfile?.emergencyContactName?.ifBlank { null } ?: emergencyNumber
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    var progress by remember { mutableFloatStateOf(0f) }
    var secondsRemaining by remember { mutableIntStateOf(5) }

    LaunchedEffect(isPressed) {
        if (isPressed) {
            val totalTimeMs = 5000L
            val stepMs = 50L
            var elapsedMs = 0L

            while (isPressed && elapsedMs < totalTimeMs) {
                delay(stepMs)
                elapsedMs += stepMs
                progress = (elapsedMs.toFloat() / totalTimeMs).coerceIn(0f, 1f)
                secondsRemaining = (5 - (elapsedMs / 1000)).toInt().coerceIn(1, 5)
            }

            if (isPressed && elapsedMs >= totalTimeMs) {
                val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                    data = "tel:$emergencyNumber".toUri()
                }
                try {
                    context.startActivity(dialIntent)
                } catch (_: Exception) {
                    Toast.makeText(context, "Could not launch phone dialer", Toast.LENGTH_SHORT).show()
                }
                progress = 0f
                secondsRemaining = 5
            }
        } else {
            progress = 0f
            secondsRemaining = 5
        }
    }

    val cardBgColor by animateColorAsState(
        targetValue = if (isPressed) Color(0xFFD32F2F) else MaterialTheme.colorScheme.surface,
        label = "EmergencyCardBg"
    )

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardBgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(
            width = 1.5.dp,
            color = if (isPressed) Color(0xFFE53935) else Color(0x33E53935)
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Surface(
            onClick = {}, // Register click listener to track press interaction
            interactionSource = interactionSource,
            color = Color.Transparent,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (isPressed) Color.White.copy(alpha = 0.25f) else Color(0x22E53935),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Emergency,
                                    contentDescription = null,
                                    tint = if (isPressed) Color.White else Color(0xFFE53935),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isPressed) "HOLDING FOR SOS DIAL..." else "Emergency SOS Call",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isPressed) Color.White else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Press and hold 5 sec to dial $contactLabel",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isPressed) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = null,
                        tint = if (isPressed) Color.White else Color(0xFFE53935),
                        modifier = Modifier.size(22.dp)
                    )
                }

                if (isPressed) {
                    Spacer(modifier = Modifier.height(14.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.35f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Dialing in $secondsRemaining second${if (secondsRemaining > 1) "s" else ""}...",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                }
            }
        }
    }
}
