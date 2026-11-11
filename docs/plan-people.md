# Speaker identity (people)

Last update: 2026-11-11.

## Goal

The hub gives each session speaker the name of a known person. It learns a voice when the user names a speaker.
The voiceprints stay on the hub (the phone). They are not sent to a server.

## Parts

| Part | File | Function |
|---|---|---|
| Session voices | `Speakers.kt`, `Model.kt` | `mergeSpeakers` returns the turns and one voice for each final speaker. The pipeline keeps them in `meta.json`. |
| People library | `People.kt` | One JSON file next to the store: `people.json` (phone: app files; laptop: next to `-data`). |
| Matching | `People.kt` `matchPeople`, `labelVoices` | Runs in the pipeline after diarization. |
| Learning | `People.kt` `learnVoice`, `nameSpeaker` | Runs when the user names a speaker on the phone. |
| Your voice | `PeopleScreen.kt` `VoiceScreen`, `HubViewModel.recordVoice`, `Engines.voiceprint` | 45 s recording on the phone. |
| Calendar names | `Calendars.attendees` | Attendee names of the events that overlap the conversation. |

## Data

New `meta.json` fields. Each one is left out when it is empty. An old `meta.json` without them loads.

| Field | Type | Meaning |
|---|---|---|
| `voices` | object | speaker (string) → `{"embedding": <base64>, "talk_ms": <ms>}`. The embedding is a unit CAM++ vector (192 float32, little endian, base64). `talk_ms` is the talk time of the turns that gave the embedding. |
| `people` | object | speaker (string) → person ID. The speaker has the name of this person. |
| `suggested` | object | speaker (string) → person ID. The best person was not sure enough. The phone offers the name. |

`people.json` is a JSON array. Each person:

| Field | Type | Meaning |
|---|---|---|
| `id` | string | Random hex. |
| `name` | string | The name. Names are unique (no difference for upper and lower case). |
| `embedding` | base64 | Unit vector. It is the weighted mean of all the voices of the person (weight: talk ms), made a unit vector again. |
| `talk_ms` | number | The sum of the weights. |
| `is_me` | boolean | The voice from "Your voice". Left out when false. |

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

## Learning

- The user names a speaker (the name sheet, a name chip, or the "Speaker A: Krista?" pill on the transcript
  page). The phone adds the session voice of that speaker to the person with that name. If no person has the name,
  it makes a new person.
- When the speaker already had a person and the new name is different, the phone removes the voice from the old
  person (weighted subtraction) and adds it to the new person. A person with no talk time left is deleted (but not
  "You").
- The same name again changes nothing. An empty name or "Speaker X" only removes the voice from the old person.
- A person rename in People also changes the speaker names in all the sessions that link to the person. A rename
  to the name of another person merges the two people.

## Your voice

- App settings › People › Your voice. The screen shows a neutral English text. Chinese users can read any text.
- Record asks for `RECORD_AUDIO` only when the user taps it. `AudioRecord`, 16 kHz mono float, up to 45 s. Stop
  ends the recording early.
- `voicedWindows` keeps the 30 ms frames with an RMS level of 10 % or more of the 95th percentile level. It cuts the
  voiced audio into 3 s windows. Each window goes to the CAM++ extractor (the same model as diarization). The mean
  of the unit embeddings is the voiceprint. Less than 3 s of voiced audio gives an error.
- The voiceprint is the person with `is_me` and the name "You" (the user can change the name). Record again
  replaces the voiceprint. Delete removes it.
- The phone microphone is not the Sticky microphone. If "You" is not found well in sessions, name yourself in
  some transcripts: this adds Sticky audio to your voiceprint.

## Calendar names

The name sheet shows chips: the suggested person, then the attendee names of the calendar events that overlap the
conversation, then the names in the library. The phone reads only the calendars that Calendars has selected. It
leaves out the attendees whose email is a calendar account on the phone.

## Privacy

- The voiceprints are in `people.json` and in `meta.json` (`voices`) on the hub only. The hub HTTP API does not send
  them. The Sticky does not get them.
- Delete in People removes the voiceprint from `people.json` at once. The session voices stay in `meta.json` while the
  session exists; the hub cannot make a person again from them without a new rename by the user.
