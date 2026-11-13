package dev.videoeditor.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.videoeditor.app.ai.AndroidAiPorts
import dev.videoeditor.app.editor.AndroidMediaAvailability
import dev.videoeditor.app.editor.AndroidMediaImporter
import dev.videoeditor.app.editor.EditorScreen
import dev.videoeditor.app.editor.EditorViewModel
import dev.videoeditor.app.editor.FileProjectStore
import dev.videoeditor.app.editor.Media3ExportPort
import dev.videoeditor.app.editor.Media3PreviewPort
import dev.videoeditor.app.mcp.KeystoreClientStore
import dev.videoeditor.app.mcp.LocalSocketBridgeListener
import dev.videoeditor.app.mcp.McpPorts
import dev.videoeditor.app.security.AndroidKeystoreAesGcmCipher
import dev.videoeditor.app.security.SharedPreferencesPrefs
import dev.videoeditor.ui.shell.EditorLayoutMode
import dev.videoeditor.ui.shell.EditorTheme

class MainActivity : ComponentActivity() {
    private lateinit var vm: EditorViewModel

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = applicationContext
        val factory =
            viewModelFactory {
                initializer {
                    EditorViewModel(
                        preview = Media3PreviewPort(app),
                        exporter = Media3ExportPort(app),
                        store = FileProjectStore(app),
                        importer = AndroidMediaImporter(app),
                        availability = AndroidMediaAvailability(app),
                        aiPorts = AndroidAiPorts(app),
                        mcpPorts =
                            McpPorts(
                                clientStore = {
                                    KeystoreClientStore(
                                        SharedPreferencesPrefs.open(app, KeystoreClientStore.FILE),
                                        AndroidKeystoreAesGcmCipher(
                                            AndroidKeystoreAesGcmCipher.BRIDGE_ALIAS,
                                            AndroidKeystoreAesGcmCipher.BRIDGE_AAD,
                                        ),
                                    )
                                },
                                listener = { LocalSocketBridgeListener() },
                            ),
                    )
                }
            }
        vm = ViewModelProvider(this, factory)[EditorViewModel::class.java]
        setContent {
            EditorTheme {
                val sizeClass = calculateWindowSizeClass(this)
                EditorScreen(vm, EditorLayoutMode.from(sizeClass.widthSizeClass))
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.mcpOnStart()
    }

    override fun onStop() {
        vm.mcpOnStop()
        super.onStop()
    }
}
