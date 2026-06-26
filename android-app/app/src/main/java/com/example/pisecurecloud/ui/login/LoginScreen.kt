package com.example.pisecurecloud.ui.login

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.pisecurecloud.network.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

@Composable
fun LoginScreen(onLoginSuccess: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val sharedPrefs = remember {
        context.getSharedPreferences("pisecurecloud_prefs", Context.MODE_PRIVATE)
    }

    var serverUrl by remember { mutableStateOf(sharedPrefs.getString("server_url", "http://10.0.2.2:3000") ?: "") }
    var username by remember { mutableStateOf(sharedPrefs.getString("username", "") ?: "") }
    var password by remember { mutableStateOf(sharedPrefs.getString("password", "") ?: "") }
    
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val savedServer = sharedPrefs.getString("server_url", "") ?: ""
        val savedUser = sharedPrefs.getString("username", "") ?: ""
        val savedPass = sharedPrefs.getString("password", "") ?: ""
        
        if (savedServer.isNotBlank() && savedUser.isNotBlank() && savedPass.isNotBlank()) {
            isLoading = true
            errorMessage = "Automatischer Login..."
            
            scope.launch {
                var targetUrl = savedServer.trim()
                if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
                    errorMessage = "Löse Bucket-ID auf..."
                    val resolved = resolveBucketUrl(targetUrl)
                    if (resolved != null) {
                        targetUrl = resolved
                    } else {
                        isLoading = false
                        errorMessage = "Bucket-ID konnte nicht gelöst werden. Bitte manuell anmelden."
                        return@launch
                    }
                }
                
                NetworkClient.setServerUrl(targetUrl, savedServer.trim())
                val result = NetworkClient.login(savedUser, savedPass)
                isLoading = false
                if (result.isSuccess) {
                    Toast.makeText(context, "Automatisch angemeldet", Toast.LENGTH_SHORT).show()
                    onLoginSuccess()
                } else {
                    errorMessage = "Automatischer Login fehlgeschlagen: " + (result.exceptionOrNull()?.message ?: "")
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("PiSecureCloud", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Mit deiner Cloud verbinden", style = MaterialTheme.typography.bodyMedium)
        
        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("Server-URL oder Bucket-ID") },
            placeholder = { Text("z.B. http://192.168.1.100:3000 oder psc_xxx") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Benutzername") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Passwort") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        if (errorMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(errorMessage, color = MaterialTheme.colorScheme.error)
        }

        Spacer(modifier = Modifier.height(32.dp))

        if (isLoading) {
            CircularProgressIndicator()
        } else {
            Button(
                onClick = {
                    if (serverUrl.isBlank() || username.isBlank() || password.isBlank()) {
                        errorMessage = "Bitte alle Felder ausfüllen."
                        return@Button
                    }
                    isLoading = true
                    errorMessage = ""
                    
                    scope.launch {
                        var targetUrl = serverUrl.trim()
                        if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
                            errorMessage = "Löse Bucket-ID auf..."
                            val resolved = resolveBucketUrl(targetUrl)
                            if (resolved != null) {
                                targetUrl = resolved
                            } else {
                                isLoading = false
                                errorMessage = "Bucket-ID konnte nicht aufgelöst werden. Bitte überprüfe die Verbindung."
                                return@launch
                            }
                        }

                        NetworkClient.setServerUrl(targetUrl, serverUrl.trim())
                        val result = NetworkClient.login(username, password)
                        isLoading = false
                        if (result.isSuccess) {
                            sharedPrefs.edit()
                                .putString("server_url", serverUrl.trim()) // Save the entered string (can be Bucket ID or URL)
                                .putString("username", username)
                                .putString("password", password)
                                .apply()
                            
                            Toast.makeText(context, "Erfolgreich angemeldet", Toast.LENGTH_SHORT).show()
                            onLoginSuccess()
                        } else {
                            errorMessage = result.exceptionOrNull()?.message ?: "Anmeldefehler"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Anmelden")
            }
        }
    }
}

private suspend fun resolveBucketUrl(bucketId: String): String? = withContext(Dispatchers.IO) {
    try {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("https://keyvalue.immanuel.co/api/KeyVal/GetValue/$bucketId/url")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val hexJson = response.body?.string() ?: return@use null
                val hexStr = Json.parseToJsonElement(hexJson).jsonPrimitive.content
                
                // Decode hex to string
                val bytes = ByteArray(hexStr.length / 2)
                for (i in bytes.indices) {
                    val index = i * 2
                    val j = hexStr.substring(index, index + 2).toInt(16)
                    bytes[i] = j.toByte()
                }
                String(bytes, Charsets.UTF_8)
            } else {
                null
            }
        }
    } catch (e: Exception) {
        null
    }
}
