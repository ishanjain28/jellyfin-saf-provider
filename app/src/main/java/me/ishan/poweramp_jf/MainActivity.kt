package me.ishan.poweramp_jf

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.ishan.poweramp_jf.ui.theme.PowerampjfTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PowerampjfTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    JellyfinSettings(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun JellyfinSettings(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val manager = remember { JellyfinClientManager(context) }
    val cacheManager = remember { TrackCacheManager(context) }
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf(manager.getUrl()) }
    var username by remember { mutableStateOf(manager.getUsername()) }
    var password by remember { mutableStateOf(manager.getPassword()) }
    var maxCacheSize by remember { mutableStateOf(manager.getMaxCacheSize().toString()) }
    var cachedSizeMB by remember { mutableStateOf(cacheManager.getStats().usedMB) }
    var isLoading by remember { mutableStateOf(false) }
    var isAuthenticated by remember { mutableStateOf(manager.isAuthenticated()) }

    // Refresh cache size when entering the screen
    LaunchedEffect(Unit) {
        cachedSizeMB = cacheManager.getStats().usedMB
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            text = "Jellyfin Configuration",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 16.dp, bottom = 24.dp)
        )

        if (isAuthenticated) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "✓ Connected",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "User: ${manager.getUsername()}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Text(
                        text = manager.getUrl(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    manager.logout()
                    isAuthenticated = false
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Logout")
            }
        } else {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Jellyfin Server URL") },
                placeholder = { Text("https://jellyfin.example.com") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    isLoading = true
                    manager.updateCredentials(url, username, password)

                    scope.launch {
                        val success = manager.login()
                        isLoading = false

                        if (success) {
                            isAuthenticated = true
                            Toast.makeText(
                                context, "Login Successful!", Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Toast.makeText(
                                context, "Login Failed. Check your credentials.", Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                },
                enabled = !isLoading && url.isNotBlank() && username.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connecting...")
                } else {
                    Text("Save and Login")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    isLoading = true
                    scope.launch {
                        val success = manager.testConnection()
                        isLoading = false

                        Toast.makeText(
                            context,
                            if (success) "Connection OK!" else "Connection Failed!",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                enabled = !isLoading && url.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Test Connection")
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

        Text(
            text = "App Settings",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.Start)
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = maxCacheSize,
            onValueChange = {
                if (it.isEmpty() || it.all { char -> char.isDigit() }) {
                    maxCacheSize = it
                    it.toLongOrNull()?.let { size -> manager.setMaxCacheSize(size) }
                }
            },
            label = { Text("Max Cache Size (MB)") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Currently cached: $cachedSizeMB MB",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.Start)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                cacheManager.clearCache()
                cachedSizeMB = 0
                Toast.makeText(context, "Cache Cleared", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Text("Clear Cache")
        }
    }
}
