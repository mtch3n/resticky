# Plan: SD card store and send policy

Status: draft, revision 2, 2026-11-11. This plan changes no code. The base is firmware 0.15.2 (built, not
measured). This plan replaces "microSD: large spool (plan layer 8)" in `hardware.md`.

Revision 2 uses the Codex source review of revision 1. The review found that revision 1 promised more than
the design can give. "Changes from revision 1" at the end lists each review point and the section that
answers it.

Each assumption has a label `A<n>`. Each open question has a label `Q<n>`. Each protocol change has a label
`P<n>`. Each hub change has a label `H<n>`. Each test has a label `T<n>`.

## Goal

1. Tell the truth about the audio that the device can keep. "Durability and capacity contract" is the
   promise. "No loss" without a limit is not possible, and this plan does not promise it.
2. Lose no chunk that is in the flash spool, in the limits of the contract.
3. Keep audio for days when the hub is away, with a card.
4. Operate correctly with a card and without a card. The card is an option, not a condition.
5. Keep the audio on the card private from a person who has only the card.
6. Use less power to move the audio to the hub. Measure first, then decide.

## Durability and capacity contract

This section is the promise of the product. Layer 1 puts it in `STATUS.md` and `protocol.md`.

### When a chunk is safe

| Stage | Where the audio is | Safe at a power cut |
|---|---|---|
| 1. Open chunk | PSRAM of the audio task, until the chunk closes (about 30 s) | No. |
| 2. Queue | Queue of 8 chunks in RAM | No. |
| 3. Flash spool | LittleFS file after the rename | Yes. LittleFS is made for a power cut. |
| 4. Card store | FAT file, after the check of "Move rules" | Not fully. See "What FAT on a card can and cannot do". |
| 5. Hub | Hub file, after a durable acknowledgement (H1) | Yes, when the hub obeys H1. |

Thus the firmware deletes a copy only in these two cases:

- It deletes a flash chunk after the hub acknowledgement, or after the card has a checked copy.
- It deletes a card record (with its segment) after the hub acknowledgement.

### What the device can lose

| Event | Loss | How the user sees it |
|---|---|---|
| Power cut, crash or brownout | The open chunk and the queue. Usually about 30 s. At most the open chunk plus 8 chunks, if the net task was busy. | A gap in the session. |
| Sleep, restart, power off (ordered) | Nothing. The audio stops and the queue goes to the flash first. | — |
| No hub, no card, more than the flash capacity | The oldest flash chunks. | `dropped_chunks` counts them. |
| No hub, card full, flash full | The oldest flash chunks. | `dropped_chunks` counts them. |
| Card removed and never put back | All chunks that are only on that card. | `card.absent_chunks` in the check-in. |
| Card removed during a card write | Nothing from the move that runs (the flash keeps its copy). Older segments on the card are at risk (FAT). | `card.quarantine` or "Not readable". |
| Power cut during a card write | The same as a card removal during a card write. | The same. |
| Card damage, card wear, format by the user | The chunks on the card. | SD page and check-in. |
| NVS erase before the hub has the card key | The chunks on the card (no key). The plan prevents this case: no move before the key backup (see "Encryption and key"). | — |

### What FAT on a card can and cannot do

- FAT has no journal. A power cut or a removal during a directory write or a FAT write can damage files
  that the write did not touch.
- In ESP-IDF 5.5.3, the SD disk layer answers `CTRL_SYNC` with "OK" and does no more work. Thus `f_sync`
  moves the data from FatFs to the card driver. It does not tell the card to empty its own cache.
- A read after a write shows that the data is readable now. It does not show that the data stays after a
  later power cut.
- Thus A7 of revision 1 ("a sector write is atomic") is not a base of this design. 30 power cuts cannot
  prove it.

The plan decreases the risk. It does not remove it:

1. The flash copy stays until the card copy passes a check after an unmount and a new mount.
2. The firmware writes a segment one time and does not change it.
3. Card writes are few: one segment file for each move, one delete for each sent segment.
4. No card write occurs below 10 % battery.
5. A damaged file goes to quarantine. The firmware does not delete it.

The promise for the card store is: "best effort, with a report". The hub Health record shows each loss
that the device can count.

### Capacity

| Store | Size | Time with speech all the time (about 7 MB/h) |
|---|---|---|
| Flash spool | 22.8 MB less 256 KB reserve | About 3 h |
| Card store | Card size less 64 MB reserve, at most 1000 segments (4 GB) | About 570 h for 4 GB |

Supported time with no hub:

- Without a card: 3 h of speech. After that, the oldest audio is lost and counted.
- With a card of 4 GB or more: 7 days (Q1). The battery, not the card, is the limit in most cases.
- The speech gate keeps less than 7 MB/h on a usual day. Layer 3 measures the real rate (`audio_keep_pm`).

## Design in short

| Decision | Reason |
|---|---|
| A new chunk always goes to the flash spool first, as today. | The flash is always there. A card removal or a card error cannot lose the newest audio. |
| The card is a second store behind the flash spool (the "card store"). The firmware moves the oldest chunks from the flash to the card in groups. | Few card jobs, each with one grouped write. The outbox grows from about 3 h to days. |
| A move has a manifest in NVS with two states. After the state `committed`, the move only goes forward. | No step deletes the last copy of a chunk. |
| The firmware deletes a flash chunk only after it authenticated the card record of that chunk. | A footer alone is not proof of the records. |
| A file that the firmware cannot explain goes to quarantine. | A delete is the only operation that cannot be put right. |
| The device sends in capture sequence: the card store first (oldest segment), then the flash spool. | The hub gets each session from its start. "Newest first" waits for layer 7. |
| Each chunk carries a boot ID and a clock anchor. | The hub can put a late chunk at its capture time. Uptime and Unix time do not mix. |
| The card files are FAT files, one "segment" file for each move, written one time. | A PC can read the card (import). The card controller spreads the wear. |
| The firmware encrypts each record with AES-256-GCM. The key is a random key of the device. The hub keeps a copy before the first move. | The card alone gives no audio. An NVS erase does not lose the card audio. |
| One task owns the card (the "card task"). It does the check, the move, the read and the format. | One owner. Mount and format have no time limit, so the net task cannot do them. |
| A mutex guards the shared SPI2 bus. The e-paper refresh holds it from start to end. | No card transfer during a refresh. |
| The card goes into SPI mode before other traffic on the bus, after each power-on of the card. | ESP-IDF requires this for a shared bus. |
| BLE stays in stream mode until a measurement shows that a batch uses less energy. | The gain on BLE is a hypothesis. The gain on Wi-Fi is plausible. |

This plan does not use these three options:

- Card as the first store (each chunk goes directly to the card). A removal or a power cut during the
  write loses the chunk, and the card is busy each 30 s.
- A PSRAM buffer of many chunks with one write to the card. A crash or a brownout loses the buffer.
- LittleFS on the card. A PC cannot read it.

## Facts from the code (0.15.2)

The review read these lines. The four rows with "layer 1 (in progress)" are faults that a different agent
repairs today. This plan does not repeat that work.

