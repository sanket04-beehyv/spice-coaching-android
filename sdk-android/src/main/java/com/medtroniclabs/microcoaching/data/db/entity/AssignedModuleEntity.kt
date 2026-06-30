package com.medtroniclabs.microcoaching.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * Join table mapping a user (CHW) to the module families assigned to them.
 *
 * One row says "module family X is assigned to user Y". The Training Modules
 * screen filters its library to families present here for the current user;
 * the chatbot/BM25 path is unaffected and continues to read the full
 * `module_cache` catalogue.
 *
 * Populated by the "assigned" `/sync/modules` call (the one that carries
 * `user_id`, always `since=EPOCH` so it returns the full assigned snapshot).
 *
 * Keyed on `module_id` — the backend's own unit of assignment
 * (`chw_module_assignment.module_id` → `module.id`) and the one field a future
 * dedicated assigned-modules endpoint is guaranteed to return. `module_family_id`
 * is **nullable**: today's `/sync/modules` payload carries it, but a dedicated
 * endpoint may not. The training filter matches a [LearnModule] by EITHER its
 * `moduleId` or its `moduleFamilyId`, so a family-less row still works.
 *
 * The field is named `user_id` (not `chw_id`) ahead of that dedicated endpoint;
 * today it's populated from `MicroCoachingSDK.currentCHWId`.
 */
@Entity(
    tableName = "assigned_module",
    primaryKeys = ["user_id", "module_id"],
    indices = [
        Index(value = ["user_id"]),
    ],
)
data class AssignedModuleEntity(

    @ColumnInfo(name = "user_id")
    val userId: String,

    /** Backend's unit of assignment (`module.id`, the version UUID). Always present. */
    @ColumnInfo(name = "module_id")
    val moduleId: String,

    /** Stable family key when the payload provides it; null if a future endpoint omits it. */
    @ColumnInfo(name = "module_family_id")
    val moduleFamilyId: String? = null,

    @ColumnInfo(name = "assigned_at")
    val assignedAt: Long? = null,

    @ColumnInfo(name = "last_synced")
    val lastSynced: Long = System.currentTimeMillis(),
)
