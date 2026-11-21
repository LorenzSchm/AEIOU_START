package ai.aeiou.edgelab.photo;

import static org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_free;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_unref;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_open2;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_to_context;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet;
import static org.bytedeco.ffmpeg.global.avformat.AV_STREAM_GROUP_PARAMS_TILE_GRID;
import static org.bytedeco.ffmpeg.global.avformat.av_read_frame;
import static org.bytedeco.ffmpeg.global.avformat.avformat_close_input;
import static org.bytedeco.ffmpeg.global.avformat.avformat_open_input;
import static org.bytedeco.ffmpeg.global.avutil.AV_LOG_QUIET;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_alloc;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_free;
import static org.bytedeco.ffmpeg.global.avutil.av_log_set_level;
import static org.bytedeco.ffmpeg.global.swscale.SWS_BILINEAR;
import static org.bytedeco.ffmpeg.global.swscale.sws_freeContext;
import static org.bytedeco.ffmpeg.global.swscale.sws_getContext;
import static org.bytedeco.ffmpeg.global.swscale.sws_scale;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVStreamGroup;
import org.bytedeco.ffmpeg.avformat.AVStreamGroupTileGrid;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;

/**
 * HEIC / HEIF (and other still images only ffmpeg knows) to a BufferedImage, with the
 * ffmpeg bundled in JavaCV. iPhone photos are a grid of 512x512 HEVC tiles; ffmpeg 7.1
 * reports the grid as a stream group, and the tiles are stitched here. A plain HEIF uses
 * its default stream, not the thumbnail. One pass over the file decodes every needed stream.
 */
final class HeicDecoder {

    private HeicDecoder() {}

    static BufferedImage decode(byte[] bytes) throws IOException {
        // The HEIF demuxer needs to seek; a temp file is the reliable way in.
        Path tmp = Files.createTempFile("edgelab-photo-", ".heic");
        try {
            Files.write(tmp, bytes);
            return decode(tmp.toString());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static BufferedImage decode(String file) throws IOException {
        av_log_set_level(AV_LOG_QUIET);
        AVFormatContext ctx = new AVFormatContext(null);
        if (avformat_open_input(ctx, file, null, null) < 0) throw new IOException("ffmpeg cannot open this image");
        try {
            for (int g = 0; g < ctx.nb_stream_groups(); g++) {
                AVStreamGroup group = ctx.stream_groups(g);
                if (group.type() == AV_STREAM_GROUP_PARAMS_TILE_GRID) return stitch(ctx, group);
            }
            int best = -1;
            long bestPixels = -1;
            for (int i = 0; i < ctx.nb_streams(); i++) {
                var par = ctx.streams(i).codecpar();
                if (par.width() <= 0) continue;
                long pixels = (long) par.width() * par.height();
                if ((ctx.streams(i).disposition() & 1) != 0) pixels += Long.MAX_VALUE / 2; // default stream wins
                if (pixels > bestPixels) {
                    best = i;
                    bestPixels = pixels;
                }
            }
            if (best < 0) throw new IOException("no picture found in the file");
            BufferedImage img = decodeStreams(ctx, new int[] {best}).get(best);
            if (img == null) throw new IOException("ffmpeg decoded no picture");
            return img;
        } finally {
            avformat_close_input(ctx);
        }
    }

    private static BufferedImage stitch(AVFormatContext ctx, AVStreamGroup group) throws IOException {
        AVStreamGroupTileGrid grid = group.params_tile_grid();
        int width = grid.width();
        int height = grid.height();
        if (width <= 0 || height <= 0 || (long) width * height > 80_000_000L) {
            throw new IOException("HEIC grid has an odd size " + width + "x" + height);
        }
        int tiles = grid.nb_tiles();
        int[] streams = new int[tiles];
        for (int t = 0; t < tiles; t++) streams[t] = group.streams(grid.offsets_idx(t)).index();
        Map<Integer, BufferedImage> decoded = decodeStreams(ctx, streams);
        BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            for (int t = 0; t < tiles; t++) {
                BufferedImage tile = decoded.get(streams[t]);
                if (tile == null) throw new IOException("HEIC tile " + t + " could not be decoded");
                g.drawImage(tile, grid.offsets_horizontal(t) - grid.horizontal_offset(),
                        grid.offsets_vertical(t) - grid.vertical_offset(), null);
            }
        } finally {
            g.dispose();
        }
        return canvas;
    }

    /** Reads the file once and decodes the first picture of each wanted stream. */
    private static Map<Integer, BufferedImage> decodeStreams(AVFormatContext ctx, int[] wanted) throws IOException {
        Map<Integer, AVCodecContext> decoders = new HashMap<>();
        Map<Integer, BufferedImage> out = new HashMap<>();
        AVPacket pkt = av_packet_alloc();
        AVFrame frame = av_frame_alloc();
        try {
            for (int s : wanted) {
                if (decoders.containsKey(s)) continue;
                var par = ctx.streams(s).codecpar();
                AVCodec codec = avcodec_find_decoder(par.codec_id());
                if (codec == null) throw new IOException("no decoder for this image codec");
                AVCodecContext dec = avcodec_alloc_context3(codec);
                decoders.put(s, dec);
                dec.thread_count(1); // tiles are tiny; per-decoder thread pools cost more than they save
                if (avcodec_parameters_to_context(dec, par) < 0 || avcodec_open2(dec, codec, (AVDictionary) null) < 0) {
                    throw new IOException("image decoder could not be opened");
                }
            }
            while (out.size() < decoders.size() && av_read_frame(ctx, pkt) >= 0) {
                int s = pkt.stream_index();
                AVCodecContext dec = decoders.get(s);
                if (dec != null && !out.containsKey(s)) {
                    // One packet is one picture: send it, flush, take the frame.
                    if (avcodec_send_packet(dec, pkt) >= 0) {
                        avcodec_send_packet(dec, null);
                        if (avcodec_receive_frame(dec, frame) >= 0) out.put(s, toImage(frame));
                    }
                }
                av_packet_unref(pkt);
            }
            return out;
        } finally {
            for (AVCodecContext dec : decoders.values()) avcodec_free_context(dec);
            av_frame_free(frame);
            av_packet_free(pkt);
        }
    }

    /** Any pixel format (8/10-bit YUV, ...) to a BGR BufferedImage through swscale. */
    private static BufferedImage toImage(AVFrame frame) throws IOException {
        int w = frame.width();
        int h = frame.height();
        SwsContext sws = sws_getContext(w, h, frame.format(), w, h, AV_PIX_FMT_BGR24, SWS_BILINEAR,
                null, null, (DoublePointer) null);
        if (sws == null) throw new IOException("unsupported pixel format in image");
        BytePointer buf = new BytePointer((long) w * h * 3);
        try (PointerPointer<BytePointer> dst = new PointerPointer<>(buf, null, null, null);
             IntPointer lines = new IntPointer(w * 3, 0, 0, 0)) {
            sws_scale(sws, frame.data(), frame.linesize(), 0, h, dst, lines);
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
            buf.get(((DataBufferByte) img.getRaster().getDataBuffer()).getData());
            return img;
        } finally {
            sws_freeContext(sws);
            buf.close();
        }
    }
}