| Place | Fact | State |
|---|---|---|
| `net.c` `spool_fresh` | It frees a chunk also when the flash write failed. The chunk is lost with no count. | Layer 1 (in progress) |
| `spool.c` `spool_oldest` | When an allocation fails, it deletes a good chunk file as "unreadable". | Layer 1 (in progress) |
| `spool.c` `spool_init` | `format_if_mount_failed = true`: a mount error erases the full spool. | Layer 1 (in progress) |
| `Store.kt` `addChunk`, `HubServer.kt` | A known (device, session, seq) gives `REPEAT` also when the audio file is not there. `CONFLICT` gets `200` or `ack`, and the device deletes its copy. | Layer 1 (in progress) |
| `spool.c` | One LittleFS file for each chunk (`/spool/<id>.chk`, temporary file then rename). Partition `spool`: 0x15D0000 bytes. Reserve 256 KB. A full spool deletes the oldest chunk and counts it (`dropped`). | Stays |
| `audio.c` | The open chunk is in PSRAM until it closes. | Stays (contract stage 1) |
| `net.c` | The audio task puts a chunk in a queue of 8. `send_ble` and `send_wifi` send the oldest chunk and delete it after `ack` or `200`. | Stays |
| `net.c` `net_task` | `ble_tick(updating())`: a chunk goes at the slow interval (150 to 200 ms, latency 4), about 8 KB/s. | Layer 3 measures |
| `ble.c` | The "fast" check reads only the interval, not the latency. The 5 s fast hold starts from some frame types, not from the end of a batch. | Layer 3 |
| `net.c` `wifi_policy` | Wi-Fi stops only while a phone hub is linked. With no BLE link, Wi-Fi stays on. | Layer 3 |
| `net.c` `net_park`, `main.c` `quiesce` | `net_park` can time out. The callers do not read the result. | Layer 4 |
| `main.c` `app_main` | `sdcard_init` (card power on) runs before `epd_init`. The card is not in SPI mode when the display traffic starts. | Layer 4 |
| `main.c` `app_main` | An NVS error erases NVS (this loses a key in NVS). | Layer 5 |
| `sdcard.c` | Mount on demand, 20 MHz, FAT. `sdcard_info` runs in the UI task. `sdcard_format` runs in its own task and erases 1 % of the card in each step. No lock guards the card. | Layer 4 |
| `epd.c` | `wait_idle` waits for BUSY for up to 15 s. A card transaction can go between two e-paper transactions. | Layer 4 |
| `board.c` | Light sleep keeps the level of the SD power and CS pins. Deep sleep holds only the power latch and the charger pin. | Layer 4 |
| `Conversations.kt` | `closed` = `final` or "the capture end is older than `SESSION_IDLE`". This is the capture time, not the arrival time. A session from a backlog closes at its first chunk. | Layer 2 |
| `Store.kt` | `started` comes from the first chunk that arrives. No later chunk corrects it. | Layer 2 |
| `Places.kt` `chunkAge` | The age is the uptime of the last check-in less `start_ms`. This is wrong for a chunk from a different boot. | Layer 2 |

## Assumptions

| ID | Assumption | How to check |
|---|---|---|
| A1 | A batch on Wi-Fi uses less energy than a stream, because Wi-Fi is off between batches. | Layer 3. |
| A2 | A batch on BLE possibly uses less energy than a stream. This is a hypothesis. The link and the check-ins stay in the two modes. | Layer 3, three runs. |
| A3 | The 11.5 %/h value is from before the speech gate and with Wi-Fi on. The share of the radio in 0.15.2 is not known. | Layer 3 baseline. |
| A4 | The card in standby (power on, SPI mode, CS high, no clock) uses less than 0.3 mA. Some cards use more. | Layer 4: drain with and without a card, no card job. |
| A5 | The card write speed on SPI at 20 MHz is 0.5 MB/s or more. | Layer 5 log line. |
| A6 | A phone accepts the fast interval and latency 0 in 3 s or less. | Layer 3 log. |
| A7 | Removed. Revision 1 assumed an atomic sector write. See the contract. | — |
| A8 | A mount with the IO buffer uses less than 16 KB of internal RAM. The card task stack is 6 KB or less. | Layer 4: `heap_min`. |
| A9 | The hardware AES (mbedTLS) encrypts 4 MB in less than 2 s. | Layer 5 log line. |
| A10 | A card with no power does not load the shared SPI lines. This is not known. Thus a card in the socket always has power. | Layer 7 experiment only. |
| A11 | The ESP-IDF format uses `FM_ANY`. The FAT type follows the card size (exFAT is off). FAT32 is not forced, and the firmware does not need it. | Test T2. |
| A12 | A card that enters SPI mode stays in SPI mode while it has power. | SD specification. Layer 4 test T5. |
| A13 | The longest time that one card call holds the bus is less than 250 ms for a read or a write. | Layer 4: `bus_hold_ms` (largest value). |

## Storage and data flow

```
audio task -> queue (8) -> flash spool (always) ----------------> hub   (second: newest data)
                               |  flash use >= 12 MB, card READY,
                               |  key backup complete
                               v
                           card store (segments) ---------------> hub   (first: oldest data)
```

Names: the "outbox" is the flash spool plus the card store. A "card job" is one unit of work of the card
task: check, move, read or format. A card job mounts the card at its start and unmounts it at its end.

Rules:

1. The audio path does not change. `spool_fresh` writes each new chunk to the flash spool.
2. A chunk is in one store at a time, except during a move. A move copies, checks, commits and then deletes.
3. The card store holds only chunks that are older than all chunks in the flash spool.
4. The firmware never writes a new chunk directly to the card.
5. Without a READY card, the firmware operates as 0.15.2 plus the layer 1 and layer 2 corrections.
6. A send and a move do not run at the same time. They use the same oldest flash chunks.

Limits (constants, not settings):

| Name | Value | Meaning |
|---|---|---|
| `MOVE_HIGH` | 12 MB of flash use | Start a move (card READY) or drain mode (no card). |
| `MOVE_LOW` | 4 MB of flash use | A move stops here. |
| `SEGMENT_MAX` | 4 MB | Largest segment. One segment for each move. |
| `SEGMENTS_MAX` | 1000 | Largest number of segments that the firmware keeps (4 GB). Above this, the store is `FULL`. |
| `CARD_RESERVE` | 64 MB free | Below this, the card store is `FULL`. |
| `SLICE` | 32 KB | Largest write call. The cancel flag is read between slices. |
| `RECORD_MAX` | 128 KB | Largest record that the parser accepts. |

## Data layout on the card

All names are 8.3 names, so long file names are not necessary.

```
/RESTICKY/
  <DEVICE8>/            last 8 hex characters of the device ID, upper case
    CARD.ID             16 random bytes, made at the first use of this card
    0000002A.RSG        sealed segment (the name is the segment ID in hex)
    0000002B.RSG
    00000017.RSQ        quarantine: the firmware keeps it and does not send it
```

The firmware touches only `/RESTICKY/<DEVICE8>/` of its own device ID. A folder of a different Sticky
stays as it is.

Segment file (all integers little-endian):

| Part | Size | Content |
|---|---|---|
| File header | 48 | `RSG1`, version u16 = 1, flags u16 = 0, device MAC (6), key ID (8), segment ID u32, card ID (16), zero (6) |
| Record, n times | 24 + L | `RSR1`, cipher length L u32, ciphertext (L), tag (16) |
| Footer | 24 + F | `RSF1`, cipher length F u32, ciphertext (F), tag (16) |
| Trailer | 16 | `RSE1`, footer offset u32, record count u32, CRC-32 of the first 12 trailer bytes |

- The plaintext of a record is the full content of the flash chunk file. Thus one parser reads the two stores.
- The plaintext of the footer is a table with one 24-byte row for each record: session u32, seq u32,
  record offset u32, record length u32, duration ms u32, flags u32 (bit 0 = final).
- The nonce is not in the file. It is: segment ID u32, record index u32, zero u32. The footer uses the
  record index 0xFFFFFFFF.
- The additional authenticated data is the 48-byte file header plus the offset of the part. Thus a part
  cannot move to a different place, file or card.
- Each record authenticates alone. A reader that has no footer can read the records in sequence.
- A segment is "sealed" when the trailer is correct and the footer tag is correct.
- The parser checks each length against `RECORD_MAX` and the file size before it allocates memory.

Segment ID:

- The segment ID is a u32 counter of the device, in NVS (namespace `card`, key `next_seg`). It is not a
  number of the card.
- The firmware increases the counter and commits it to NVS before it makes the file. An ID is used one time.
- The firmware does not use "largest number in the folder plus 1". After an empty card, that rule gives
  an old ID again, and an old cursor then skips new records.
- After an NVS erase the counter is lost. `CHECK` sets it to the largest ID in the folder (`.RSG` and
  `.RSQ`) plus 1, if that is larger. The key is also new after an NVS erase, so no nonce repeats.

## Move rules (flash to card)

A move starts when all of these are true: the state is `READY`, the flash use is `MOVE_HIGH` or more, the
key backup is complete, no manifest is in NVS, no send runs, no update runs, no quiesce request is active,
and the battery is 10 % or more.

The manifest is one NVS record (namespace `card`, key `move`): card ID (16), segment ID, state (`writing`
or `committed`), first flash chunk ID, last flash chunk ID.

| Step | Work | State of the manifest |
|---|---|---|
| 1 | Mount. Read `CARD.ID` and the free space. If the space is below `CARD_RESERVE` plus 4 MB, go to `FULL`. | none |
| 2 | Take a new segment ID. Write the manifest. | `writing` |
| 3 | Make `<ID>.RSG`. Write the header. For each of the oldest flash chunks: read, encrypt, write. Stop at `MOVE_LOW`, at 4 MB, at 60 s or at a cancel. | `writing` |
| 4 | Write the footer and the trailer. `f_sync`. Close. Unmount. | `writing` |
| 5 | Mount again. Read the full file. Authenticate the footer and each record. Compare (session, seq) of each record with the footer row. | `writing` |
| 6 | Write the last flash chunk ID and the state `committed`. | `committed` |
| 7 | Delete the flash chunks of the manifest, oldest first. | `committed` |
| 8 | Erase the manifest. Unmount. | none |

