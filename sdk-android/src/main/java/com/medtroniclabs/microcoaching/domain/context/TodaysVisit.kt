package com.medtroniclabs.microcoaching.domain.context

import kotlinx.serialization.Serializable

/**
 * One patient visit/appointment the CHW has scheduled for **today**, pushed in by
 * the SPICE host (from its `FollowUp` rows due today) via
 * [com.medtroniclabs.microcoaching.MicroCoachingSDK.onTodaysVisitsUpdated].
 *
 * Used as a cold-start refresher source: when the CHW has no behavioural-gap picks
 * and no backend morning cards, the on-device generator matches these visits to
 * coaching modules through the synced `workflow_event` trigger bindings
 * (`spice_event_code = "assessment_due"`, `filter_predicate.match` on
 * [encounterType]/[isPregnant]).
 *
 * No patient identifiers — only the clinical-type signal needed to pick a module.
 *
 * @param type SPICE `AppointmentType` (`HH_VISIT` | `MEDICAL_REVIEW` | `REFERRED` | …).
 * @param encounterType the visit's clinical encounter type (`ANC` | `MALARIA` |
 *   `PNC_MOTHER` | …); matched against the trigger's `encounter_type_any`. Null when unset.
 * @param dueDateIso the visit's due date (ISO 8601); the SDK keeps only those == today.
 * @param isPregnant whether the patient is currently pregnant; matched against the
 *   trigger's `is_pregnant`. Null = unknown (the constraint is skipped, not failed).
 * @param villageId optional village id of the visit.
 */
@Serializable
data class TodaysVisit(
    val type: String,
    val encounterType: String? = null,
    val dueDateIso: String,
    val isPregnant: Boolean? = null,
    val villageId: String? = null,
)
