package com.medtroniclabs.microcoaching.domain.telemetry

// TODO: Phase F — records digital_proficiency_events to Room
// Signals: sync_attempt (success/failure), form_submit (success/failure), digital_help_used.
// Feeds digital_proficiency_score in chw_gap_profile_local → drives SPICE digital track in UC-1.
// Keep lightweight — runs on every form submission; no blocking IO on main thread.
// See docs/TIMELINE_v2.md Phase F, docs/new-doc-versions/MicroCoaching_DataDesign_v1.1 7.md Section 4.3
