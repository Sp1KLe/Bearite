package com.bearite.patcher

/** Константы арен мини-игр (создаются скриптом по тем же картам, что лежат в assets/minigames). */
object MgArenas {
    val PARKOUR_CPS = listOf(Triple(0f,0f,0f),Triple(4.5f,0.4f,32.4f),Triple(4.5f,1f,65.8f),Triple(2f,1f,99.2f),Triple(2f,1.2f,132.6f))
    val PARKOUR_FINISH = Triple(0f,1.3f,151.4f)
    const val PARKOUR_LEN = 151.4
    const val HILL_RADIUS = 8.5f
    const val HILL_TOP = 2.4f
    const val TAG_R = 22f
}
