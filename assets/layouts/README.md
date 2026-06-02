# Importable layouts

These files are example custom layouts that can be imported from KBoard's layout
settings. The Burmese/Myanmar layouts are also mirrored into the app's built-in
layout assets for the first-class Burmese language subtype.

## Burmese / Myanmar

* `myanmar_g.json` is the default mobile-style Burmese layout, converted from
  FUTO Keyboard's `MyanmarScript/myanmar_g.yaml`.
* `myanmar_basic_main.json` is the alternate computer/basic layout, derived
  from the Keyman `basic_kbdmyan` keyboard.
* `myanmar_basic_symbols.json` is an optional symbols layout derived from
  Keyman's numeric touch layer.

The FUTO source layout is in the Apache-2.0 licensed
`futo-keyboard-layouts` repository:

* https://github.com/futo-org/futo-keyboard-layouts/blob/main/MyanmarScript/myanmar_g.yaml
* https://github.com/futo-org/futo-keyboard-layouts/blob/main/mapping.yaml
* https://github.com/futo-org/futo-keyboard-layouts/blob/main/LICENSE

See `futo-keyboard-layouts-LICENSE.md` for the Apache-2.0 license text.

The source keyboard is `Myanmar (Phonetic order) Basic` from the Keyman keyboard
repository:

* https://github.com/keymanapp/keyboards/tree/master/release/basic/basic_kbdmyan
* https://raw.githubusercontent.com/keymanapp/keyboards/master/release/basic/basic_kbdmyan/source/basic_kbdmyan.keyman-touch-layout
* https://raw.githubusercontent.com/keymanapp/keyboards/master/release/basic/basic_kbdmyan/source/basic_kbdmyan.kmn

The upstream keyboard is licensed under the MIT license. See
`keyman-basic-kbdmyan-LICENSE.md`.

The generated main layout keeps Keyman's Myanmar digit/top row and uses KBoard's
two-key final-row behavior for comma and period replacements. Shift selects the
Keyman shift-layer output, including Myanmar comma and full stop.
