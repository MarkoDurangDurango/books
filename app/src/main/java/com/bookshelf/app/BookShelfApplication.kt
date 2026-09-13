package com.bookshelf.app

import android.app.Application
import com.bookshelf.app.data.AppContainer

class BookShelfApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
