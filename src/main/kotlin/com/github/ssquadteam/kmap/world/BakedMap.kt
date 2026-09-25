package com.github.ssquadteam.kmap.world

import java.io.File

class BakedMap(
    val worldName: String,
    val originX: Int,
    val originZ: Int,
    val widthPx: Int,
    val heightPx: Int,
    val blocksPerPx: Double,
    val sprite: String,
    val file: File,
)
