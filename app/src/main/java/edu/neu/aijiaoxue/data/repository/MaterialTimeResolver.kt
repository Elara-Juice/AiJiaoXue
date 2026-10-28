package edu.neu.aijiaoxue.data.repository

import edu.neu.aijiaoxue.data.entity.Material
import edu.neu.aijiaoxue.data.model.MaterialType

/** Imported audio has file time only. Its legacy offset is never classroom evidence. */
object MaterialTimeResolver {
    fun classroomOffsetMs(material: Material): Long? =
        if (material.type == MaterialType.AUDIO && material.isImported) null else material.offsetMs
}
