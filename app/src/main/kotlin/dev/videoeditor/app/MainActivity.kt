package dev.videoeditor.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.videoeditor.app.editor.AndroidMediaAvailability
import dev.videoeditor.app.editor.AndroidMediaImporter
import dev.videoeditor.app.editor.EditorScreen
import dev.videoeditor.app.editor.EditorViewModel
import dev.videoeditor.app.editor.FileProjectStore
import dev.videoeditor.app.editor.Media3ExportPort
import dev.videoeditor.app.editor.Media3PreviewPort
import dev.videoeditor.ui.shell.EditorLayoutMode
import dev.videoeditor.ui.shell.EditorTheme

class MainActivity : ComponentActivity() {
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
                    )
                }
            }
        setContent {
            EditorTheme {
                val vm: EditorViewModel = viewModel(factory = factory)
                val sizeClass = calculateWindowSizeClass(this)
                EditorScreen(vm, EditorLayoutMode.from(sizeClass.widthSizeClass))
            }
        }
    }
}
