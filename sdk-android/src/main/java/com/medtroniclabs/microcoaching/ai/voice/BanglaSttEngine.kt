package com.medtroniclabs.microcoaching.ai.voice

// TODO: Phase H — interface contract for Bangla STT (from PoC-validated pattern)
// Modelled after the SherpaOnnx Zipformer contract validated in gemma-2b-kotlin/ PoC.
// Concrete implementation (SherpaOnnxSttEngine) is deferred — wire in when voice flows are prioritized.
// Distribution note: SherpaOnnx AAR is NOT on Maven Central/JitPack. Fetch from GitHub Releases
// or self-host on private Maven. Decide distribution strategy before implementing concrete class.
// See docs/TIMELINE_v2.md Phase H, gemma-2b-kotlin/docs/MicroCoaching_SDK_PoC_v1.md (STT benchmarks)
// Architecture mandate: docs/new-doc-versions/MicroCoaching_Architecture_v2 6.md Section 16 (REQUIRED)
