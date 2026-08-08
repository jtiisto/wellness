package dev.jtiisto.wellness.di

import dev.jtiisto.wellness.ui.tools.ToolsViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/** UI-layer wiring the app shell owns. Features bring their own modules. */
val appModule = module {
    viewModelOf(::ToolsViewModel)
}
