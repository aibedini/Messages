package com.autonomousone.messages.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.autonomousone.messages.model.Sms

@Composable
fun ChatBubble(
    sms: Sms
) {

    val incoming = sms.type == 1

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement =
            if (incoming)
                Arrangement.Start
            else
                Arrangement.End
    ) {

        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(
                    color =
                        if (incoming)
                            Color(0xFFF1F5F9)
                        else
                            MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(18.dp)
                )
                .padding(12.dp)
        ) {

            Text(
                text = sms.message,
                color =
                    if (incoming)
                        Color.Black
                    else
                        Color.White,
                textAlign = TextAlign.Start
            )

        }

    }

}