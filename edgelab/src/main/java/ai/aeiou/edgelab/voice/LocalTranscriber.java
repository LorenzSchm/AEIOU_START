package ai.aeiou.edgelab.voice;

import java.util.List;

/** Speech to text on this machine (Whisper in production). A seam so tests can make it fail. */
@FunctionalInterface
public interface LocalTranscriber {

    List<WhisperTranscriber.Segment> transcribe(float[] samples) throws Exception;
}
