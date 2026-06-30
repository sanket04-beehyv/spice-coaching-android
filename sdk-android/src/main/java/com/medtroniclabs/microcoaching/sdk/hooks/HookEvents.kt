package com.medtroniclabs.microcoaching.sdk.hooks

// TODO: Phase E — internal sealed class for all hook-triggered events
// Events passed from SpiceHookAdapter through CoachingDecisionEngine.
// Examples:
//   MorningOpen(chwId: String, pendingPatients: Int?)
//   AssessmentReady(assessmentData: Map<String, Any>)
//   RiskFlagObserved(riskLevel: String, patientId: String)
//   VisitCompleted(encounterId: String)
//   ConnectivityChanged(isOnline: Boolean)
// See docs/TIMELINE_v2.md Phase E, docs/SDK_Implementation_Realignment_v1.md Section 7 Phase E