Rules:

- **Before step 6, the flash is the truth.** If a step fails, the firmware deletes the segment file of the
  manifest if it can, erases the manifest and keeps all flash chunks.
- **From step 6, the move only goes forward.** No error deletes the segment. No error puts a chunk back.
  A failure in step 7 keeps the manifest, and `CHECK` continues the deletes.
- The firmware never starts a move while a manifest is in NVS. It never writes a new manifest on an old one.
- Step 5 runs after an unmount and a mount. Thus the data comes from the card, not from a FatFs buffer.

Recovery at `CHECK` (after a power cut, a crash or a removal):

| Manifest | Card | Work |
|---|---|---|
| none | any | Nothing. |
| `writing` | The same card ID | Delete the segment file of the manifest, if it is there. Erase the manifest. The flash has all chunks. |
| `writing` | A different card, or no card | Erase the manifest. The flash has all chunks. The file on the first card goes to quarantine when that card comes back (no manifest explains it). |
| `committed` | The same card ID, segment sealed | Authenticate each record again. For each record that is correct, delete the flash chunk with the same (session, seq), if it is in the ID range of the manifest. Erase the manifest. |
| `committed` | The same card ID, segment not there or damaged | Keep each flash chunk that has no correct record. Move the file to quarantine. Erase the manifest. |
| `committed` | A different card, or no card | Do not delete a flash chunk. Erase the manifest. |

The last row is safe, because the only work that a `committed` manifest still has is the delete of flash
copies. If the firmware does not delete them, the chunks go to the hub from the flash. The first card has
a second copy, and the hub answers `REPEAT` when that card comes back. The cost is a duplicate send, not a
loss.

## Quarantine

The firmware deletes a segment file only in two cases: the file is the segment of a `writing` manifest of
the same card, or the hub acknowledged each record of the file.

All other files that the firmware cannot use go to quarantine:

| File | Work |
|---|---|
| Not sealed, and no manifest names it | Rename to `.RSQ`. Do not send. |
| Sealed, with one or more records that do not authenticate | Send the correct records. Then rename to `.RSQ`. |
| Header not readable, wrong device MAC or wrong card ID in the header | Rename to `.RSQ`. |
| Unknown key ID | Keep the name `.RSG`. Do not send. Count as `unknown_key`. |

- If the rename fails, the firmware keeps the file and keeps a mark in RAM.
- The check-in shows `card.quarantine` (files) and `card.unknown_key` (files). The SD page shows the same.
- The hub import (layer 5) reads quarantine files and unknown-key files record by record with the key history.
- Only the user removes these files: with "Format" on the SD page, or on a PC.

## Send state: cursor and delete sequence

- The device sends the records of the oldest sealed segment in file sequence.
- The cursor is (card ID, segment ID, record index, done flag) in NVS (namespace `card`, key `cursor`).
- The cursor can be behind the truth. It is never before the truth. The firmware writes the cursor only
  after the acknowledgement of the record. It writes at each 16th record and at the end of a send.
- A cursor that is behind causes a second send of at most 16 records. The hub answers `REPEAT`.

Sequence at the end of a segment:

1. The hub acknowledges the last record.
2. Write the cursor with the done flag for this segment ID. Commit NVS.
3. Delete the segment file.
4. The cursor for the next segment starts at record 0.

Rules at `CHECK`:

| Cursor | Work |
|---|---|
| Not there, or a different card ID | Start at record 0 of the oldest segment. |
| Segment ID is not on the card | Drop the cursor. Start at record 0 of the oldest segment. An ID is never used again, so the cursor cannot point into a newer file. |
| Done flag, and the file is there | Delete the file (step 3 did not complete). |
| No done flag, file there | Continue at the record index. |

## Sequence, sessions and time on the hub

Layer 2 does this work. It needs no card. It is necessary before a backlog of days can arrive.

### Send sequence of the device

- The device sends in capture sequence: card store first (oldest segment, file sequence), then the flash
  spool (oldest first).
- The device does not send "newest first" in layers 1 to 6. A newest chunk that arrives first gives the
  hub a wrong start and closes a session that is not complete.
- The capture sequence is not a promise. A card that is absent, in `ERROR` or in quarantine holds older
  chunks while the flash sends newer chunks. The hub rules below must accept this.

### Session states on the hub (H2)

| State | Condition | What the pipeline does |
|---|---|---|
| `open` | Chunks arrive. | Nothing final. |
| `final` | The final chunk is there, and each seq from 0 to the final seq is there. | Speakers, transcript, summary. |
| `provisional` | Not complete, and no chunk of this session arrived for `PROVISIONAL_IDLE` (20 minutes, Q9). | The same work as for `final`, with a "not complete" mark. |

- The idle time is the arrival time, not the capture time. Thus a session from a backlog does not close at
  its first chunk.
- `PROVISIONAL_IDLE` must be longer than the longest batch wait (N of the policy table).
- A chunk that arrives for a `provisional` session opens it again. The pipeline does its work again for
  this session.
- A session becomes `final` at once when it is complete. A final chunk still starts a send at once.
- The rule does not use `outbox_chunks`. That count does not include an absent card or the open chunk.

### Start of a session (H3)

- `started` is the capture time of the chunk with the lowest seq that the hub has.
- When a chunk with a lower seq arrives, the hub calculates `started` again.
- The offsets inside a session continue to use `start_ms` differences. All `start_ms` values of one boot
  are uptime values, so the subtraction is correct.

### Clock anchor (P1, H4)

Revision 1 put Unix time into `start_ms` when the clock was valid. This mixes two clocks in one field, and
the subtraction in `Conversations.kt` fails. Revision 2 keeps `start_ms` as uptime and adds an anchor.

| Field | Where | Content |
|---|---|---|
| `start_ms` | Chunk header, frames, `X-Start-Ms` (as today) | Uptime in ms at the chunk start. Always uptime. |
| `boot_id` | New in the chunk header, the chunk frame, `X-Boot-Id`, and the check-in | Random u32, made at each boot. |
| `boot_unix_ms` | New in the same places | Unix time in ms of uptime 0, when the clock is valid. 0 = clock not known. |

- The capture time of a chunk is `boot_unix_ms` + `start_ms`.
- The hub keeps a table (device, `boot_id`) → `boot_unix_ms`. Each chunk and each check-in with a valid
  anchor fills it.
- A chunk with `boot_unix_ms` = 0 uses the table. Thus a clock that becomes valid in the middle of a
  session also gives the time of the earlier chunks of the same boot.
- If the table has no row, the hub estimates the time from the arrival and sets a mark `time_estimated`.
  It corrects the time when the anchor arrives.
- `chunkAge` uses the anchor. It uses the uptime of the last check-in only when the `boot_id` is the same.
- The flash chunk header changes (two new fields). The firmware has one parser and no reader for the old
  header. See Q13 for chunks that are in the spool at the update.

### Acknowledgement and duplicates (H1, layer 1, in progress)

- The key of a chunk is (device, session, seq).
- An acknowledgement (`ack` or `200`) means: the audio file and the session record are in the hub store.
  The device deletes its copy after an acknowledgement, so the hub must not send one earlier.
- `REPEAT` with the audio file not there: the hub stores the bytes again, then acknowledges.
- `CONFLICT` (other bytes for a known key): the hub keeps the first copy, stores the second copy in a
  conflict place, logs it, and then acknowledges. No copy is lost.
- Duplicates occur after a lost `ack`, a cursor that is behind, or a `committed` manifest with no card.

## Encryption and key

Threat in scope: a person removes or finds the card, and has only the card.

**Not in scope: a person who has the Sticky.** The flash spool is plain. NVS is plain and holds `K_card`
and the hub password. A person with the device and a USB cable can read the key and then the card. Flash
encryption and NVS encryption are a different, larger change. This plan does not include them.

### Key lifecycle

