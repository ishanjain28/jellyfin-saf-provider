package me.ishan.jellyfin_saf

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.StrictMode
import android.provider.DocumentsContract
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
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ishan.jellyfin_saf.db.AppDatabase
import me.ishan.jellyfin_saf.ui.theme.JellyfinSaf

class MainActivity : ComponentActivity() {
	@OptIn(ExperimentalMaterial3Api::class)
	override fun onCreate(savedInstanceState: Bundle?) {
		StrictMode.setVmPolicy(
			StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().penaltyLog()
				.penaltyDropBox().build()
		)
		Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
			android.util.Log.e("MainActivity", "Uncaught exception", throwable)
			// don't rethrow - prevents crash
		}
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		setContent {
			JellyfinSaf {
				Scaffold(
					modifier = Modifier.fillMaxSize(),
					topBar = {
						TopAppBar(
							title = { Text("Jellyfin Storage Provider") },
							colors = TopAppBarDefaults.topAppBarColors(
								containerColor = MaterialTheme.colorScheme.primaryContainer,
								titleContentColor = MaterialTheme.colorScheme.primary
							)
						)
					},
					bottomBar = {
						BottomAppBar(
							containerColor = MaterialTheme.colorScheme.primaryContainer,
							contentColor = MaterialTheme.colorScheme.primary
						) {
							Text(
								text = "Jellyfin Storage Provider",
								modifier = Modifier.fillMaxWidth(),
								textAlign = TextAlign.Center
							)
						}
					}
				) { innerPadding ->
					JellyfinSettings(
						modifier = Modifier.padding(innerPadding)
					)
				}
			}
		}
	}
}

private fun handleNetworkError(t: Throwable): String {
	return when (t) {
		is org.jellyfin.sdk.api.client.exception.TimeoutException -> "Server timed out - is Jellyfin running?"
		is java.net.ConnectException -> "Cannot reach server - check URL and network"
		is java.net.UnknownHostException -> "Unknown host - check server URL"
		is java.net.SocketTimeoutException -> "Connection timed out"
		else -> "Error: ${t.message}"
	}
}

