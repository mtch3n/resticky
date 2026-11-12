# Speaker identity (people)

Last update: 2026-11-11.

## Goal

The hub gives each session speaker the name of a known person. All of it is automatic: no step needs the user.
The hub finds "You" and the voices that come back, and it learns names from the meetings. The manual tools (name
sheet, Your voice) stay, but they are optional, and a name from the user always wins.
The voiceprints stay on the hub (the phone). They are not sent to a server.

## Parts

| Part | File | Function |
|---|---|---|
| Session voices | `Speakers.kt`, `Model.kt` | `mergeSpeakers` returns the turns and one voice for each final speaker. The pipeline keeps them in `meta.json`. |
| People library | `People.kt` | One JSON file next to the store: `people.json` (phone: app files; laptop: next to `-data`). |
| Matching | `People.kt` `matchPeople`, `labelVoices` | Runs in the pipeline after diarization. |
| Learning | `People.kt` `learnVoice`, `nameSpeaker` | Runs when the user names a speaker on the phone, and for each name that the summary model finds. |
| Automatic people | `People.kt` `clusterVoices`, `autoPeople`, `linkPeople` | Runs in the pipeline after each diarization. |
| Names from the meeting | `Names.kt` `slots`, `acceptNames`; `Pipeline.learnNames` | Runs after each summary. |
| Your voice | `PeopleScreen.kt` `VoiceScreen`, `HubViewModel.recordVoice`, `Engines.voiceprint` | 45 s recording on the phone. |
| Calendar names | `Calendars.attendees` | Attendee names of the events that overlap the conversation. |

## Data

New `meta.json` fields. Each one is left out when it is empty. An old `meta.json` without them loads.

| Field | Type | Meaning |
|---|---|---|
| `voices` | object | speaker (string) → `{"embedding": <base64>, "talk_ms": <ms>}`. The embedding is a unit CAM++ vector (192 float32, little endian, base64). `talk_ms` is the talk time of the turns that gave the embedding. |
| `people` | object | speaker (string) → person ID. The speaker has the name of this person. |
| `suggested` | object | speaker (string) → person ID. The best person was not sure enough. The name sheet shows it as the first chip. |
| `user_named` | array | Speakers (string) that the user named. The hub never changes their names. |

`people.json` is a JSON array. Each person:

| Field | Type | Meaning |
|---|---|---|
| `id` | string | Random hex. |
| `name` | string | The name. Names are unique (no difference for upper and lower case). |
| `embedding` | base64 | Unit vector. It is the weighted mean of all the voices of the person (weight: talk ms), made a unit vector again. |
| `talk_ms` | number | The sum of the weights. |
| `is_me` | boolean | The owner ("You"). From "Your voice" or found automatically. Left out when false. |
| `auto` | boolean | The hub made the name ("You", "Person 3" or a name from a meeting). Left out when false. A rename by the user sets it to false. |

An old `people.json` loads: a missing `auto` is false.

## Speakers of short replies

A short reply ("yeah", "you know") from one person in the long talk of another person must get its own speaker.
The rules come from WhisperX (a speaker for each word) and pyannote community-1 (the exclusive view at overlaps).

Diarizer (both hubs, `Speakers.kt` constants that `Engines.kt` and `Main.kt` use):

| Constant | Value | Note |
|---|---|---|
| `MIN_DURATION_ON` | 0.2 s | Was 0.3 s. Shorter turns stay. |
| `MIN_DURATION_OFF` | 0.2 s | Was 0.5 s. A short gap no longer joins two turns. |
| `VOICE_MIN_MS` | 1500 ms | A turn of this length or more gives the speaker voice. |
| `SHORT_VOICE_MIN_MS` | 800 ms | A speaker with no voice gets an embedding for each turn of this length or more (`Turn.voice`). It is low confidence: it only decides the speaker of that turn. It never goes into `voices` or the people library. |

