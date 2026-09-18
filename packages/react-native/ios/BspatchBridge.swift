// Maps the C symbol `bspatch_apply` (from bspatch/bspatch.c) directly into
// Swift without requiring an Objective-C wrapper or a bridging header.
// Called only from applyDeltaPatch() inside NitroPushSdk.
@_silgen_name("bspatch_apply")
func _bspatch_apply(
    _ oldfile: UnsafePointer<CChar>,
    _ patchfile: UnsafePointer<CChar>,
    _ newfile: UnsafePointer<CChar>,
    _ expectedSize: UInt64
) -> Int32