| Step | Rule |
|---|---|
| 1. Make | `K_card` = 32 bytes from `esp_fill_random`. The firmware makes the key only while a radio is on, because the random source needs it. |
| 2. Persist | Write the key to NVS (namespace `card`, key `key`). Commit. Read it back and compare. Only then can the key encrypt a record. |
| 3. Key ID | First 8 bytes of SHA-256(`"resticky card key id"` + `K_card`). It is in each segment header. |
| 4. Backup | BLE frame 28 `card_key`, device → hub: `{"key_id":"<16 hex>","key":"<64 hex>"}`. The device sends it only when the link is encrypted, authenticated and bonded (the `secure` gate of `ble.c`). |
| 5. Acknowledgement | The hub writes the key to its key history. Then it answers with frame 29 `card_key_ack`: `{"key_id":"<16 hex>"}`. The device writes `backed` = key ID to NVS. |
| 6. Use | A move starts only when `backed` is the ID of the current key. Before that, the device operates with the flash only. The SD page shows "Needs the phone one time". |
| 7. Lost key | After an NVS erase, the device makes a new key (steps 1 to 6). Segments of the old key stay on the card as `unknown_key`. The hub import reads them with the key history. |

- The key never goes on HTTP, because HTTP is plain.
- A device that has only a Wi-Fi hub has no key backup, thus no card store (Q3).
- The device sends frame 28 again at each secure link until the acknowledgement arrives. After that, it
  sends only the key ID in the check-in (`card.key_id`), and the hub asks again if it does not know the ID.

### Hub key history (H5)

- The hub keeps each key in a table with the index (device, key ID). It never writes a new key on an old one.
- The acknowledgement goes out only after the table is in the hub store.
- The import chooses the key from the key ID in the segment header.
- The phone hub and the laptop hub must each have the key that they need. A key moves between hubs only on
  a secure link (Q14).

### Cipher

| Item | Design |
|---|---|
| Cipher | AES-256-GCM (mbedTLS, hardware AES). Tag 16 bytes. |
| Nonce | Segment ID, record index, zero (see "Data layout"). A segment ID is used one time for each key. Thus the nonce needs no random source, and a nonce does not repeat. |
| Key limit | One key encrypts at most 2^32 segments. The counter cannot go above this. |
| Parser | Each length has a limit before memory is allocated. A tag error makes no plaintext available. |

### What a person with only the card can see

| Visible | Not visible |
|---|---|
| The device ID (folder name and header), the key ID, the card ID | The audio |
| The number of segments and their IDs (the sequence of the moves) | The VAD masks and the level |
| The size of each file and each record (thus about how much speech) | The session and the seq |
| The FAT dates of the files (the time of each move, when the clock was valid) | The capture time and the duration of each chunk |

The file dates and sizes tell when and how much the user spoke, to about one move (hours). The plan accepts
this (Q15).

The hub password (frame 26) is not the source of the key. Reasons: the password goes as a `Bearer` header
on plain HTTP, a new password makes old segments unreadable, and a Sticky with no password has no key.

## Shared SPI bus

### SPI mode first

ESP-IDF (`sdspi_share`) requires this: on a bus with other devices, the card must enter SPI mode before
other traffic goes on the bus. A card that is still in SD mode can answer the traffic of a different
device, also with its CS line high. Today the card gets power, and then the display starts.

Rule: **a card with power is in SPI mode before a different device uses the bus.**

| Event | Sequence |
|---|---|
| Boot, card in the socket | Start the SPI2 bus. Card power on. Enter SPI mode (card init with no mount). Then `epd_init`. |
| Boot, no card | Card power off. `epd_init`. |
| Card inserted during operation | The card has no power at this time. Take the bus mutex (this waits for a refresh). Power on. Wait. Enter SPI mode. Release the mutex. |
| Power cycle of the card (`ERROR` recovery) | Take the bus mutex. Power off 200 ms. Power on. Enter SPI mode. Release the mutex. |
| Wake from light sleep | Before the first refresh: take the bus mutex, power cycle the card, enter SPI mode. The card was possibly changed during the sleep. |
| Card removed | Power off. State `ABSENT`. |

- "Card power off" applies only while the socket is empty. A card in the socket always has power (A10).
- If the SPI mode entry fails, the card power goes off and the state is `ERROR`. The display must still work.

Hardware risk that the firmware cannot remove:

- The user can insert a card during a refresh. The card has no power then, but the contact itself can
  disturb the lines for a short time.
- A card with a fault can hold MISO.
- Layer 4 tests this on the hardware (T5): many inserts during a full refresh, with a check of the
  screen. If the screen shows faults, the result goes into `hardware.md` and the SD page tells the user
  to insert the card while the screen is at rest.

### Locks

| Lock | Guards | Held by |
|---|---|---|
| Bus mutex (`epd_bus_lock` in `epd.c`) | The SPI2 bus | `epd_refresh` from the first command to the end. The card task for each card call. |
| Card job slot | "One card job at a time" | The card task. |
| Spool lock (in `spool.c`, exists) | The flash spool | Short, in each `spool_*` function. |
| Status lock | The copy of the card state for the UI and the check-in | Only for the copy. |

Lock rules:

1. The sequence is: card job slot, then one of the other three.
2. A task holds at most one of bus mutex, spool lock and status lock at a time.
3. No task calls the UI, the hub or `vTaskDelay` for a wait on a person while it holds a lock.
4. The UI task never takes the bus mutex for the card and never mounts the card. It reads the status copy.
5. The net task never touches the card. It gives a job to the card task and reads the result.

### Time limits

"32 KB is about 50 ms" (revision 1) was not a limit. One FatFs call can cause many card commands, and
mount, sync, allocation and erase do not depend on the write size.

| Wait | Limit | When the limit is exceeded |
|---|---|---|
| The card task waits for the bus (a refresh can hold it for 15 s in `wait_idle`) | 20 s | The job stops with `bus_timeout`. A move before step 6 is dropped. The task tries again later. |
| A refresh waits for one card call | Not limited by the application. The SD driver command timeout is the limit. | Layer 4 measures `bus_hold_ms`. The target is A13. |
| A refresh waits during mount or format | One mount, or one erase part | The SD page shows "Card busy" before the job starts. If one erase part holds the bus for more than 1 s, layer 4 makes the parts smaller. |
| Card job `CHECK` | 30 s | `ERROR`. |
| Card job `MOVE` | 60 s for the write (then seal what is there) | — |
| Card job `READ` | 10 s for one fill | `ERROR`. |
| Card job `FORMAT` | None. The SD page shows the progress. | — |

Cancel:

- One flag (`card_cancel`). The card task reads it between records, between slices and between erase parts.
- One card call cannot stop in the middle. Thus the cancel time is the longest single call (mount, one
  erase part, one write). Layer 4 measures it.
- A cancelled move before step 6 is dropped. A cancelled move after step 6 keeps its manifest.
- A cancelled format leaves a card with no file system. The state is `UNREADABLE`, and the SD page shows
  "Format not complete".
- A removed card ends each call with a driver error. This is the fastest stop.

## Card task and state machine

The card task is the only code that calls `sdcard.c`. It starts when a job is ready and ends after the job,
as the format task does today. Thus it uses no stack between jobs. The net task and the UI task give jobs
with `card_request`. The format is a job as all others.

| State | Meaning | Leaves to |
|---|---|---|
| `ABSENT` | Detect pin (GPIO11) high. Card power off. | `CHECK` after the pin is low for 500 ms and the SPI mode entry is correct. |
| `CHECK` | Job: mount, read or make `CARD.ID`, list the files, do the manifest recovery, apply the cursor rules, move files to quarantine. | `READY`, `FULL`, `UNREADABLE`, `ERROR`, `ABSENT`. |
| `READY` | The store can take a move and can give records. The card is not mounted between jobs. | `MOVE`, `READ`, `FULL`, `ERROR`, `ABSENT`, `FORMAT`. |
| `MOVE` | Job that writes one segment. | `READY`, `FULL`, `ERROR`, `ABSENT`. |
| `READ` | Job that reads records into PSRAM for a send. | `READY`, `ERROR`, `ABSENT`. |
| `FULL` | Free space below `CARD_RESERVE`, or `SEGMENTS_MAX` segments. No move. A read is possible. | `READY`, `READ`, `ERROR`, `ABSENT`, `FORMAT`. |
| `UNREADABLE` | The mount failed (no FAT, exFAT, broken FAT). The firmware never formats by itself. | `FORMAT` (user), `ABSENT`. |
| `ERROR` | An IO error or a time limit. Recovery: power cycle with SPI mode entry, one new `CHECK` after 10 s, then after 1, 5 and 30 minutes. | `CHECK`, `ABSENT`, `FORMAT`. |
| `FORMAT` | The user format runs as a job. | `CHECK`, `UNREADABLE` (cancelled). |
| `QUIET` | A quiesce request is active (sleep, restart, update, power off). No job starts. | The state before, after the release. |