Minor speakers (`mergeSpeakers`): a speaker with little talk time and no voice joins a main speaker by time (the
main speaker of the turn before it). This rule does not apply when the turn interrupts that main speaker. A turn
interrupts speaker X when the turn before it is a turn of X that continues after it, or when the turns before and
after it (of other speakers) are both of X. Then:

1. If the turn has a `Turn.voice`, it goes to the main speaker with the highest cosine (`ABSORB_MIN_COSINE` or
   more).
2. Else the speaker is unknown (`-1`). The transcript shows "?" and the summary input shows "?:". The name sheet
   does not open for "?".

A session that has no speakers (no diarization, old data) shows no label, as before.

Speaker of a word (`wordSpeaker`, `Session.assignSpeakers`). Times are session times:

1. The turns that contain the midpoint of the word give the speaker.
2. When turns of two or more speakers contain the midpoint: if the shortest of them is `BACKCHANNEL_MAX_MS`
   (2000 ms) or less and the word is fully in it, its speaker gets the word. Else the speaker of the turn with the
   largest overlap with the word gets the word.
3. When no turn contains the midpoint: the nearest turn gets the word if the gap is `WORD_NEAR_MS` (300 ms) or
   less. Else the word gets the speaker of the word before it. The first word of a session gets the nearest turn.
4. `Segment.splitBySpeaker` then splits the segment at each speaker change. Each new segment has the words of one
   run, its start and end from these words, and the text of these words with spaces between them. A segment with
   one speaker stays as it is.

A segment with no words (old data, engines without word times) gets the speaker with the largest overlap, as
before. The `meta.json` format does not change, so an old `meta.json` loads. Old sessions change only when
diarization runs again.

## Matching

1. The pipeline calculates the cosine of each session voice and each person.
2. It sorts all pairs with cosine ≥ `PERSON_SUGGEST` by cosine, the highest first.
3. It takes a pair when the speaker and the person are both still free (greedy). Thus one person never gets two
   speakers of one session.
4. The pair is sure when cosine ≥ `PERSON_MATCH` and the cosine is `PERSON_MARGIN` or more above the second best
   person of that speaker. A sure pair sets the speaker name and `people`. Other pairs go to `suggested`.
5. A new diarization run clears `voices`, `people` and `suggested` and makes them again.

| Constant | Value | Note |
|---|---|---|
| `PERSON_MATCH` (T_HIGH) | 0.62 | Start value. Tune it on the test set. |
| `PERSON_MARGIN` | 0.05 | Start value. Tune it on the test set. |
| `PERSON_SUGGEST` (T_LOW) | 0.5 | Start value. Tune it on the test set. |

To tune: name the speakers of the test sessions by hand, then run the matching with a person library made from
other sessions. Count the wrong names (false accept) and the missed names for each threshold. A wrong name is worse
than a missed name, because the summary uses the name.

## Automatic people (after each diarization)

1. `clusterVoices` puts all session voices of all sessions into groups (greedy, the longest talk time first). A
   voice joins the group with the highest cosine when the cosine is `PERSON_MATCH` or more and the group has no
   voice of the same session yet (two speakers of one session are two people). The group embedding is the mean of
   its unit voices, weighted by talk time.
2. Owner: if no person has `is_me`, the group in the most sessions becomes "You" (`is_me`, `auto`) when it is in
   `OWNER_MIN_SESSIONS` sessions or more and in `OWNER_SHARE` of the sessions with voices or more. If a person
   already matches the group, that person gets `is_me` (an unnamed "Person N" gets the name "You"). An enrolled
   "You" stays.
3. Recurring voices: each other group in `RECURRING_MIN_SESSIONS` sessions or more that matches no person becomes
   an unnamed person "Person N" (`auto`). N is the highest number in use plus 1, so a number never moves to another
   person.
4. `linkPeople` then names the free speakers of all sessions with the normal matching (sure pairs only), and
   changes the speaker names of linked speakers when the person name changed. It does not touch `user_named`
   speakers.

