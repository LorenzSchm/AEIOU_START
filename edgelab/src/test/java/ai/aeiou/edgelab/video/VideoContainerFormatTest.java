package ai.aeiou.edgelab.video;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import ai.aeiou.edgelab.EdgeReader;
import ai.aeiou.edgelab.Reading;
import ai.aeiou.edgelab.Router;
import ai.aeiou.edgelab.voice.AudioDecoder;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VideoContainerFormatTest {
    @Test
    void audioReaderCanSeekBackFromAnMp4TrackIndex(@TempDir Path dir) throws Exception {
        Path clip = dir.resolve("voice.mp4");
        try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(clip.toFile(), 1)) {
            recorder.setFormat("mp4");
            recorder.setSampleRate(16000);
            recorder.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
            recorder.start();
            for (int i = 0; i < 16; i++) recorder.recordSamples(16000, 1, ShortBuffer.allocate(1024));
            recorder.stop();
        }
        AudioDecoder.Result result = new AudioDecoder(3).decode(Files.readAllBytes(clip));
        assertTrue(result.ok(), result.problem());
        assertTrue(result.seconds() >= 1);
    }

    @Test
    void samplesPicturesAndReadsSoundOnce() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (FFmpegFrameRecorder video = new FFmpegFrameRecorder(bytes, 64, 48, 1);
             Java2DFrameConverter converter = new Java2DFrameConverter()) {
            video.setFormat("avi");
            video.setVideoCodec(avcodec.AV_CODEC_ID_MJPEG);
            video.setAudioCodec(avcodec.AV_CODEC_ID_PCM_S16LE);
            video.setSampleRate(8000);
            video.setFrameRate(4);
            video.start();
            BufferedImage frame = new BufferedImage(64, 48, BufferedImage.TYPE_3BYTE_BGR);
            var graphics = frame.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 32, 48);
            graphics.dispose();
            for (int i = 0; i < 8; i++) {
                video.record(converter.convert(frame));
                video.recordSamples(8000, 1, ShortBuffer.allocate(2000));
            }
            video.stop();
        }
        AtomicInteger frames = new AtomicInteger();
        AtomicInteger audio = new AtomicInteger();
        VideoReader video = new VideoReader(new FrameSampler(), (picture, type) -> {
            assertEquals(64, picture.getWidth());
            frames.incrementAndGet();
            return Reading.ok("weight 240 kg", 0.8, type);
        }, (name, clip, type) -> {
            audio.incrementAndGet();
            return Reading.ok("pickup Monday", 0.9, type);
        });
        Reading result = new EdgeReader(Router.empty().withVideo(video), EdgeReader.DEFAULT_MAX_BYTES)
                .read("clip.avi", bytes.toByteArray());
        assertEquals(Reading.Status.OK, result.status(), result.notes().toString());
        assertTrue(frames.get() >= 1 && frames.get() <= FrameSampler.MAX_FRAMES);
        assertEquals(1, audio.get());
        assertTrue(result.text().contains("240 kg"));
        assertTrue(result.text().contains("Monday"));
    }
}
