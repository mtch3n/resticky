# Plan: several Stickies in one meeting, and sharing from the hub

Status: draft, 2026-11-10. User request: "a sticky can detect for example multi sticky in a meeting it can merge
the audio ... and share data with others from the phone (hub)".

## Is it worth it?

Yes, for meetings. Several Stickies in one room act as a spread-out microphone array:

- Each speaker is close to some Sticky, so the best channel at each moment has a much higher signal-to-noise
  ratio than one Sticky at the end of the table. Fewer "[INAUDIBLE]" parts, better transcripts.
- Which Sticky hears a voice loudest is a strong, free speaker cue. It fixes the main weakness of the current
  diarization (too many speakers, wrong turns).
- One merged session gives one transcript, one summary and one to-do list for the meeting, instead of
  several partial ones. The hub transcribes the merged audio once, not each Sticky's audio.

The hard parts are not the audio: they are identity, transport and consent when the Stickies belong to
different people.

## Two cases

| Case | Example | What is hard |
|---|---|---|
| A. One owner, several Stickies | Desk and kitchen Sticky, or two Stickies on a long table | Only the merge itself |
| B. Different owners | Alex and Sam each bring their Sticky to a meeting | Consent, identity of the other hub, transport between hubs |

Build A first; B uses all of A.

## Detection: "these sessions are the same meeting"

On the hub, without extra radio work on the devices:

1. **Time overlap**: sessions from different Stickies that overlap in time.
2. **Same place**: the same place tag (Places) when present.
3. **Same sound**: the voice detection masks (one bit per 20 ms, already sent with each chunk) and the level
   numbers correlate strongly when two Stickies hear the same room. A cross-correlation of the masks over
   a few minutes gives a yes/no and the rough time offset. This costs almost no CPU.

Optional device hint: a Sticky can listen for the BLE advertisements of other Stickies for 2 s each minute
while it records, and send the IDs and RSSI of the Stickies it hears in the check-in. It costs some battery,
so only if the hub-side test is not enough.

## Alignment and merge (hub)

1. **Coarse**: both clocks come from the network and the RTC (about ±100 ms).
2. **Fine**: GCC-PHAT cross-correlation of the decoded audio per 30 s chunk gives the offset to a fraction of
   a millisecond. Re-align each chunk: crystal drift is about 70 ms per hour.
3. **Merge**: per 1 s window, take the channel with the best signal-to-noise ratio (from the level meter and
   voice detection), with a short crossfade at changes. Keep each original; the merged track is a copy.
4. **Speaker cue**: the energy ratio between the Stickies per word goes into diarization as an extra feature.
5. **One result**: "Meeting · Office · 2 Stickies" with one transcript, summary and to-do list. Each
   original session links to the merged one.

The laptop hub does the merge first (CPU). The phone does it later with a time limit, as with diarization.

## Sharing from the hub

Three levels, from simple to hard:

1. **Share the result as text**: the Android share sheet sends the summary and to-dos (Markdown or plain
   text) to email, chat or notes. No audio. Simple and private: the user chooses what and to whom.
2. **Hub contacts**: two people pair their hubs once with a QR code (each hub has its own key pair). After
   that, one hub can send a meeting result to the other, end-to-end encrypted. Over the local network when
   the phones are near; otherwise over a small relay that only sees encrypted data.
3. **Shared meeting (case B)**: when both hubs see the same meeting (detection above), each owner gets
   "Sam's Sticky is in this meeting. Share this meeting with Sam?". Only when both accept do the hubs
   exchange the chunks of the overlapping time, merge them, and both get the merged result.

## Automatic shared meetings (user, 2026-11-10: "work under the hood like an array so everyone gets the
best result", "user need to enable the share setting so that device can join")

Principle: **acoustic proof of presence.** Hubs combine recordings only when their Stickies hear the same sound
at the same moment. A person who receives the merged result was in the room and could hear it anyway.

| Step | How |
|---|---|
| Opt-in | Setting "Join shared meetings": Off (default) / Text results / Audio too. Only hubs with the setting on take part. |
| Discovery | While its Sticky records speech, an opted-in phone hub looks for other opted-in hubs nearby (candidate: Android Nearby Connections over BLE and Wi-Fi; laptop hubs on the same network over mDNS). No fixed IDs are broadcast. |
| Proof | The hubs exchange the voice-detection masks and level envelopes of the last few minutes. They join only if the masks correlate strongly at a lag under 1 s. A neighbour behind a wall or a different meeting does not match. |
| Keys | Each hub has a key pair; a session key per meeting (ECDH). All data is end-to-end encrypted. |
| Leader | One hub per meeting does the heavy work: a charging laptop first, then a charging phone, then the best battery. If it leaves, the next one takes over. |
| Merge | Members send their chunks to the leader (about 7 MB per hour each). The leader aligns (GCC-PHAT), picks the clearest Sticky per second, transcribes once, labels speakers with the loudness differences between Stickies, and names each owner automatically (the voice that is loudest at Alex's Sticky most of the time is Alex). |
| Result | Every member gets the merged transcript, summary and to-dos (and the merged audio if both chose "Audio too"), stored next to its own original. A member can leave a meeting or withdraw; deletes reach the other hubs. |

## Decisions (user, 2026-11-10) and review (Codex gpt-6-astra)

- One setting turns it on: "Join shared meetings" (user: "join atomaticall is just to enable on the phone once in
  the setting"). Trust on first meeting: the first time two owners' Stickies are in one meeting, each phone shows
  one notification "Merge with <name>? Always / Not now". After "Always", later meetings with that person merge
  without any prompt. Acoustic similarity only groups recordings; it never admits an unknown owner.
- Best result: audio goes to the leader for processing only; the leader deletes peer audio after the merge.
  Everyone keeps their original and gets the merged transcript, summary and to-dos; each owner chooses to keep
  the merged audio. Merging only text loses most of the array gain.
- The phone shows an ongoing notification "Merging with <name> · n Stickies" and a result notification
  "Meeting merged · <place> · n to-dos for you" that opens the merged transcript with playback.
- Speakers keep anonymous labels unless a name is confirmed; no automatic owner naming.
- Order (review): 0) bind each Sticky to its owner's hubs and authenticate uploads (today the Sticky uploads to
  the highest-priority hub on the network over plain HTTP); 1) store voice marks, levels and capture timing on
  the hub; 2) same-owner merge on the laptop; 3) trusted sharing over the local network; 4) Android Nearby;
  5) diarization cues.
- Legal: Canadian one-party consent covers recording a conversation one takes part in; sharing is separate.
  Workplace or health use needs legal review before cross-owner sharing.

## Privacy and consent

- Recording people needs their consent; sharing their voice to another person needs it even more. Default:
  share text results only. Audio only when both owners accept, per meeting.
- Show who receives what before sending. Shares can expire and be withdrawn (delete on the other hub).
- Location, voice prints and raw audio never leave a hub without an explicit action.

## Build order

1. Share sheet for one session (summary + to-dos as text). Small.
2. Several Stickies per hub (the phone home page already has the Sticky chooser).
3. Case A on the laptop hub: detection by time and mask correlation, GCC-PHAT alignment, best-channel merge,
   merged session in the app.
4. Speaker cue from the energy ratio into diarization.
5. Hub contacts (QR pairing, encrypted send) and then the shared meeting (case B).

## Testing with one Sticky

The phone can record at the same time as a second "virtual Sticky" (its microphone, same chunk format). This
tests detection, alignment and merge before a second Sticky is available.