@SuppressLint("DefaultLocale")
@Composable
fun JellyfinSettings(modifier: Modifier = Modifier) {
	val context = LocalContext.current
	val manager = remember { JellyfinClientManager(context) }
	val playbackManager = remember { TrackPlaybackManagerSingleton.getInstance(context) }
	val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
		// exceptions are handled in each launch block
	}
	val scope = rememberCoroutineScope { exceptionHandler }
	
	var url by remember { mutableStateOf(manager.getUrl()) }
	var username by remember { mutableStateOf(manager.getUsername()) }
	var password by remember { mutableStateOf(manager.getPassword()) }
	var maxCacheSize by remember { mutableStateOf(manager.getMaxCacheSize().toString()) }
	var isLoading by remember { mutableStateOf(false) }
	var isAuthenticated by remember { mutableStateOf(manager.isAuthenticated()) }
	var cacheBreakdown by remember { mutableStateOf<TrackPlaybackManager.CacheSizeBreakdown?>(null) }
	var isEvicting by remember { mutableStateOf(false) }
	var isScanning by remember { mutableStateOf(false) }
	var scanProgress by remember { mutableStateOf("") }
	var libraryStats by remember { mutableStateOf<Pair<Int, Int>?>(null) }
	
	fun performScan() {
		isScanning = true
		scanProgress = "Starting scan..."
		scope.launch(Dispatchers.IO) {
			try {
				val albums = manager.getAlbums(limit = 100000)
				var tracksScanned = 0
				for ((index, album) in albums.withIndex()) {
					if (!isScanning) break
					withContext(Dispatchers.Main) {
						scanProgress = "Scanning album ${index + 1}/${albums.size}: ${album.name}"
					}
					val tracks = manager.getAlbumTracks(album.id)
					for (track in tracks) {
						manager.getTrackMetadata(track.id)
						tracksScanned++
					}
				}
				val breakdown = playbackManager.getCacheSizeBreakdown()
				withContext(Dispatchers.Main) {
					scanProgress = "Scan complete! Cached metadata for ${albums.size} albums, $tracksScanned tracks."
					Toast.makeText(context, scanProgress, Toast.LENGTH_LONG).show()
					isScanning = false
					cacheBreakdown = breakdown
				}
			} catch (e: Exception) {
				withContext(Dispatchers.Main) {
					scanProgress = "Scan failed: ${handleNetworkError(e)}"
					Toast.makeText(context, scanProgress, Toast.LENGTH_LONG).show()
					isScanning = false
				}
			}
		}
	}
	
	LaunchedEffect(Unit) {
		val breakdown = withContext(Dispatchers.IO) { playbackManager.getCacheSizeBreakdown() }
		cacheBreakdown = breakdown
	}
	
	LaunchedEffect(isAuthenticated) {
		if (isAuthenticated) {
			try {
				val stats = withContext(Dispatchers.IO) { manager.getLibraryStats() }
				libraryStats = stats
			} catch (t: Throwable) {
				libraryStats = null
				withContext(Dispatchers.Main) {
					Toast.makeText(context, handleNetworkError(t), Toast.LENGTH_LONG).show()
				}
			}
		} else {
			libraryStats = null
		}
	}
	
	Column(
		modifier = modifier
			.fillMaxSize()
			.padding(16.dp)
			.verticalScroll(rememberScrollState()),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.Top
	) {
		if (isAuthenticated) {
			Card(
				modifier = Modifier.fillMaxWidth(),
				colors = CardDefaults.cardColors(
					containerColor = MaterialTheme.colorScheme.primaryContainer
				)
			) {
				Column(modifier = Modifier.padding(16.dp)) {
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
					scope.launch { manager.logout() }
					// Reset compose state to match cleared credentials
					url = ""
					username = ""
					password = ""
					isAuthenticated = false
				},
				modifier = Modifier.fillMaxWidth(),
				colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
			) {
				Text("Logout")
			}
		} else {
			OutlinedTextField(
				value = url,
				onValueChange = { url = it },
				label = { Text("Jellyfin Server URL") },
				placeholder = { Text("http://jellyfin:8096") },
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
			
			// Save and Login button
			Button(
				onClick = {
					isLoading = true
					manager.updateCredentials(url, username, password)
					scope.launch {
						try {
							val success = withContext(Dispatchers.IO) { manager.login() }
							isLoading = false
							if (success) {
								isAuthenticated = true
								context.contentResolver.notifyChange(
									DocumentsContract.buildRootsUri(
										context.applicationContext.getString(R.string.documents_authority)
									), null
								)
								Toast.makeText(context, "Login Successful! Starting scan...", Toast.LENGTH_SHORT).show()
								performScan()
							} else {
								Toast.makeText(context, "Login Failed. Check your credentials.", Toast.LENGTH_LONG).show()
							}
						} catch (t: Throwable) {  // <-- Throwable not Exception
							isLoading = false
							Toast.makeText(context, handleNetworkError(t), Toast.LENGTH_LONG).show()
						}
					}
				},
				enabled = !isLoading && url.isNotBlank() && username.isNotBlank(),
				modifier = Modifier.fillMaxWidth()
			) {
				if (isLoading) {
					CircularProgressIndicator(
						modifier = Modifier.size(24.dp),
						color = MaterialTheme.colorScheme.onPrimary
					)
					Spacer(modifier = Modifier.width(8.dp))
					Text("Connecting...")
				} else {
					Text("Save and Login")
				}
			}
			
			Spacer(modifier = Modifier.height(12.dp))
			
			// Test Connection button
			OutlinedButton(
				onClick = {
					isLoading = true
					scope.launch {
						try {
							val success = withContext(Dispatchers.IO) { manager.testConnection() }
							isLoading = false
							Toast.makeText(
								context,
								if (success) "Connection OK!" else "Connection Failed!",
								Toast.LENGTH_SHORT
							).show()
						} catch (t: Throwable) {  // <-- Throwable not Exception
							isLoading = false
							Toast.makeText(context, handleNetworkError(t), Toast.LENGTH_LONG).show()
						}
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
		
		if (isAuthenticated) {
			libraryStats?.let { stats ->
				Text(
					text = "Server Library: ${stats.first} Albums, ${stats.second} Tracks",
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(bottom = 8.dp).align(Alignment.Start)
				)
			} ?: Text(
				text = "Fetching library stats...",
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(bottom = 8.dp).align(Alignment.Start)
			)
		}
		
		// Scan Library button
		Button(
			onClick = { performScan() },
			modifier = Modifier.fillMaxWidth(),
			enabled = !isScanning && !isEvicting && isAuthenticated,
			colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
		) {
			if (isScanning) {
				CircularProgressIndicator(
					modifier = Modifier.size(24.dp),
					color = MaterialTheme.colorScheme.onPrimary
				)
				Spacer(modifier = Modifier.width(8.dp))
				Text(
					text = scanProgress,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
			} else {
				Text("Scan Library")
			}
		}
		
		Spacer(modifier = Modifier.height(16.dp))
		
		OutlinedTextField(
			value = maxCacheSize,
			onValueChange = {
				if (it.isEmpty() || it.all { char -> char.isDigit() }) {
					maxCacheSize = it
					it.toLongOrNull()?.let { size ->
						manager.setMaxCacheSize(size)
						playbackManager.setMaxCacheSizeMB(size)
					}
				}
			},
			label = { Text("Max Cache Size (MB)") },
			modifier = Modifier.fillMaxWidth(),
			keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
			singleLine = true
		)
		
		Spacer(modifier = Modifier.height(16.dp))
		
		cacheBreakdown?.let { breakdown ->
			Column(modifier = Modifier.fillMaxWidth()) {
				Text(
					text = "Currently cached: ${String.format("%.2f MB", breakdown.totalSize / (1024.0 * 1024.0))} / ${breakdown.completeSongsNum + breakdown.partialSongsNum} Songs",
					style = MaterialTheme.typography.bodyLarge,
					color = MaterialTheme.colorScheme.onSurface,
					modifier = Modifier.padding(bottom = 4.dp)
				)
				Text(
					text = "  • Partial files: ${String.format("%.2f MB", breakdown.partialFilesSize / (1024.0 * 1024.0))} / ${breakdown.partialSongsNum} Songs",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
				Text(
					text = "  • Favourite files: ${String.format("%.2f MB", breakdown.favouriteFilesSize / (1024.0 * 1024.0))} / ${breakdown.favouriteSongsNum} Songs",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
				Text(
					text = "  • Complete files: ${String.format("%.2f MB", breakdown.completeFilesSize / (1024.0 * 1024.0))} / ${breakdown.completeSongsNum} Songs",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
				Text(
					text = "  • Album arts: ${String.format("%.2f MB", breakdown.albumArtsSize / (1024.0 * 1024.0))}",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
				Text(
					text = "  • Database: ${String.format("%.2f MB", breakdown.databaseSize / (1024.0 * 1024.0))}",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
			}
		} ?: Text(
			text = "Calculating cache size...",
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant
		)
		
		Spacer(modifier = Modifier.height(8.dp))
		
		// Refresh Cache Stats button
		OutlinedButton(
			onClick = {
				scope.launch(Dispatchers.IO) {
					val breakdown = playbackManager.getCacheSizeBreakdown()
					withContext(Dispatchers.Main) {
						cacheBreakdown = breakdown
						Toast.makeText(context, "Cache stats refreshed", Toast.LENGTH_SHORT).show()
					}
				}
			},
			modifier = Modifier.fillMaxWidth()
		) {
			Text("Refresh Cache Stats")
		}
		
		Spacer(modifier = Modifier.height(8.dp))
		
		// Evict Old Tracks button
		Button(
			onClick = {
				isEvicting = true
				playbackManager.performEviction { count ->
					isEvicting = false
					scope.launch(Dispatchers.IO) {
						val breakdown = playbackManager.getCacheSizeBreakdown()
						withContext(Dispatchers.Main) {
							cacheBreakdown = breakdown
							Toast.makeText(context, "Evicted $count tracks", Toast.LENGTH_SHORT).show()
						}
					}
				}
			},
			modifier = Modifier.fillMaxWidth(),
			enabled = !isEvicting,
			colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
		) {
			if (isEvicting) {
				CircularProgressIndicator(
					modifier = Modifier.size(24.dp),
					color = MaterialTheme.colorScheme.onSecondary
				)
				Spacer(modifier = Modifier.width(8.dp))
				Text("Evicting...")
			} else {
				Text("Evict Old Tracks")
			}
		}
		
		Spacer(modifier = Modifier.height(16.dp))
		
		Button(
			onClick = {
				scope.launch(Dispatchers.IO) {
					playbackManager.deletePartialFiles(excludeFavourites = true)
					val breakdown = playbackManager.getCacheSizeBreakdown()
					withContext(Dispatchers.Main) {
						cacheBreakdown = breakdown
						Toast.makeText(context, "Partial files deleted (favourites kept)", Toast.LENGTH_SHORT).show()
					}
				}
			},
			modifier = Modifier.fillMaxWidth(),
			colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
		) {
			Text("Remove Partial Files")
		}
		
		Spacer(modifier = Modifier.height(8.dp))
		
		Button(
			onClick = {
				scope.launch(Dispatchers.IO) {
					playbackManager.deleteAllTracks(excludeFavourites = true)
					val breakdown = playbackManager.getCacheSizeBreakdown()
					withContext(Dispatchers.Main) {
						cacheBreakdown = breakdown
						Toast.makeText(context, "All tracks deleted (favourites kept)", Toast.LENGTH_SHORT).show()
					}
				}
			},
			modifier = Modifier.fillMaxWidth(),
			colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
		) {
			Text("Remove All Downloaded Tracks")
		}
		
		Spacer(modifier = Modifier.height(8.dp))
		
		Button(
			onClick = {
				scope.launch(Dispatchers.IO) {
					playbackManager.deleteFavouriteTracks()
					val breakdown = playbackManager.getCacheSizeBreakdown()
					withContext(Dispatchers.Main) {
						cacheBreakdown = breakdown
						Toast.makeText(context, "Favourite tracks deleted", Toast.LENGTH_SHORT).show()
					}
				}
			},
			modifier = Modifier.fillMaxWidth(),
			colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
		) {
			Text("Remove Favourite Tracks")
		}
		
		Spacer(modifier = Modifier.height(16.dp))
		
		Button(
			onClick = {
				scope.launch(Dispatchers.IO) {
					val db = AppDatabase.getDatabase(context)
					playbackManager.deleteAllTracks(excludeFavourites = false)
					playbackManager.deleteAllAlbumArts()
					db.clearAllTables()
					val breakdown = playbackManager.getCacheSizeBreakdown()
					withContext(Dispatchers.Main) {
						cacheBreakdown = breakdown
						Toast.makeText(context, "Database Reset & Cache Cleared", Toast.LENGTH_SHORT).show()
					}
				}
			},
			modifier = Modifier.fillMaxWidth(),
			colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
		) {
			Text("Reset Database & Clear All Cache")
		}
	}
}