package com.autonomousone.messages.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autonomousone.messages.data.dummySms
import com.autonomousone.messages.ui.components.SmsItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {

    var search by remember {
        mutableStateOf("")
    }

    val filteredList = dummySms.filter {

        it.sender.contains(search, true) ||
                it.message.contains(search, true)

    }

    Scaffold(

        topBar = {

            CenterAlignedTopAppBar(

                title = {
                    Text(
                        text = "Messages"
                    )
                },

                actions = {

                    IconButton(onClick = {}) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = null
                        )
                    }

                },

                colors = TopAppBarDefaults.centerAlignedTopAppBarColors()

            )

        },

        floatingActionButton = {

            FloatingActionButton(
                onClick = { }
            ) {

                Icon(
                    Icons.Default.Add,
                    contentDescription = null
                )

            }

        }

    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {

            OutlinedTextField(

                value = search,

                onValueChange = {
                    search = it
                },

                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 10.dp),

                placeholder = {
                    Text("Search messages")
                },

                leadingIcon = {

                    Icon(
                        Icons.Default.Search,
                        contentDescription = null
                    )

                },

                singleLine = true

            )

            LazyColumn(

                modifier = Modifier.fillMaxSize(),

                contentPadding = PaddingValues(bottom = 90.dp),

                verticalArrangement = Arrangement.spacedBy(4.dp)

            ) {

                items(filteredList) { sms ->

                    SmsItem(
                        sms = sms,
                        onClick = {

                        }
                    )

                }

            }

        }

    }

}