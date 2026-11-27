import Foundation

/// Extracts chosen files from a .tar.bz2 archive using the SDK's libbz2 and a small tar reader
/// (ustar, GNU long names and pax path records). Entries are matched by file name, ignoring folders.
enum TarBz2 {
    static func extract(archive: URL, entries: [String: String], to directory: URL) throws {
        let tar = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".tar")
        defer { try? FileManager.default.removeItem(at: tar) }
        try decompress(archive, to: tar)
        try untar(tar, entries: entries, to: directory)
    }

    static func decompress(_ input: URL, to output: URL) throws {
        let reader = try FileHandle(forReadingFrom: input)
        defer { try? reader.close() }
        FileManager.default.createFile(atPath: output.path, contents: nil)
        let writer = try FileHandle(forWritingTo: output)
        defer { try? writer.close() }

        var stream = bz_stream()
        guard BZ2_bzDecompressInit(&stream, 0, 0) == BZ_OK else { throw ModelError.archive("bzip2 init") }
        defer { BZ2_bzDecompressEnd(&stream) }

        let outSize = 1 << 20
        var out = [CChar](repeating: 0, count: outSize)
        while let chunk = try reader.read(upToCount: 1 << 20), !chunk.isEmpty {
            var input = [CChar](repeating: 0, count: chunk.count)
            chunk.withUnsafeBytes { raw in
                input.withUnsafeMutableBytes { $0.copyMemory(from: raw) }
            }
            var offset = 0
            while offset < input.count {
                let (consumed, ended) = try input.withUnsafeMutableBufferPointer { inBuf -> (Int, Bool) in
                    try out.withUnsafeMutableBufferPointer { outBuf -> (Int, Bool) in
                        stream.next_in = inBuf.baseAddress! + offset
                        stream.avail_in = UInt32(inBuf.count - offset)
                        stream.next_out = outBuf.baseAddress
                        stream.avail_out = UInt32(outSize)
                        let ret = BZ2_bzDecompress(&stream)
                        guard ret == BZ_OK || ret == BZ_STREAM_END else { throw ModelError.archive("bzip2 \(ret)") }
                        let produced = outSize - Int(stream.avail_out)
                        if produced > 0 {
                            writer.write(Data(bytes: outBuf.baseAddress!, count: produced))
                        }
                        return (inBuf.count - offset - Int(stream.avail_in), ret == BZ_STREAM_END)
                    }
                }
                offset += consumed
                if ended {
                    // Archives made by parallel bzip2 tools hold several streams back to back.
                    BZ2_bzDecompressEnd(&stream)
                    stream = bz_stream()
                    guard BZ2_bzDecompressInit(&stream, 0, 0) == BZ_OK else { throw ModelError.archive("bzip2 init") }
                }
            }
        }
        // Flush output bzip2 still holds after the last input chunk.
        while true {
            let produced = out.withUnsafeMutableBufferPointer { outBuf -> Int in
                stream.avail_in = 0
                stream.next_out = outBuf.baseAddress
                stream.avail_out = UInt32(outSize)
                _ = BZ2_bzDecompress(&stream)
                let n = outSize - Int(stream.avail_out)
                if n > 0 { writer.write(Data(bytes: outBuf.baseAddress!, count: n)) }
                return n
            }
            if produced == 0 { break }
        }
    }

    static func untar(_ tar: URL, entries: [String: String], to directory: URL) throws {
        let handle = try FileHandle(forReadingFrom: tar)
        defer { try? handle.close() }
        var remaining = entries
        var longName: String?

        while !remaining.isEmpty {
            guard let header = try handle.read(upToCount: 512), header.count == 512 else { break }
            if header.allSatisfy({ $0 == 0 }) { break } // end of archive
            let bytes = [UInt8](header)
            let size = Int(octal(bytes[124..<136]))
            let type = bytes[156]
            var name = longName ?? string(bytes[0..<100])
            longName = nil
            let magic = string(bytes[257..<263])
            if magic == "ustar", bytes[262] == 0 { // POSIX ustar: optional prefix
                let prefix = string(bytes[345..<500])
                if !prefix.isEmpty, name == string(bytes[0..<100]) { name = prefix + "/" + name }
            }
            let padded = (size + 511) / 512 * 512

            switch type {
            case UInt8(ascii: "L"): // GNU long name for the next entry
                let data = try handle.read(upToCount: padded) ?? Data()
                longName = string([UInt8](data.prefix(size))[...])
                continue
            case UInt8(ascii: "x"): // pax header; may carry the next entry's path
                let data = try handle.read(upToCount: padded) ?? Data()
                let text = String(decoding: data.prefix(size), as: UTF8.self)
                for record in text.split(separator: "\n") {
                    if let r = record.range(of: " path=") { longName = String(record[r.upperBound...]) }
                }
                continue
            default:
                break
            }

            let base = (name as NSString).lastPathComponent
            if type == UInt8(ascii: "0") || type == 0, let local = remaining[base] {
                let dest = directory.appendingPathComponent(local)
                let part = dest.appendingPathExtension("part")
                FileManager.default.createFile(atPath: part.path, contents: nil)
                let writer = try FileHandle(forWritingTo: part)
                var left = size
                while left > 0 {
                    guard let data = try handle.read(upToCount: min(left, 4 << 20)), !data.isEmpty else {
                        try? writer.close()
                        throw ModelError.archive("檔案被截斷")
                    }
                    writer.write(data)
                    left -= data.count
                }
                try writer.close()
                try? FileManager.default.removeItem(at: dest)
                try FileManager.default.moveItem(at: part, to: dest)
                remaining[base] = nil
                if padded > size { _ = try handle.read(upToCount: padded - size) }
            } else {
                try handle.seek(toOffset: try handle.offset() + UInt64(padded))
            }
        }
        if !remaining.isEmpty { throw ModelError.archive("找不到 \(remaining.keys.sorted().joined(separator: ", "))") }
    }

    private static func string(_ bytes: ArraySlice<UInt8>) -> String {
        let end = bytes.firstIndex(of: 0) ?? bytes.endIndex
        return String(decoding: bytes[bytes.startIndex..<end], as: UTF8.self)
    }

    private static func octal(_ bytes: ArraySlice<UInt8>) -> Int64 {
        var v: Int64 = 0
        for b in bytes {
            if b == 0 || b == UInt8(ascii: " ") { if v > 0 { break } else { continue } }
            guard b >= UInt8(ascii: "0"), b <= UInt8(ascii: "7") else { break }
            v = v * 8 + Int64(b - UInt8(ascii: "0"))
        }
        return v
    }
}
