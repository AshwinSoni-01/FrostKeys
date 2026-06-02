# Importable Dictionaries

This folder contains dictionary files intended for manual import through KBoard's
dictionary picker.

## Burmese_Myanmar.dict

`Burmese_Myanmar.dict` is an AOSP/HeliBoard-compatible binary dictionary for
locale `my`, dictionary type `main`. Users can import it from KBoard settings:

1. Open KBoard settings.
2. Go to Dictionaries.
3. Choose Burmese/Myanmar.
4. Tap Add dictionary and select `Burmese_Myanmar.dict`.

Generation sources:

- `mcfnlp/Dictionary` at commit `ba6184014052dd066793111faabeeab55a2f863d`
- `chanmratekoko/Awesome-Myanmar-Wordlists-Dictionary-Collection` at commit `c6f1514fc7d17b4ca1dfb103b0cd58c892effe39`
- `Helium314/aosp-dictionaries` `dicttool_aosp.jar` at commit `881a5ac3c6fdc5fd866fcdc517b7534c5574dd6b`

Build notes:

- Primary words came from the Burmese word-list files in the Awesome collection.
- Myanmar tokens from `mcfnlp/Dictionary` definition columns were added as
  auxiliary entries with lower weights.
- Personal names were included at lower weights.
- Offensive/categorized phrase lists, Karen lists, and NRC metadata were not
  included.
- The generated dictionary has 146,438 entries.

License note: the requested source repositories do not contain clear root
licenses. Treat this generated artifact as requiring license review before
public redistribution.
