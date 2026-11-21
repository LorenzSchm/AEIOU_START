package ai.aeiou.edgelab.voice;

import java.nio.ShortBuffer;
import java.nio.file.Path;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

/**
 * Turns the Windows-voice wav fixtures into mp3 with the bundled ffmpeg. Run once by hand;
 * the wav files come from PowerShell's System.Speech (see the README, "Voice fixtures").
 *
 *   java -cp <test runtime classpath> ai.aeiou.edgelab.voice.MakeVoiceFixtures src/test/resources/voice
 */
public final class MakeVoiceFixtures {

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "src/test/resources/voice");
        for (String name : new String[] {"rfq-en", "trucker-offer-de"}) {
            toMp3(dir.resolve(name + ".wav"), dir.resolve(name + ".mp3"));
        }
    }

    static void toMp3(Path wav, Path mp3) throws Exception {
        try (FFmpegFrameGrabber in = new FFmpegFrameGrabber(wav.toFile())) {
            in.setSampleMode(org.bytedeco.javacv.FrameGrabber.SampleMode.SHORT);
            in.start();
            try (FFmpegFrameRecorder out = new FFmpegFrameRecorder(mp3.toFile(), 1)) {
                out.setFormat("mp3");
                out.setAudioCodec(avcodec.AV_CODEC_ID_MP3);
                out.setSampleRate(in.getSampleRate());
                out.setAudioBitrate(48_000);
                out.start();
                Frame f;
                while ((f = in.grabSamples()) != null) {
                    if (f.samples != null) out.recordSamples(in.getSampleRate(), 1, (ShortBuffer) f.samples[0]);
                }
                out.stop();
            }
            in.stop();
        }
        System.out.println("wrote " + mp3);
    }
}