| Constant | Value | Note |
|---|---|---|
| `OWNER_MIN_SESSIONS` | 5 | Fewer sessions give no automatic "You". |
| `OWNER_SHARE` | 0.4 | Share of the sessions with voices. |
| `RECURRING_MIN_SESSIONS` | 3 | Sessions for an unnamed "Person N". |

## Names from the meeting (after each summary)

The summary call is one JSON call for each conversation. `SUMMARY_SCHEMA` has `speakers`:
`[{"speaker":"Speaker B","name":"Krista","evidence":"<exact transcript words>"}]`. The prompt asks for a name only
when the transcript shows it (the speaker says "this is Krista", or another speaker says the name and that speaker
answers next) and gives the calendar attendees of the conversation time as candidates. `acceptNames` keeps a
proposal only when all of these are true:

- `quoteSpan` finds the evidence in the transcript that went to the model, and the first word of the name is in it.
- The evidence line, or the next line, starts with the proposed speaker label.
- The name is an attendee (all its words are in an attendee name), or all its words are in the transcript.
- The name is not "You", "Speaker X" or "Person N". No other speaker of the conversation has the name, and the
  model gave the name to one speaker only and one name to the speaker only.
- The speaker is open: it has the letter name or an unnamed "Person N", it is not "You", and it is not in
  `user_named`. If two sessions of one conversation have the same label, the hub skips the label.

An accepted name goes through `nameSpeaker(auto = true)`: an unnamed "Person N" gets the name in the library and in
all sessions; else the hub adds the voice to the person with the name or makes a new person with `auto`. Thus the
name stays for the next meetings.

## Learning

- The user names a speaker (the name sheet from the speaker chip or the name row on the transcript page). The phone adds the session voice of that speaker to the person with that name. If no person has the name,
  it makes a new person.
- When the speaker already had a person and the new name is different, the phone removes the voice from the old
  person (weighted subtraction) and adds it to the new person. A person with no talk time left is deleted (but not
  "You").
- The same name again changes nothing. An empty name or "Speaker X" only removes the voice from the old person.
- A user name adds the speaker to `user_named` and sets `auto` to false. If the speaker had an unnamed "Person N",
  the user name goes to that person in all sessions.
- A person rename in People also changes the speaker names in all the sessions that link to the person. A rename
  to the name of another person merges the two people.

## Your voice

- Optional. App settings › People shows "Your voice" as the last row. The hub finds "You" without it. The screen
  shows a neutral English text. Chinese users can read any text.
- Record asks for `RECORD_AUDIO` only when the user taps it. `AudioRecord`, 16 kHz mono float, up to 45 s. Stop
  ends the recording early.
- `voicedWindows` keeps the 30 ms frames with an RMS level of 10 % or more of the 95th percentile level. It cuts the
  voiced audio into 3 s windows. Each window goes to the CAM++ extractor (the same model as diarization). The mean
  of the unit embeddings is the voiceprint. Less than 3 s of voiced audio gives an error.
- The voiceprint is the person with `is_me` and the name "You" (the user can change the name). Record again
  replaces the voiceprint. Delete removes it.
- The phone microphone is not the Sticky microphone. The automatic "You" uses Sticky audio.

## Calendar names

The summary prompt gets the attendee names of the conversation time as candidates (`Pipeline` `attendees`).
The name sheet shows chips: the suggested person, then the attendee names of the calendar events that overlap the
conversation, then the names in the library. The phone reads only the calendars that Calendars has selected. It
leaves out the attendees whose email is a calendar account on the phone.

## Privacy

- The voiceprints are in `people.json` and in `meta.json` (`voices`) on the hub only. The hub HTTP API does not send
  them. The Sticky does not get them.
- Delete in People removes the voiceprint from `people.json` at once. The session voices stay in `meta.json` while the
  session exists. The automatic step can make an unnamed person again from them when the voice is in
  `RECURRING_MIN_SESSIONS` sessions or more.