Rules for all states:

- The main loop reads the detect pin each second. The card task reads it at the start and end of each job.
- Pin high in any state: cancel the job, card power off, drop the segment list, go to `ABSENT`. The flash
  spool is not touched.
- Only `READY` permits a move. `READY` and `FULL` permit a read. In all other states the outbox is the
  flash spool only.
- A different `CARD.ID` after an insert is a different card: new segment list, cursor rules, manifest rules.
- The mount for each job is for safety at a removal. It is not a power measure. The card keeps its power.

### Format

- "Format" on the SD page is a job of the card task. It is not a second owner.
- Today the format erases the full card in 100 parts and shows "Erasing N %". The user asked for this
  visible progress, so the plan keeps it. Each part is one cancel point.
- Option (Q16): make only the file system (`f_mkfs`) and do no erase. It is faster and causes less wear,
  but it shows no progress, and old ciphertext stays on the card.
- The format keeps `K_card`, `next_seg` and the key history. It erases the cursor and a `writing` manifest.
  A `committed` manifest of this card becomes "no card" (no flash chunk is deleted).
- The button text shows the minutes of audio that the format erases. A second tap is necessary.

## Sleep, restart, update and power off

Rule: **no sleep, restart or update starts while a card job runs.** The 45 s guard in `main.c` is a stop
for a hang. It is not a recovery for a card write.

New function `card_quiesce(timeout)`:

1. Set the state `QUIET` and the cancel flag.
2. Wait until the card task reports "idle" (file closed, unmounted, CS high), at most `timeout`.
3. Return true (idle) or false (still busy).

`card_release()` ends `QUIET`.

| Event | Sequence |
|---|---|
| Light sleep (key) | `audio_stop`, `net_park`, `card_quiesce(10 s)`. If one result is false: do not sleep, show "Busy, sleep follows", and sleep when the acknowledgement arrives. |
| Deep sleep, night sleep | The same. The night check tries again at its next turn. |
| Restart (all `esp_restart` paths) | One function does `card_quiesce(10 s)` first. If false: card power off, then restart. |
| Update (OTA) | `card_quiesce` before the update starts. `QUIET` stays until the restart. If false: the update does not start. |
| Power off by the user, low battery | `card_quiesce(30 s)`. If false: card power off, then power off. |
| Wake | SPI mode entry (see "SPI mode first"), `card_release`, `CHECK`. The backlog is due at once. |

- "Card power off" as the last step is the same as a power cut for the card. The manifest rules cover it.
- `quiesce` in `main.c` reads the results of `audio_stop`, `net_park` and `card_quiesce`. Today it reads none.
- `net_park` keeps its fallback: if the net task does not answer, the caller writes the queue to the flash.
- A format that runs gets the cancel as all jobs. The card is `UNREADABLE` after the wake, and the user
  starts the format again.
- "Flush to the card before sleep" is not necessary. The flash spool keeps its data with no power.
- A drain before the night sleep waits for layer 7.

## Send policy

### What is decided and what is not

| Item | State |
|---|---|
| Stream after a user mark, drain on USB power | Decided. Layer 3. |
| Batch on Wi-Fi with Wi-Fi off between batches | Decided, if the layer 3 run confirms A1. |
| Batch on BLE | Hypothesis (A2). BLE stays in stream mode until layer 3 gives a result. |
| Drain when the flash is at `MOVE_HIGH` with no card | Decided. Layer 3. |
| Live signal (frame 27), newest first, drain before the night | Deferred to layer 7. Not necessary for correct storage. |

### Modes

| Mode | Behaviour |
|---|---|
| Stream | Send each chunk when it is in the flash spool. This is the 0.15.2 behaviour. |
| Batch | Hold the chunks. Send all of them in one burst when a trigger occurs. |
| Drain | Send without a pause until the outbox is empty, in capture sequence. |
| Hold | No usable link. Store only. |

Batch triggers (one is sufficient): the oldest held chunk waited N minutes, the held bytes are M or more,
a final chunk is ready, or a row of the table below changes the mode.

### Policy table

The first row that is true wins.

| # | Situation | Link | Mode | Radio |
|---|---|---|---|---|
| 1 | Update mode (OTA) | any | Hold | As today. |
| 2 | USB power (`tele.usb`) | BLE | Drain, then stream | Fast interval while chunks go. |
| 3 | USB power | Wi-Fi | Drain, then stream | Wi-Fi stays on. |
| 4 | A user mark in the last 2 minutes | BLE or Wi-Fi | Stream for 2 minutes. On Wi-Fi: start Wi-Fi now. | The check-in with the mark goes first. |
| 5 | Flash use at `MOVE_HIGH` or more and no `READY` card | BLE or Wi-Fi | Drain until the flash use is `MOVE_LOW` | Fast interval or Wi-Fi on. |
| 6 | Idle (no row above) | BLE | Stream, as today. Batch (N = 10 min, M = 2 MB) only if layer 3 shows a gain. | As today. |
| 7 | Idle | Wi-Fi, no BLE | Batch, N = 15 min, M = 2 MB | Wi-Fi off between batches. |
| 8 | No hub | none | Hold | BLE advertises as today. Wi-Fi tries at 15, 30, then each 60 minutes. |

Notes:

- With a card store, a drain sends the card store first. Thus old card data does not wait behind new data
  for all time.
- After a restart, a backlog in the outbox is due at once.
- N on Wi-Fi must stay at 15 minutes or less. The drain stretch of the Health screen stops at a gap of 30
  minutes, and `PROVISIONAL_IDLE` is 20 minutes.
- N and M are constants. A setting comes only if the measurement shows a use for it (Q6).

### BLE burst (used by drain, and by batch if it stays)

- `ble_tick(busy)` gets `busy` = update mode, or a burst is active. The burst state holds the fast
  interval, not the frame type.
- At the start of a burst the device asks for the fast interval (15 to 30 ms) and latency 0.
- "Fast" means: the negotiated interval is in range and the negotiated latency is 0. The log shows the two
  values at each connection update.
- If the phone does not give "fast" in 3 s, the burst goes at the interval that the link has. The counter
  `fast_denied` increases. The burst does not stop.
- A lost `ack` stops the burst and starts the 60 s BLE pause, as today. The chunks stay in the outbox.
- After 3 bursts in sequence that stop with a lost `ack`, the device uses stream mode until the link is new.
- The phone app must not ask for its own interval during a burst (H6).

### Wi-Fi path with no BLE link

Today Wi-Fi stays on when no phone hub is linked. New in layer 3:

1. Wi-Fi is off between batches (`wifi_stop`), after a first successful check-in on this network.
2. At a trigger: `wifi_resume`, wait at most 15 s, try the last hub URL (with the hub check), use mDNS only
   if this fails.
3. Send the check-in (marks, Health), then all chunks, then do the usual sync (lists, weather, firmware).
4. `wifi_stop`.
5. If no network or no hub answers: wait 15, 30, then 60 minutes between tries. The outbox keeps the chunks.
6. A touch or a key on a page that needs fresh data uses the 5-minute Wi-Fi window that exists
   (`net_need_wifi`).
7. BLE continues to advertise. When a phone links, the BLE rules apply.

## Power: measure first

No policy change goes into a release before its measurement.

### What to measure

| Value | How |
|---|---|
| Energy for each captured hour | Battery change (and the mean `current_ma`) divided by the hours of captured audio in the run. This is the result of each run. |
| Negotiated interval and latency | Log line and counter at each connection update. `ble_fast_s` counts only "interval in range and latency 0". |
| Reconnects and tries | `ble_links`, `send_retries`, `fast_denied`. |
| Throughput | KB/s for each burst (log line). |
| Card standby current | Run with a card in the socket and no card job, against a run with no card. Light sleep and deep sleep are separate runs, because `board.c` holds different pins. |
| Card write current | Not possible without a meter. The 50 to 100 mA value is from data sheets. |
| Card time | `sd_pwr_s` (card has power) and `sd_mount_ms` (card mounted) are two counters. Revision 1 had only the mount time, which is not the power time. |

### Runs

One run changes one thing. All runs use the same phone, the same place, the same hub and the same audio (a
recorded file that plays in a loop at the same distance). Each run is 8 h or more on battery.

