package com.bookshelf.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bookshelf.app.ui.BookShelfApp
import com.bookshelf.app.ui.BookShelfViewModel
import com.bookshelf.app.ui.BookShelfViewModelFactory
import com.bookshelf.app.ui.theme.BookShelfTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as BookShelfApplication).container

        setContent {
            BookShelfTheme {
                val vm: BookShelfViewModel = viewModel(
                    factory = BookShelfViewModelFactory(container.repository)
                )
                BookShelfApp(vm)
            }
        }
    }
}
