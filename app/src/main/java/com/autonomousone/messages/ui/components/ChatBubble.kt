package com.autonomousone.messages.ui.components

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.utils.formatMessageTime

@Composable
fun ChatBubble(
    sms: Sms,
    modifier: Modifier = Modifier
) {
    val incoming = sms.type == 1

    // Parse image tag if present [IMAGE:uri]
    val imageUriString = if (sms.message.contains("[IMAGE:")) {
        sms.message.substringAfter("[IMAGE:").substringBefore("]")
    } else null

    val captionText = if (imageUriString != null) {
        sms.message.substringAfter("]", "").trim()
    } else {
        sms.message
    }

    // Asymmetrical rounded corner shape with bubble tail indicator
    val bubbleShape = if (incoming) {
        RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomEnd = 24.dp, bottomStart = 6.dp)
    } else {
        RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 6.dp)
    }

    AnimatedVisibility(
        visible = true,
        enter = fadeIn(animationSpec = tween(300)) + scaleIn(initialScale = 0.92f, animationSpec = tween(300))
    ) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
            horizontalArrangement = if (incoming) Arrangement.Start else Arrangement.End
        ) {
            Box(
                modifier = Modifier
                    .widthIn(min = 80.dp, max = 300.dp)
                    .shadow(
                        elevation = if (incoming) 1.dp else 2.dp,
                        shape = bubbleShape,
                        clip = false
                    )
                    .clip(bubbleShape)
                    .then(
                        if (incoming) {
                            Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                        } else {
                            Modifier.background(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.tertiary
                                    )
                                )
                            )
                        }
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Column {
                    if (!incoming) {
                        Text(
                            text = "You",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier
                                .align(Alignment.End)
                                .padding(bottom = 2.dp)
                        )
                    }
                    if (imageUriString != null) {
                        AsyncImage(
                            model = Uri.parse(imageUriString),
                            contentDescription = "Attached Image",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .clip(RoundedCornerShape(16.dp))
                        )
                        if (captionText.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                    }

                    if (captionText.isNotBlank()) {
                        Text(
                            text = captionText,
                            color = if (incoming) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                Color.White
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            fontSize = 15.sp,
                            lineHeight = 21.sp
                        )
                    }

                    Row(
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatMessageTime(sms.date),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Normal,
                            color = if (incoming) {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                            } else {
                                Color.White.copy(alpha = 0.75f)
                            }
                        )

                        if (!incoming) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = if (sms.unread) Icons.Default.Done else Icons.Default.DoneAll,
                                contentDescription = "Status",
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}