| Run | Link | Mode |
|---|---|---|
| R0 | BLE | 0.15.2 behaviour (stream at the slow interval). Baseline (A3). |
| R1 | BLE | Stream, with a fast burst for each chunk. |
| R2 | BLE | Batch, N = 10 min, with a fast burst. |
| R3 | Wi-Fi, no BLE | 0.15.2 behaviour (Wi-Fi on). |
| R4 | Wi-Fi, no BLE | Batch, N = 15 min, Wi-Fi off between batches. |
| R5 | BLE, as the best of R0 to R2 | With a card in the socket, no card job (A4). |

- Compare the energy for each captured hour between two runs that differ in one thing.
- Do not divide one difference by `wifi_on_s`, `ble_fast_s` and `send_s` together. These timers overlap,
  so one difference cannot give the cost of each.
- If R2 is not better than R0 and R1 by more than the difference between two equal runs, BLE keeps the
  stream mode and the batch code for BLE does not go into the release.
- A power meter is not available. If one comes, measure one burst and one card job on the bench.

## Failure handling

| Failure | Detection | Behaviour | Data |
|---|---|---|---|
| Flash write of a new chunk fails | `spool_put` result | Keep the chunk in the queue and try again. Layer 1 (in progress). | Lost only if the queue is full, and then counted. |
| Flash spool mount fails | `spool_init` | No automatic format. Report in the check-in and on the screen. Layer 1 (in progress). | The spool stays for a later try. |
| No memory to read a flash chunk | Allocation fails | Try again later. Do not delete the file. Layer 1 (in progress). | No loss. |
| No card | Detect pin high | Flash only. Policy row 5 applies at `MOVE_HIGH`. | No loss until the flash is full (about 3 h of speech with no hub). |
| Card inserted during operation | Detect pin low for 500 ms | SPI mode entry under the bus mutex, then `CHECK`. | — |
| Card removed, no card job | Detect pin high | `ABSENT`, card power off. | Card chunks stay on the card. `card.absent_chunks` keeps the last count. |
| Card removed during a move, before step 6 | Write error or detect pin | Stop. Flash chunks stay. Recovery row "`writing`". | No loss of this move. Older segments: FAT risk. |
| Card removed during a move, from step 6 | Detect pin | Stop. The manifest stays. Recovery row "`committed`". | No loss. A duplicate send is possible. |
| Card removed during a read | Read error or detect pin | Stop the send. The cursor stays behind. | No loss. |
| Power cut during a move | Manifest at `CHECK` | Recovery table. | No loss of this move. Older segments: FAT risk. |
| Power cut during a segment delete | Cursor done flag at `CHECK` | Delete the file again. | No loss. Some space can stay lost until a format. |
| FAT damage, mount fails | Mount error | `UNREADABLE`. No automatic format. Flash only. | Card chunks are possibly lost for the Sticky. A PC tool can possibly read them (Q8). |
| FAT damage, one segment | Trailer, footer tag or record tag wrong | Send the correct records. Quarantine. Count `card.bad`. | Only the bad records are lost for the Sticky. |
| File that no rule explains | `CHECK` | Quarantine. | Kept for the hub import. |
| Card full | Free space or segment count | `FULL`. No move. Reads continue and make space. | No loss until the flash is also full, then `dropped` counts. |
| Card IO error | Error from FAT or SDSPI | `ERROR`, power cycle with SPI mode entry, tries after 10 s, 1, 5, 30 minutes. | No loss (move before step 6) or no change (read). |
| SPI mode entry fails | Card init error | Card power off, `ERROR`. The display operates. | Card chunks stay on the card. |
| Bus not free in 20 s | Mutex timeout | Job stops. Try again later. Count `bus_timeout`. | No loss. |
| Card write too slow (below 100 KB/s) | Time of a segment | Count `card_slow`. Seal after 60 s. | No loss. |
| Unknown key ID on the card | Segment header | Keep the file. Do not send. Count. | Readable by the hub import with the key history. |
| No key backup | `backed` is not the key ID | No move. Flash only. | As with no card. |
| Card of a different Sticky | Folder name | Not touched. | — |
| No memory for the card task | Task start or allocation fails | No job. Try again after 1 minute. Count `card_nomem`. | No loss. |
| Hub has the key but not the file (`REPEAT`) | Hub store | The hub stores the bytes again, then acknowledges. Layer 1 (in progress). | No loss. |
| Hub has other bytes (`CONFLICT`) | Hub store | The hub keeps the two copies, then acknowledges. Layer 1 (in progress). | No loss. |
| `ack` lost | 15 s timeout | 60 s BLE pause. The chunk goes again. The hub answers `REPEAT`. | No loss, no duplicate on the hub. |
| Sleep request during a card job | `card_quiesce` | Cancel, wait for the acknowledgement, then sleep. | No loss. |
| The user formats a card with chunks that were not sent | SD page | The button text shows the minutes of audio. A second tap is necessary. | The user decides. |
| Brownout during a card write | Reset reason | As a power cut. No move below 10 % battery. | As a power cut. |

## Wear and write amplification

| Store | Writes | Notes |
|---|---|---|
| Flash spool (LittleFS) | Each chunk one time, as today. At most about 7 MB/h. | 22.8 MB with wear control. At 100,000 cycles this is more than 30 years. No change. |
| Card (FAT) | Each moved chunk one time, in sequence. One read of each segment for the check. | The directory and the FAT change a few times for each 4 MB. No file is written again. A rename to `.RSQ` is one directory write. |
| NVS | Manifest: three writes for each segment. `next_seg`: one write for each segment. Cursor: at each 16th record and at the end of a send. | Small. |

## Memory

| Item | Where | Size |
|---|---|---|
| Chunk buffer for a move or a read | PSRAM | At most 72 KB + masks, as the chunk today |
| Ciphertext buffer | PSRAM | Same size + 24 bytes |
| Segment list | PSRAM | 16 bytes for each segment (1000 segments = 16 KB) |
| Record table of the segment in the send | PSRAM | 24 bytes for each record, at most about 32 KB |
| Card task stack | Internal | About 6 KB, only during a job (A8) |
| IO buffer for the card (DMA) | Internal | 4 KB, only during a job |
| FAT mount and one open file | Internal | About 5 to 10 KB, only during a job (A8) |

Internal RAM is the limit: about 85 to 95 KB free with Wi-Fi and BLE on. `ble_init` needs 64 KB free. A
card job starts only when 32 KB of internal RAM is free. Report `heap_min` as today.

## Protocol and hub changes

| ID | Change | Layer |
|---|---|---|
| P1 | Clock anchor: `boot_id` and `boot_unix_ms` in the chunk header, the chunk frame, the HTTP headers and the check-in. `start_ms` stays uptime. | 2 |
| P2 | Health: new counters (see "Metrics"). | 3 |
| P3 | Check-in: `outbox_chunks` and `outbox_bytes` count the flash spool plus the card store that is in the socket. New object `"card":{"state","chunks","bytes","free_mb","bad","quarantine","unknown_key","absent_chunks","key_id"}`. | 4 |
| P4 | BLE frame 28 `card_key`, device → hub, only on the secure link. BLE frame 29 `card_key_ack`, hub → device. | 5 |
| P5 | BLE frame 27 `live`, hub → device. Deferred. | 7 |
| H1 | Durable acknowledgement. `REPEAT` with no file stores the bytes. `CONFLICT` keeps the two copies. `protocol.md` and the contract tests say this. | 1 (in progress) |
| H2 | Session states `open`, `provisional`, `final`. Close by arrival time. A late chunk opens a `provisional` session again. | 2 |
| H3 | `started` follows the lowest seq. | 2 |
| H4 | Table (device, `boot_id`) → `boot_unix_ms`. `time_estimated` mark. `chunkAge` uses the anchor. | 2 |
| H5 | Key history with the index (device, key ID). Acknowledgement after the store. | 5 |
| H6 | The phone app does not ask for its own interval during a burst. Check `requestConnectionPriority` in `BleHub.kt`. | 3 |
| H7 | Hub import of a card folder (laptop hub first): sealed segments, quarantine files and unknown-key files, record by record. | 5 |
| H8 | Health screen: `dropped_chunks`, `card.absent_chunks`, `card.quarantine` and "not complete" sessions are visible. | 2 and 4 |

## Changes for each file

