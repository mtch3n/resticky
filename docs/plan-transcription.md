# Plan: transcription for English, Mandarin and Cantonese (code-switching)

Status: draft, 2026-11-11. User: "sometimes i use both eng and chinese and what is the best way", "think deep and
see what fits us the best and all using best practice". Based on a source review of open models (2025–2026).

## Current defaults (2026-11-11)

The measurement on real Sticky audio (see below) changed the first plan:

- Phone and laptop: Whisper small.en is the default speech engine.
- SenseVoice-Small int8 is the choice for meetings in Mandarin or Cantonese. It spells English badly on Sticky audio.
- Qwen3-ASR and FireRedASR2 are not built yet.

The table in "Decisions" is the first plan from the source review. It is not the current state.

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
the previous text as a prompt. SenseVoice stays as the choice for meetings in Mandarin or Cantonese.

After the engine, the hub applies these rules (2026-11-11). A Chinese character counts as one word.

- The hub removes sound labels and repeated phrases.
- A conversation with 0 to 4 words gets "No speech". A conversation with 5 to 24 words gets the title "Short note",
  and the cleaned text is the summary. The hub does not call the summary model for these.
- Each to-do must quote the transcript, else the hub drops it. The quote must be in the transcript as one span,
  or 90 % of its words must be in the same order within 1.5 × the quote length. The hub keeps the matched text and
  its start time (`todo_quotes`).
- The hub drops a to-do when only the task or only the quote has a negation ("not", "cannot", "n't").
- Speakers from the 20-minute windows join at cosine 0.6 (was 0.95, which gave 17 speakers in a 4-hour meeting).
- A speaker with less than 20 s or 2 % of the talk time joins the closest main voice only at cosine 0.4 or more.
  A speaker with no voice embedding and less than 5 s joins the nearest main speaker in time.
- Two speakers whose turns overlap for more than 1 s never join.
- If the speaker run fails, the hub tries again once. After the second failure, `speakers_failed` is true.

## Evaluation (decides the defaults)

- Personal test set: 30–60 min recorded on the Sticky (same mic, Opus 16 kbps), all three language mixes, 2–4
  speakers, transcribed by hand (colloquial Cantonese, English in Latin script); 5–10 min kept as a fixed
  regression set.
- Metric: MER (Chinese characters + English words), plus CER for Chinese only and WER for English only; same
  normalization on both sides (one script via OpenCC, no punctuation, lowercase, half-width, no model tags,
  numbers and fillers the same). Speaker-aware scores with meeteval.
- Engines in the A/B: SenseVoice-Small, the SenseVoice Cantonese fine-tune (2025-09), Qwen3-ASR-0.6B,
  Qwen3-ASR-1.7B (laptop), FireRedASR2-CTC, Whisper (baseline). Measure RTF on the phone.

## How to build and score the test set

The tools are in `tools/eval/`. They are uv scripts. Run them with `uv run --script`. Do not use pip.

### 1. Get the audio

- Use hub session folders. A session folder has the chunk files `0.opus`, `1.opus`, … and `meta.json`. Each
  chunk file is a list of Opus packets: a 2-byte length (little-endian), then the packet. Each packet is 20 ms,
  16 kHz, mono. A packet with length 0 is 20 ms of silence.
- You can also use WAV or OGG files.
- Record 30–60 min. Use all three language mixes and 2–4 speakers. Also record 10–20 min of noise and music
  without speech.
- Keep 5–10 min as a fixed regression set. Do not change it.

### 2. Make a draft reference

1. Download the large model one time:

   ```
   mkdir -p /tmp/resticky-models
   curl -L -o /tmp/resticky-models/ggml-large-v3-turbo.bin https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo.bin
   ```

2. Make the draft:

   ```
   uv run --script tools/eval/draft.py ~/.local/share/resticky/data/<device>/<session> --out eval/refs
   ```

   Options: `--model PATH` (default `/tmp/resticky-models/ggml-large-v3-turbo.bin`), `--language en|zh|yue|auto`
   (default `auto`), `--threads N`. For a session folder, the script joins the chunks in sequence order. A missing
   chunk becomes silence of its `chunk_ms` length. Thus the times in the draft are the same as the session times on
   the hub.

3. The script writes two files: `<name>.wav` (16 kHz mono) and `<name>.ref.txt`.

### 3. Correct the draft

Each line of `<name>.ref.txt` is `START END SPEAKER | TEXT`, for example:

```
00:01:02.500 00:01:06.120 S1 | 我哋聽日 send the slides 啦
00:01:06.200 00:01:30.000 - | <noise>
# todo: Send the slides to Anna
```

- Listen to the WAV file and correct each word. Write what the speaker says. Write colloquial Cantonese
  characters (嘅, 喺, 咗). Write English words in Latin script. Do not translate.
- Change the speaker `S?` to `S1`, `S2`, … Use the same label for the same person in the full file.
- Split a line where the speaker changes.
- Write `<noise>` as the text of a span that has only noise or music.
- Add one `# todo:` line for each to-do that a person in the meeting asks for or promises.
- Lines that start with `#` are comments.

### 4. Score the hub

