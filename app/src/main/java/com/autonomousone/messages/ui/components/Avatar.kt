package com.autonomousone.messages.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun Avatar(name: String) {

    val firstLetter =
        if (name.isNotEmpty())
            name.first().uppercase()
        else
            "?"

    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),

        contentAlignment = Alignment.Center
    ) {

        Text(
            text = firstLetter,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp
        )

    }

}