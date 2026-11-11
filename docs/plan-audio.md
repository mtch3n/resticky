# Plan: audio quality (pre-processing and post-processing)

Status: draft, 2026-11-10. User decision: "one is the post processing and one is pre procession ... the
enhancement and de noise or clear voice should be the post processing that can do things like make the
recording crisp or filter noise and filler word cleanup".

## Principles

- Pre-processing on the Sticky keeps the original recording honest and clean. It does only what cannot be
  done later. No noise suppression on the device: what the device removes is lost.
- Post-processing on the hub improves copies. The original audio and the verbatim transcript always stay,
  so a better method can run again on old recordings.
- Measure each change on recordings with a known text before it becomes the default.

## Pre-processing (Sticky, at capture)

| Step | Why | Cost |
|---|---|---|
| High-pass filter, about 80 Hz | Removes DC, rumble, desk knocks and handling noise; speech has no energy there | A few multiplies per sample |
| Fixed gain, set from measurements | Peaks at about −6 dBFS, quiet far speakers still above the noise floor; no automatic gain (it pumps and confuses ASR) | None |
| Level telemetry per chunk: peak, RMS, clipped samples, noise floor | Shows clipping and too-quiet audio in the data, not by ear | Small |
| Opus settings test: 16 vs 24 kbps, application VOIP vs AUDIO | The codec is the biggest loss in the chain; post-processing works better on more detail | Bandwidth and battery; measure |
| Voice detection marks only (shadow mode) | Gating drops audio for ever; enable only after the marks are validated | Already built (0.9.0) |

## Using the ESP32-S3 processing power

The ESP32-S3 has two 240 MHz cores with vector instructions (PIE, 128-bit SIMD). `esp-dsp` (filters, FFT,
dot products, optimized for the S3) is already in the build through ESP-SR, and VADNet already runs on
`esp-nn`. Today the CPU runs at a fixed 240 MHz with no power management. The useful work for the device:

| Use | How | Gain |
|---|---|---|
| Measure first | Time the audio task per 20 ms frame (Opus encode, VADNet, filter) with `esp_timer`; log the busy share each minute | Shows how much headroom there is |
| Pre-processing at almost no cost | High-pass biquad and peak/RMS/clip counters with the S3-optimized `esp-dsp` functions (`dsps_biquad_f32`, `dsps_dotprod_f32`) | Clean input for a few µs per frame |
| Lower clock = the real battery win | When the load is known, enable power management (`esp_pm_configure`, 80–240 MHz). The I2S driver holds an APB lock, so the CPU cannot light-sleep while the mic runs, but a lower clock still cuts the active current | Largest saving while recording |
| Opus complexity | Test complexity 5 against 10 with the test set; the quality loss for speech is small and the CPU time drops a lot | Makes 80 MHz possible |
| Keep the cores apart | Audio pipeline on core 1, radios and the BLE/Wi-Fi stacks on core 0 (as now) | No radio stalls from DSP work |
| Chunk tags for the hub | Per chunk: noise floor, an SNR estimate and spectral flatness (`esp-dsp` FFT) → "speech", "music", "noise", "silence". The hub can skip or deprioritize chunks without speech and pick the post-processing per chunk | Less phone CPU and battery |
| Not on the device | Noise suppression and enhancement (ESP-SR AFE NS): it is destructive and it costs CPU on battery. It belongs to post-processing | — |

Measured on 0.10.1 (240 MHz, 60 s): filter + meter 1.2 %, Opus 24.4 % (worst frame 21.8 ms, more than
the 20 ms frame), VADNet 11.5 % (worst 18.3 ms). Opus complexity must come down before the clock can.
The S3-optimized `dsps_biquad_f32_aes3` / `dsps_dotprod_f32_aes3` corrupted memory in the audio task
(stack in PSRAM); the ANSI versions are used.

## Post-processing (hub, on copies)

Three outputs from one original:

1. **Listening copy** ("crisp"): noise removal (DeepFilterNet 3, runs on CPU), loudness normalization
   (EBU R128, ffmpeg `loudnorm`), click and hum removal. Stored next to the original; the app plays this
   one by default.
2. **ASR input**: the variant that gives the lowest word error rate in the test, for example original +
   high-pass + normalization, or the denoised copy. Silent parts are skipped with the voice detection
   marks. Whisper gets a vocabulary prompt (names, projects) and does not condition on previous text, to
   stop repeat loops.
3. **Clean transcript**: from the verbatim transcript, remove filler words ("um", "uh", "you know",
   repeated words, false starts), fix punctuation and casing, join broken sentences, add speaker labels.
   The verbatim transcript stays. Summaries and to-dos use the clean transcript.

## Recording session (test set)

Record with the Sticky in its normal place, 2–3 minutes per scene. Speakers read the Harvard sentences
(known text) and then talk freely for one minute.

| Scene | What it tests |
|---|---|
| Quiet room, 1 m | Baseline |
| Far speaker, 3–4 m | Gain, normalization |
| Fan or air conditioner on | Steady noise |
| Café or TV in the background | Babble noise, the hardest case |
| Keyboard typing next to the device | Clicks, high-pass |
| Two people, some overlap | Diarization, overlap |
| Device in a pocket or bag for 30 s | Handling noise, clipping |

## Evaluation

- A script runs each variant on each scene: original; high-pass + normalization; denoised; denoised +
  normalization. It computes the word error rate against the known Harvard text, and the listening copies
  go to a page for an A/B listen.
- Pick the ASR input by the word error rate, and the listening copy by ear.
- Keep the test set; run it again after each change to the firmware or the models.

## Order

1. Firmware: measure the audio task load; level telemetry and the high-pass filter with `esp-dsp`. Record
   the test set.
2. Hub: evaluation script and the three post-processing outputs behind a setting.
3. Choose the defaults from the numbers; then try the Opus settings with the same test set.
4. Firmware: Opus complexity and power management (lower CPU clock), checked with the overflow counter
   and the fuel gauge; then the chunk tags.