| File | Change |
|---|---|
| `firmware/src/spool.c`, `net.c` | Layer 1 (in progress): no free after a failed write, no delete at an allocation error, no automatic format. |
| `firmware/src/spool.c`, `spool.h` | Header with `boot_id` and `boot_unix_ms` (P1). `spool_used`, `spool_read_raw`, `spool_parse` (one parser for the two stores), delete of a range of chunks for the move. |
| `firmware/src/audio.c` | `boot_id` and `boot_unix_ms` in each chunk. |
| `firmware/src/epd.c`, `epd.h` | Bus mutex. `epd_refresh` holds it. `epd_bus_lock(timeout)` and `epd_bus_unlock` for the card. |
| `firmware/src/sdcard.c`, `sdcard.h` | Driver only: power, SPI mode entry with no mount, mount, unmount, power cycle, free space, erase part, file system format. Each call takes the bus mutex. `sdcard_info` and the format task go away. |
| `firmware/src/card.c`, `card.h` (new) | The card task and the store: state machine, `card_request`, `card_quiesce`, `card_release`, `card_status`, `CHECK`, manifest, segment write and check, quarantine, segment read, cursor, format job. |
| `firmware/src/cardkey.c`, `cardkey.h` (new) | Key lifecycle, key ID, `backed` state, AES-GCM for one record. |
| `firmware/src/outbox.c`, `outbox.h` (new, layer 6) | One view of the two stores for `net.c`: `outbox_next`, `outbox_done`, `outbox_stats`, `outbox_tick` (asks for a move). |
| `firmware/src/net.c` | Layer 3: `send_mode` (policy table), burst state for `ble_tick`, Wi-Fi off between batches, counters. Layer 4: check-in `card` object. Layer 5: frames 28 and 29. Layer 6: `outbox_*`. |
| `firmware/src/ble.c`, `ble.h` | "Fast" = interval and latency. Burst hold. Counters. Frames 28 and 29 behind the `secure` gate. |
| `firmware/src/main.c` | Boot sequence: SPI mode entry before `epd_init`. Detect pin each second. `quiesce` reads its results and calls `card_quiesce`. One restart function. |
| `firmware/src/ota.c` | `card_quiesce` before the update. |
| `firmware/src/board.c` | Card power function. No change of the sleep pins before layer 7. |
| `firmware/src/ui.c` | SD page from `card_status`: state, chunks that wait, minutes of audio, quarantine and unknown-key counts, "Card busy", "Needs the phone one time", format progress. No mount in the UI task. |
| `firmware/sdkconfig.defaults` | Check that hardware AES and GCM are on. |
| `docs/protocol.md`, `docs/STATUS.md`, `docs/hardware.md` | The contract, P1 to P4, H1 to H5, measured values. |
| `app/shared` (hub) | H1 (in progress), H2, H3, H4, H5, H7, H8. |
| `app/androidApp` | H6. SD state on the Sticky page. |
| `firmware/tools/ble_hub.py` | Frames 28 and 29 for tests. Anchor fields. |

## Metrics

New Health fields (counters since start, in the check-in `health` object):

| Field | Meaning |
|---|---|
| `wifi_on_s` | Seconds with Wi-Fi started. |
| `ble_link_s`, `ble_links` | Seconds with a ready BLE link. Number of links. |
| `ble_fast_s` | Seconds with the negotiated interval in the fast range and latency 0. |
| `fast_denied` | Bursts that did not get "fast" in 3 s. |
| `ble_tx_kb`, `wifi_tx_kb` | Chunk bytes sent on each link. |
| `send_s`, `send_retries` | Seconds in a chunk send. Chunks sent again. |
| `batches` | Number of bursts. |
| `sd_pwr_s` | Seconds with card power on. |
| `sd_mount_ms` | Milliseconds with the card mounted. |
| `sd_wr_kb`, `sd_rd_kb` | Bytes written to and read from the card. |
| `sd_moves`, `sd_errors`, `bus_timeout` | Segments committed, card errors, bus time limits. |
| `bus_hold_ms` | Longest time that one card call held the bus. |
| `send_mode` | The mode at the check-in: 0 stream, 1 batch, 2 drain, 3 hold. |

The log gets one line for each burst: chunks, bytes, seconds, KB/s, interval, latency.

## Test plan

Each test records: firmware version, card (make, size) or "no card", link, result, `lost_chunks`,
`dropped_chunks`, and the hub count of chunks for each session (no gap in seq).

| ID | Test | Pass | Layer |
|---|---|---|---|
| T1 | No card, phone linked, app closed, 2 h, each of the modes R0 to R2 | All chunks on the hub. The log shows the interval and the latency of each burst. | 3 |
| T2 | No card, exFAT card, card with no FAT, small card (FAT16) | SD page shows "No card", "Not readable" or a ready card. Audio flow as T1. | 4 |
| T3 | No card, no hub for 4 h | Flash fills. `dropped_chunks` counts each lost chunk. No crash. The newest audio is kept. The loss agrees with the contract. | 1 |
| T4 | Card, no hub for 6 h, then hub | Moves at 12 MB. `dropped_chunks` = 0. All chunks arrive in capture sequence, no gap. | 6 |
| T5 | Hot plug: insert and remove 20 times each: idle, during a move, during a read, during a full refresh, during a partial refresh | No crash, no hang. Screen faults are counted and written to `hardware.md`. No gap on the hub after the card is back. | 4 (no data), 6 |
| T6 | Power cut (reset key and battery pull) 30 times during a move: steps 3, 5, 6 and 7 | After each start the recovery table gives the result. No gap. This test finds faults. It does not prove the FAT. | 5 |
| T7 | Full card: fill the card to 60 MB free with PC files | `FULL`. No PC file changes. Drain mode at 12 MB. | 6 |
| T8 | Card error: a card with bad sectors, or CS forced high in a debug build | `ERROR`, tries with back-off, flash-only operation, no loss. The display operates. | 6 |
| T9 | Screen and card: page changes and a full refresh during a 4 MB move and during a format | No refresh fault. `bus_hold_ms` agrees with A13 for the move. `overflows` stays 0. | 4, 5 |
| T10 | Light sleep during a move, during a read and during a burst | Sleep after the acknowledgement, in less than 10 s. `sleep_step` = 0 after wake. No segment is lost. | 4, 6 |
| T11 | Deep sleep and night with a backlog in the two stores | After the wake the backlog goes. The session times on the hub are the capture times (anchor). | 6 |
| T12 | Live signal: open the app with a 2 MB backlog | Deferred with frame 27. | 7 |
| T13 | Mark in batch mode | The mark and its chunk are on the hub in less than 45 s. | 3 |
| T14 | USB power with a backlog | Drain at once at the fast interval. | 3 |
| T15 | Wi-Fi hub, no phone, 2 h | Wi-Fi on only for the batches (`wifi_on_s`). Health has a sample at least each 15 minutes. | 3 |
| T16 | Wi-Fi hub away, then back | Tries at 15, 30, 60 minutes. Then all chunks arrive. | 3 |
| T17 | Privacy: read the card on a PC | No audio, session, seq or capture time is readable. The file dates and sizes are visible, as the plan says. A changed byte in a record makes the Sticky skip that record only. | 5 |
| T18 | Card A with chunks, then card B, then card A | Each card keeps its segments. All chunks of A arrive after A is back. | 6 |
| T19 | Unknown key: NVS erase with segments on the card | New key, new backup. No move before the acknowledgement. Old segments are kept and counted. The hub import reads them with the key history. | 5 |
| T20 | Format with chunks on the card | The button shows the audio minutes. After the format the store is empty and `READY`. The key and `next_seg` stay. | 6 |
| T21 | OTA with a backlog in the two stores, and OTA request during a move | No card job during the update. The update does not start without the acknowledgement. No loss after the restart. | 4, 6 |
| T22 | Internal RAM | `heap_min` stays above 64 KB with BLE, Wi-Fi and a card job at the same time. | 4 |
| T23 | Contract tests (hub) | H1 to H4 behave as written. | 1, 2 |
| T24 | Partial source delete: power cut in step 7 after some flash deletes, 10 times | Each chunk is on the hub one time. No chunk is lost. No segment is deleted. | 5 |
| T25 | Old cursor after an empty card: send all segments, cut the power before the cursor write, then make new segments | No record is skipped. The new segment has a new ID. | 6 |
| T26 | Card swap with a manifest: cut the power in step 3 and in step 7, then start with card B, then put card A back | No flash chunk is deleted with card B. No new move before the manifest is gone. The file on card A is in quarantine or sent. No loss. | 5 |
| T27 | Quarantine: a cut file, a file with a changed record, a file with a wrong header | The firmware deletes none. The correct records arrive. The counts are in the check-in. The hub import reads the files. | 5 |
| T28 | Hub file not there: delete one audio file on the hub, then send the chunk again | The hub stores the bytes and then acknowledges. | 1 |
| T29 | Clock sync in the middle of a session, and a backlog from an earlier boot | All chunks of the boot get the correct capture time. `started` is the time of seq 0. No `time_estimated` mark stays. | 2 |
| T30 | Sleep, restart and power off during a format | The format stops at a part. The device sleeps after the acknowledgement. After the wake: `UNREADABLE`, "Format not complete". No hang. | 4 |
| T31 | Session sequence: send seq 5 to 9 with final, wait 25 minutes, then send seq 0 to 4 | `provisional` after 20 minutes, open again, then `final`. `started` moves to seq 0. The pipeline does its work again. | 2 |
| T32 | Key backup: first card use with no phone, then with a phone | No move before frame 29. A move after it. The hub has the key under (device, key ID) after a hub restart. | 5 |
| T33 | Flash faults (layer 1, in progress): a failed write, a failed allocation, a failed mount, each forced in a debug build | No chunk is freed or deleted. No format. | 1 |

