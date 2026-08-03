package com.autonomousone.messages.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonomousone.messages.model.Contact

@Composable
fun ContactItem(
    contact: Contact,
    onClick: () -> Unit
) {

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                onClick()
            }
            .padding(16.dp)
    ) {

        Row {

            Avatar(contact.name)

            Spacer(
                modifier = Modifier.width(16.dp)
            )

            Column {

                Text(
                    text = contact.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )

                Spacer(
                    modifier = Modifier.height(4.dp)
                )

                Text(
                    text = contact.phone,
                    fontSize = 14.sp
                )

            }

        }

        Spacer(
            modifier = Modifier.height(12.dp)
        )

        HorizontalDivider()

    }

}