# Plan: transcription for English, Mandarin and Cantonese (code-switching)

Status: draft, 2026-11-11. User: "sometimes i use both eng and chinese and what is the best way", "think deep and
see what fits us the best and all using best practice". Based on a source review of open models (2025–2026).

## Decisions

1. **One code-switch model, no language routing.** People switch language inside one sentence, so per-segment
   language ID cannot choose one model. SenseVoice, Qwen3-ASR and FireRedASR2 decode mixed speech without a
   language flag. Language ID stays as metadata only.
2. **Engines** (all on device, no cloud):

   | Where | Default | Higher accuracy | Why |
   |---|---|---|---|
   | Phone | SenseVoice-Small int8 (228 MB, sherpa-onnx, auto language, ITN on) | Qwen3-ASR-0.6B int8 (sherpa-onnx, ~940 MB) | SenseVoice: best published Mandarin–English zero-shot result (CS-Dialogue MER 6.71), per-character timestamps, punctuation, fast, same runtime as diarization |
   | Laptop | SenseVoice-Small (sherpa-onnx JVM) for drafts | Qwen3-ASR-1.7B + Qwen3-ForcedAligner-0.6B, after the meeting while charging | Qwen3-ASR-1.7B: best published Cantonese+English result (CER 15.38 vs 20.49 SenseVoice), Apache-2.0, best aligner |
   | Both | Whisper only for English-only meetings | — | Whisper large-v3/turbo is much worse on Cantonese and code-switching |

3. **Timestamps:** characters for Chinese, words for English. SenseVoice gives token timestamps. For engines
   without timestamps (Qwen3-ASR in sherpa-onnx) the aligner (laptop) or a timestamp transfer from SenseVoice
   (phone) gives them.
4. **Biasing:** a vocabulary list (names, products, English terms) per user, passed as Qwen3-ASR hotwords or a
   Whisper prompt. It can add errors, so it is measured like any other change.
5. **No free LLM rewrite.** The transcript stays verbatim with its timestamps. An optional narrow correction pass
   (listed terms and punctuation only) shows its changes as a diff and never translates, never converts
   Cantonese to standard written Chinese.
6. **Script:** store the raw output; the display copy uses OpenCC (Hong Kong Traditional `s2hk`, Taiwan `s2twp`
   or Simplified), a user setting. Colloquial Cantonese characters (嘅, 喺, 咗) stay.
7. **Fillers:** marked on tokens, not deleted. Chinese and Cantonese lists (嗯, 呃, 啊, 那个, 就是, 呢, 嗰個,
   即係, 咁, 嘛, 囉) only when short and standing alone (time and duration rule), because some are also words.
8. **VAD in front of the ASR:** segments under 20–30 s, merge segments under 2 s, 0.2 s padding. LLM-based
   models can invent text on silence.

## Measured on real Sticky audio (2026-11-11)

Six 30 s pieces of a real English meeting (Sticky mic, Opus 16 kbps), three with speech, three with music or noise:

| Engine | Speech pieces | Music and noise pieces |
|---|---|---|
| Whisper base.en q8 (old default) | Good, some wrong words ("pulled me out", "theold disk … U.S. means") | Invented loops on the phone ("I'm going to go over the rest of my room" ×6) |
| Whisper small.en q8 | Best ("pointed me out", "creating the UI screens and all the integration") | One short word |
| SenseVoice int8 2025-09-09 | Bad English spelling ("DASHWARD", "PERATIONAL", "PORNTD") | Empty |

Decision: Whisper small.en is the default (about 9 s per 30 s piece on the phone). The Silero speech detector runs
first and a piece with less than 1 s of speech is not transcribed. Whisper drops non-speech tokens and does not use
the previous text as a prompt. SenseVoice stays as the choice for meetings in Mandarin or Cantonese. After the engine,
the hub removes sound labels and repeated phrases, sessions with fewer than 25 words (Chinese characters count as
words) get "No speech" without a summary, and each to-do must quote the transcript or it is dropped. Speakers from the
20-minute windows join at cosine 0.6 (was 0.95, which gave 17 speakers in a 4-hour meeting), and a speaker with less
than 20 s or 2 % of the talk time joins the closest main voice.

## Evaluation (decides the defaults)

- Personal test set: 30–60 min recorded on the Sticky (same mic, Opus 16 kbps), all three language mixes, 2–4
  speakers, transcribed by hand (colloquial Cantonese, English in Latin script); 5–10 min kept as a fixed
  regression set.
- Metric: MER (Chinese characters + English words), plus CER for Chinese only and WER for English only; same
  normalization on both sides (one script via OpenCC, no punctuation, lowercase, half-width, no model tags,
  numbers and fillers the same). Speaker-aware scores with meeteval.
- Engines in the A/B: SenseVoice-Small, the SenseVoice Cantonese fine-tune (2025-09), Qwen3-ASR-0.6B,
  Qwen3-ASR-1.7B (laptop), FireRedASR2-CTC, Whisper (baseline). Measure RTF on the phone.

## Risks

- No public benchmark matches our audio (far-field PDM mic, Opus 16 kbps); only the personal set decides.
- No published Mandarin–English code-switch numbers for Qwen3-ASR or FireRedASR2.
- SenseVoice uses the FunASR model license, not Apache-2.0: read it before shipping.
- Phone speeds are estimates from other CPUs until measured.

## Build order

1. Engine interface for sherpa-onnx offline ASR (SenseVoice first) on phone and laptop, with token timestamps
   into the existing word model; Models › Speech choice.
2. Evaluation tool (MER, normalization, per-engine report) and the recording guide for the personal set.
3. Script setting (OpenCC) and Chinese filler rules; vocabulary list.
4. Qwen3-ASR-0.6B on the phone and Qwen3-ASR-1.7B + aligner on the laptop as the high-accuracy path.
5. CJK font on the Sticky so mixed titles, summaries and to-dos display.
