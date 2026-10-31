package com.devoir.photoshop.data

import com.devoir.photoshop.model.Project
import com.devoir.photoshop.model.ProjectConfig
import java.util.UUID

const val NEW_PROJECT_ID = "new"

/** Swap in a Room-backed implementation in the project-saving phase. */
interface ProjectRepository {
    fun recent(): List<Project>
    fun find(id: String?): Project?
    fun create(name: String, config: ProjectConfig): Project
    fun update(project: Project)
}

/** Phase 2: projects live in memory for the current app session only (no database yet). */
object InMemoryProjectRepository : ProjectRepository {
    private fun sample(id: String, name: String, w: Int, h: Int, seed: Int) =
        Project(id, name, "Sample · $w × $h px", seed, ProjectConfig(w, h))

    private val items = mutableListOf(
        sample("p1", "Poster Draft", 1080, 1920, 1),
        sample("p2", "Portrait Retouch", 1080, 1350, 2),
        sample("p3", "Logo Concept", 1080, 1080, 3),
        sample("p4", "Landscape Edit", 1920, 1080, 4),
        sample("p5", "Social Banner", 1500, 500, 5),
        sample("p6", "Product Mockup", 1280, 720, 6),
    )

    override fun recent(): List<Project> = items.toList()
    override fun find(id: String?): Project? = items.firstOrNull { it.id == id }

    override fun create(name: String, config: ProjectConfig): Project {
        val project = Project(
            id = UUID.randomUUID().toString(),
            name = name,
            subtitle = "${config.widthPx} × ${config.heightPx} px · ${config.dpi} DPI",
            colorSeed = items.size + 7,
            config = config,
        )
        items.add(0, project)
        return project
    }

    override fun update(project: Project) {
        val i = items.indexOfFirst { it.id == project.id }
        if (i >= 0) items[i] = project
    }
}
