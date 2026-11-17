package org.fresnel.backend.measurement.session;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;

import static org.fresnel.backend.measurement.session.MeasurementSessions.bad;

/** Inspect finite MP4 movie/track metadata with bounded reads, without expanding encoded media. */
final class BoundedMp4 {
    private final RandomAccessFile file;
    private int boxes;
    private record Box(String type, long data, long end) {}
    private record Track(int width, int height, boolean video, double seconds) {}

    private BoundedMp4(RandomAccessFile file) { this.file = file; }

    static MeasurementSessions.Asset inspect(Path path, String sha, long bytes) throws IOException {
        try (var file = new RandomAccessFile(path.toFile(), "r")) {
            return new BoundedMp4(file).inspect(sha, bytes);
        } catch (EOFException malformed) {
            throw bad("Incomplete MP4 metadata");
        }
    }
    private MeasurementSessions.Asset inspect(String sha, long bytes) throws IOException {
        boolean fileType = false, media = false;
        Track track = null;
        for (long offset=0; offset<bytes;) {
            Box b = box(offset, bytes);
            if (b.type.equals("ftyp")) fileType = b.end - b.data >= 8;
            if (b.type.equals("mdat")) media |= b.end > b.data;
            if (b.type.equals("moov")) {
                for (long p=b.data; p<b.end;) {
                    Box item = box(p,b.end);
                    if (item.type.equals("trak")) {
                        Track t = track(item);
                        if (t.video) {
                            if (track != null) throw bad("Choose a single-track video");
                            track = t;
                        }
                    }
                    p = item.end;
                }
            }
            offset = b.end;
        }
        if (!fileType || !media || track == null || !(track.seconds > 0 && track.seconds <= 30)
                || track.width < 1 || track.height < 1 || track.width > 4096 || track.height > 4096
                || (long)track.width * track.height > 8_500_000) throw bad("Invalid or out-of-bounds MP4");
        return new MeasurementSessions.Asset(sha, bytes, "video/mp4", track.width, track.height);
    }
    private Track track(Box container) throws IOException {
        int w = 0, h = 0;
        boolean video = false;
        Box mediaInformation = null;
        double seconds = 0;
        for (long p=container.data; p<container.end;) {
            Box b = box(p,container.end);
            if (b.type.equals("tkhd")) {
                if (b.end-b.data < 84) throw bad("Invalid MP4 track header");
                file.seek(b.end-8);
                w = file.readInt() >>> 16;
                h = file.readInt() >>> 16;
            }
            if (b.type.equals("mdia")) {
                for (long q=b.data; q<b.end;) {
                    Box item = box(q,b.end);
                    if (item.type.equals("hdlr") && item.end-item.data >= 12) {
                        file.seek(item.data+8);
                        video = fourcc().equals("vide");
                    }
                    if (item.type.equals("minf")) mediaInformation = item;
                    if (item.type.equals("mdhd")) {
                        file.seek(item.data);
                        int version=file.readUnsignedByte();
                        if (version > 1 || item.end-item.data < (version==0 ? 24 : 36)) throw bad("Invalid MP4 media header");
                        file.seek(item.data + (version==0 ? 12 : 20));
                        long scale = Integer.toUnsignedLong(file.readInt());
                        long ticks = version==0 ? Integer.toUnsignedLong(file.readInt()) : file.readLong();
                        seconds = (double)ticks/scale;
                    }
                    q=item.end;
                }
            }
            p=b.end;
        }
        if (video) inspectSampleDescription(mediaInformation);
        return new Track(w,h,video,seconds);
    }
    private void inspectSampleDescription(Box mediaInformation) throws IOException {
        Box table = child(mediaInformation, "stbl");
        Box descriptions = child(table, "stsd");
        if (descriptions == null || descriptions.end-descriptions.data < 8) throw bad("Missing MP4 sample descriptions");
        file.seek(descriptions.data+4);
        int count=file.readInt();
        if (count != 1) throw bad("Choose an MP4 with a single video format");
        Box sample = box(descriptions.data+8,descriptions.end);
        if (sample.end != descriptions.end || sample.end-sample.data < 78
                || !Set.of("avc1","avc3","hvc1","hev1","vp09","av01","mp4v").contains(sample.type))
            throw bad("Unsupported MP4 video sample description");
        file.seek(sample.data+24);
        int width=file.readUnsignedShort(), height=file.readUnsignedShort();
        if (width == 0 || height == 0 || width > 4096 || height > 4096 || (long)width*height > 8_500_000)
            throw bad("MP4 coded image dimensions exceed limits");
    }
    private Box child(Box parent, String type) throws IOException {
        if (parent == null) return null;
        for (long p=parent.data; p<parent.end;) {
            Box b=box(p,parent.end);
            if (b.type.equals(type)) return b;
            p=b.end;
        }
        return null;
    }
    private Box box(long start, long end) throws IOException {
        if (++boxes > 4096 || end-start < 8) throw bad("Invalid MP4 box layout");
        file.seek(start);
        long size = Integer.toUnsignedLong(file.readInt());
        String type = fourcc();
        if (size == 1) {
            if (end-start < 16) throw bad("Incomplete extended MP4 box");
            size=file.readLong();
        } else if (size == 0) size=end-start;
        long data=file.getFilePointer();
        if (size < data-start || size > end-start) throw bad("Invalid MP4 box size");
        return new Box(type,data,start+size);
    }
    private String fourcc() throws IOException {
        byte[] value = new byte[4]; file.readFully(value);
        return new String(value, StandardCharsets.ISO_8859_1);
    }
}
