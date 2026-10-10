# ChildWatch family chat catalog · version 1

Original artwork created for ChildWatch with the built-in imagegen tool: one mint
baby dragon,12 family emotions/actions. The owner rejected the initial flat
prototype; these modern 3D illustrations replace it before the first release.
Prompt set and original source SHA-256 are recorded in `artwork-provenance.json`.
No platform emoji, borrowed sticker pack, external GIF search or font appears
inside the pictures. UI labels remain accessible text outside each picture.

Twelve transparent256×256 RGBA PNGs and two transparent animated GIFs
(twelve100ms frames, loop0). The two animations gently rock/pulse complete
illustrations; they are not newly rendered character-action sequences. Playback
and static mode are controlled by the chat client.

Full-size original PNGs remain locally under `.runtime/chat-catalog-modern-originals`.
Export with `python scripts/import-chat-catalog.py --input-dir <originals> --animate`;
review the staged contact sheet, then add `--publish` to update PROJECT assets
and manifest. This flag does not upload anything to a server. The script preserves
original alpha/content and only exports dimensions/encoder/whole-image motion.
Pillow11.1.0 was used for these bytes.

Committed binary files and manifest are authoritative. After the first release,
do not replace an existing version: publish a new catalog version and retain old
bytes. No released catalog was overwritten during this stage.

The authenticated endpoint checks current conversation membership and hashes.
Never expose this directory through public static middleware.
