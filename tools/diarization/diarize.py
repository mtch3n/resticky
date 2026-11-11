# /// script
# requires-python = ">=3.11"
# dependencies = ["sherpa-onnx", "numpy", "soundfile"]
# ///
import argparse
import json
import time

import numpy as np
import sherpa_onnx
import soundfile as sf


def main():
    p = argparse.ArgumentParser()
    p.add_argument("wav")
    p.add_argument("out")
    p.add_argument("--segmentation", required=True)
    p.add_argument("--embedding", required=True)
    p.add_argument("--threshold", type=float, default=0.5)
    p.add_argument("--num-speakers", type=int, default=-1)
    p.add_argument("--threads", type=int, default=4)
    a = p.parse_args()

    audio, sr = sf.read(a.wav, dtype="float32", always_2d=False)
    config = sherpa_onnx.OfflineSpeakerDiarizationConfig(
        segmentation=sherpa_onnx.OfflineSpeakerSegmentationModelConfig(
            pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(model=a.segmentation),
            num_threads=a.threads,
        ),
        embedding=sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=a.embedding, num_threads=a.threads),
        clustering=sherpa_onnx.FastClusteringConfig(num_clusters=a.num_speakers, threshold=a.threshold),
        min_duration_on=0.3,
        min_duration_off=0.5,
    )
    sd = sherpa_onnx.OfflineSpeakerDiarization(config)
    assert sd.sample_rate == sr
    t0 = time.perf_counter()
    result = sd.process(audio).sort_by_start_time()
    elapsed = time.perf_counter() - t0
    segments = [{"start": r.start, "end": r.end, "speaker": r.speaker} for r in result]

    extractor = sherpa_onnx.SpeakerEmbeddingExtractor(
        sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=a.embedding, num_threads=a.threads)
    )
    sums, weights = {}, {}
    for s in segments:
        if s["end"] - s["start"] < 1.5:
            continue
        stream = extractor.create_stream()
        stream.accept_waveform(sr, audio[int(s["start"] * sr) : int(s["end"] * sr)])
        stream.input_finished()
        e = np.array(extractor.compute(stream))
        e /= np.linalg.norm(e)
        w = s["end"] - s["start"]
        sums[s["speaker"]] = sums.get(s["speaker"], 0) + w * e
        weights[s["speaker"]] = weights.get(s["speaker"], 0) + w
    embeddings = {str(k): (v / np.linalg.norm(v)).tolist() for k, v in sums.items()}

    duration = len(audio) / sr
    talk = {}
    for s in segments:
        talk[s["speaker"]] = talk.get(s["speaker"], 0) + s["end"] - s["start"]
    report = {
        "duration": duration,
        "elapsed": elapsed,
        "rtf": elapsed / duration,
        "num_speakers": len(talk),
        "talk_seconds": {str(k): round(v, 1) for k, v in sorted(talk.items())},
        "segments": segments,
        "embeddings": embeddings,
    }
    with open(a.out, "w") as f:
        json.dump(report, f)
    print(json.dumps({k: report[k] for k in ("duration", "elapsed", "rtf", "num_speakers", "talk_seconds")}))


main()
