plugins {
    id("wellness.android.feature")
}

android {
    sourceSets {
        // The golden analysis payloads live at the repo root. The markdown
        // pipeline is pinned against the same report body the DTO contract test
        // decodes, so the raw-HTML vector is asserted on the exact bytes that
        // would arrive from the server, not a paraphrase of them.
        getByName("test") {
            resources.directories.add(layout.settingsDirectory.dir("testdata").asFile.path)
        }
    }
}

dependencies {
    // BackHandler: the four sub-views are store state rather than nav routes, so
    // the system gesture has to be handled here instead of by the NavHost.
    implementation(libs.androidx.activity.compose)

    // Parser only — the renderer is never used. Report markdown is walked into a
    // Compose model, so no HTML is produced anywhere in this module and there is
    // no WebView for any to land in.
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
}