```
uv run --script tools/eval/score.py \
  --pair eval/refs/<session>.ref.txt ~/.local/share/resticky/data/<device>/<session>/meta.json \
  --pair eval/refs/<other>.ref.txt ~/.local/share/resticky/data/<device>/<other>/meta.json
```

Add `--json` to get the totals as JSON. The script prints one line for each pair and one total line.

| Metric | What it measures |
|---|---|
| `mer` | Mixed error rate. Each Chinese character is one token. Each English word is one token. |
| `wer` | Word error rate on the English tokens only. |
| `cer` | Character error rate on the Chinese tokens only. |
| `hallucinated_words_per_hour` | Hub words in the `<noise>` spans, for each hour of noise. |
| `der` | Diarization error rate (missed speech + false alarm + speaker confusion), 10 ms frames, no collar. |
| `cpwer` | Concatenated minimum-permutation word error rate (meeteval), with the mixed tokens. |
| `todo_precision`, `todo_recall` | Hub to-dos against the `# todo:` lines. Two to-dos match when their text similarity is 0.5 or more. |

Normalization is the same on both sides: NFKC (full-width to half-width), OpenCC Traditional to Simplified,
lowercase, no punctuation, no model tags (`<|en|>`, `[BLANK_AUDIO]`, `(music)`). The script does not change
numbers or fillers. Thus write numbers and fillers in the reference as the speaker says them.

The script does not calculate `der` and `cpwer` for a file that has a speaker `S?` in it.

### 5. Compare engines

1. Set the speech model on the phone (Models › Speech-to-text).
2. On the phone, run all sessions again (adb: `--es pipeline rerun`).
3. Copy the new `meta.json` files and score them. Keep one copy of `meta.json` for each engine.
4. Read the time for each 30 s chunk in the phone log (`run job=speech … seconds=`). The `speech` log line
   shows the windows and the engine for each window (`routes=whisper:en,sensevoice:yue`).

## Speech windows (phone and laptop)

- The transcriber decodes the chunk and the last 2 s of the previous chunk (`<seq-1>.opus`) as left context.
- Silero VAD finds the speech. Each speech part gets 0.25 s padding. The parts go into windows of 28 s or less.
  A window ends between two speech parts, thus not in a word. Noise between the speech parts does not go to the
  engine.
- The engine transcribes each window. The times go back to the chunk timeline. A word whose middle is in the left
  context belongs to the previous chunk. The transcriber removes it.
- A chunk with less than 0.5 s of speech is not transcribed.
- Right edge: the transcriber does not wait for the next chunk. Thus a word that crosses the end of the chunk can
  be cut. The next chunk then has the full word, because its middle is after the chunk boundary. Thus the
  transcript can show a part of a word and then the full word.
- Auto (English + Chinese), the phone default: SenseVoice reads each window and gives the language. A window in
  English without Chinese characters goes to Whisper small.en. Other windows (zh, yue, mixed) keep the SenseVoice
  text. Until the SenseVoice model is on the phone, all windows go to Whisper.

## Vocabulary (phone and laptop)

Whisper spells names and work words incorrectly ("Christa" for "Krista", "QI" for "QA"). The vocabulary corrects
this in two steps.

- Store: `vocabulary.json` next to the `data` folder (phone: app files; laptop: `~/.local/share/resticky/`). It
  has terms (`text`, `source`: `user`, `correction`, `calendar` or `people`, `count`, `last_used`) and rules
  (`from` → `to`). The store keeps 300 terms at most. When it has more, it removes the non-user terms that have the
  oldest `last_used`. It never removes user terms.
- Prompt: for each chunk, the hub makes a Whisper prompt in this order: user terms (newest first), people names,
  titles of calendar events that overlap the session, correction terms (most used first). Each term occurs one
  time. The hub stops at a cost of 400 (1 for each ASCII character, 3 for each other character, 2 for each
  separator). This keeps the prompt below the Whisper limit of 224 tokens. Whisper.cpp keeps only the last
  tokens of a prompt that is too long, thus the hub must cut it. The phone sends it as `initial_prompt`
  (`no_context` stays true); the laptop sends `--prompt` to `whisper-cli`. SenseVoice has no prompt.
- Calendar: the phone reads the titles of the events in the selected calendars from the session start to the
  session end. Each title becomes a `calendar` term. Calendars.kt does not read attendees.
- People: the pipeline gets the names from `extraNames` (empty until the people library connects to it).
- Rules: after the clean-up step and before the summary, the hub applies each rule to the segment words and the
  text. A rule matches whole words, ignores case, keeps the punctuation around the word ("Christa," → "Krista,")
  and can have more than one word. The chunk records the number of changes in `chunk_rules` (key: the chunk). The
  hub does not keep the engine text.
- Correction on the phone: long-press a word in the transcript. The sheet shows the heard word and the correct
  word. "Fix" changes all equal words in this conversation, adds the rule and adds the correct word as a
  `correction` term. "Only here" changes only the line and adds the term, but no rule. The conversation then
  gets a new summary because its input changed.
- App settings › Vocabulary: shows the fixes and the words, adds a word (`user`) and deletes a word or a fix.

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