## Layers (sequence of the work)

The sequence is the corrected sequence of the review. Each step is small, works from end to end and has its
tests. Flash it and test it before the next step starts.

| Layer | Step | Content | Tests |
|---|---|---|---|
| 1 | 1a (in progress) | `spool_fresh` keeps a chunk after a failed flash write. `spool_oldest` does not delete at an allocation error. No automatic format of the spool. | T33, T3 |
| 1 | 1b (in progress) | Hub: `REPEAT` with no file stores the bytes. `CONFLICT` keeps the two copies. Acknowledgement after the store (H1). | T28, T23 |
| 1 | 1c | The contract in `STATUS.md` and `protocol.md`. `dropped_chunks` visible on the Health screen. | Review |
| 2 | 2a | Clock anchor: P1 in the firmware, H4 on the hub. | T29 |
| 2 | 2b | Session states and `started` (H2, H3). Pipeline does its work again for a session that opens again. | T31 |
| 2 | 2c | Contract tests for H1 to H4. | T23 |
| 3 | 3a | Counters (P2) and the burst log line. No behaviour change. Run R0 and R3. | Baseline |
| 3 | 3b | Burst send on BLE with the "fast" definition. Rows 2, 4 and 5. Runs R1 and R2. Decision for row 6. | T1, T13, T14 |
| 3 | 3c | Wi-Fi batch: rows 3, 7 and 8. Run R4. | T15, T16 |
| 4 | 4a | SPI mode entry at boot, insert, wake and power cycle. Bus mutex. Card power only with a card in the socket. | T5 (no data), T9 |
| 4 | 4b | Card task as the one owner. State machine with `CHECK` (no audio on the card). Format as a job. Status copy, SD page, check-in `card` object (P3). Run R5. | T2, T9, T22 |
| 4 | 4c | `card_quiesce` with acknowledgement for sleep, restart, update and power off. | T10, T21, T30 |
| 5 | 5a | Key lifecycle, frames 28 and 29, hub key history (P4, H5). | T32 |
| 5 | 5b | Segment write, check, manifest, recovery, quarantine. Hub import (H7) reads what the device wrote. The move is on only in a debug build. | T6, T17, T19, T24, T26, T27 |
| 6 | 6a | Segment read and send, cursor, delete sequence, `outbox.c`, `FULL` and `ERROR`. Debug build. | T25, T7, T8, T18, T20 |
| 6 | 6b | Gate: T5, T6, T10, T21, T24 to T27 and T30 pass on two card types. Then the move is on in the release build. | T4, T11 |
| 7 | — | From measurements only. Batch N and M. Live signal (frame 27) and newest first. Drain before the night. Card power off between jobs and in sleep. Format with no erase. | T12, a decision for each in `STATUS.md` |

Layers 1 and 2 are corrections that are necessary also without a card. Layer 3 gives the power effect and
needs no card. If layer 3 shows no gain, layers 4 to 6 still have a use: audio for days when the hub is away.

## Open questions

| ID | Question |
|---|---|
| Q1 | Is "7 days with no hub, card of 4 GB or more" the correct supported time? It sets `SEGMENTS_MAX`. |
| Q2 | Must the Sticky screen show fresh lists while Wi-Fi is off between batches? The plan uses the 5-minute window after a touch. |
| Q3 | A device with only a Wi-Fi hub has no secure link for the key backup. The plan gives it no card store. Is this correct, or is a move with no backup (and a loss at an NVS erase) better for that user? |
| Q4 | Can the card power be off between jobs and in sleep (A10)? In deep sleep the SD power pin is not held today, so the card state in deep sleep is not known. Layer 7. |
| Q5 | Stream mode: is one fast burst for each chunk cheaper than 6 s at the slow interval? Runs R0 and R1 answer this. |
| Q6 | Does the user want a setting for the send policy (for example "Stream always", "Only on USB power")? The plan has constants only. |
| Q7 | Does the laptop hub need a live signal? Deferred with frame 27. |
| Q8 | Is a PC recovery tool for a card with a broken FAT necessary? The segment parts have magic values, and each record authenticates alone, so a raw scan can find them. |
| Q9 | Is 20 minutes correct for `PROVISIONAL_IDLE`? It delays the summary of a session that has no final chunk. Does the phone "Transcribe" setting change it? |
| Q10 | Answered: `start_ms` stays uptime, and the anchor is in new fields (P1). |
| Q11 | Are 12 MB and 4 MB the correct move limits? A lower `MOVE_HIGH` moves earlier and keeps more flash free. |
| Q12 | Must the hub show a warning when a card with chunks is absent for a long time? H8 shows the count. |
| Q13 | The chunk header changes in layer 2, and the firmware keeps no reader for the old header. Proposal: the hub offers this update only when `outbox_chunks` is 0. Is this sufficient? |
| Q14 | How does the laptop hub get a key that only the phone hub has (for the import)? Proposal: the device sends frame 28 to each bonded hub that does not know the key ID. |
| Q15 | The FAT file dates show the time of each move. Is a constant file date necessary? It costs one more directory write for each segment. |
| Q16 | Format: keep the full erase with "Erasing N %" (the user asked for visible progress), or make only the file system (faster, no progress)? The plan keeps the erase. |

## Changes from revision 1

| Review point | Answer in revision 2 |
|---|---|
| "No loss" was more than the design gives. T3 accepted a loss. | "Goal" and "Durability and capacity contract". Loss cases and supported time are written. |
| Flash loss paths (`spool_fresh`, `spool_oldest`, automatic format) | Layer 1 (in progress). T33. |
| A seal does not make FAT safe. `CTRL_SYNC` does nothing more. A7 cannot be proved. | "What FAT on a card can and cannot do". A7 removed. Check after a new mount. |
| Recovery deleted the last copy. | "Move rules": no way back from step 6. Each record is authenticated before its flash copy is deleted, also in the recovery. |
| `CHECK` deleted each file that was not sealed. | "Quarantine". |
| Segment numbers repeated. The move mark had no card ID. | Segment ID from an NVS counter. Manifest with the card ID. "Send state" gives the cursor and delete sequence. |
| H1 and H2 did not repair the sequence. `started` came from the first arrival. Uptime and Unix time were mixed. | "Sequence, sessions and time on the hub": capture sequence, three session states, `started` from the lowest seq, clock anchor with an unknown state. |
| H3 accepted `REPEAT` with no file and `CONFLICT` with a delete. | H1, layer 1 (in progress). T28. |
| The card was not in SPI mode before bus traffic. Hot insert risk. | "SPI mode first" and the hardware risk. |
| "32 KB is about 50 ms" was not a limit. Format and mount have no limit. BUSY can be 15 s. | "Locks", "Time limits", "Cancel". |
| The format task was a second owner. | "Card task and state machine", "Format". |
| Sleep did not wait for the card. | "Sleep, restart, update and power off": `card_quiesce` with acknowledgement. |
| BLE batch gain is a hypothesis. The measurement was not sufficient. | "Power: measure first". BLE stays in stream mode. Wi-Fi batch stays. |
| Key lifecycle was not complete. | "Encryption and key": random source, persist before use, key ID, backup acknowledgement, key history, secure link only, device theft not in scope, visible data. |
| Too much in the first layers. | Newest first, live signal and the drain before the night are in layer 7. The format with no erase is an option (Q16). |
| Layer sequence | "Layers" uses the sequence of the review. |
| New tests | T24 to T33. |
