import CaptionsCore

/// Copies between Swift arrays and Kotlin FloatArrays in one memcpy (see core/src/iosMain IosBridge.kt).
enum FloatArrays {
    static func toKotlin(_ samples: [Float]) -> KotlinFloatArray {
        var copy = samples
        return copy.withUnsafeMutableBufferPointer { buf in
            guard let base = buf.baseAddress, buf.count > 0 else { return KotlinFloatArray(size: 0) }
            return IosBridgeKt.floatArrayFromPointer(src: base, count: Int32(buf.count))
        }
    }

    static func toSwift(_ array: KotlinFloatArray) -> [Float] {
        let n = Int(array.size)
        guard n > 0 else { return [] }
        var out = [Float](repeating: 0, count: n)
        out.withUnsafeMutableBufferPointer { buf in
            IosBridgeKt.writeFloatArrayToPointer(array: array, dst: buf.baseAddress!)
        }
        return out
    }
}
