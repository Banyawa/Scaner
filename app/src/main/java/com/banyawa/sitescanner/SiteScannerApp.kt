package com.banyawa.sitescanner

import android.app.Application
import android.content.Context
import com.banyawa.sitescanner.core.project.ProjectRepository
import com.banyawa.sitescanner.data.ModelBuilder
import com.banyawa.sitescanner.data.ScanAnalysis
import java.io.File

class SiteScannerApp : Application() {
    val repository: ProjectRepository by lazy { ProjectRepository(File(filesDir, "projects")) }
    val analysis: ScanAnalysis by lazy { ScanAnalysis(repository) }
    val modelBuilder: ModelBuilder by lazy { ModelBuilder(repository) }
}

val Context.app: SiteScannerApp get() = applicationContext as SiteScannerApp
