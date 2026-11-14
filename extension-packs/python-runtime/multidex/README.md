# Runtime multi-DEX probe

`RuntimeMultidexProbe.java` is compiled by `xtask extensions pack` with the
project JDK and Android build-tools (`d8 --min-api 28`) into `classes2.dex`. The task appends
that DEX to the unsigned standalone runtime container so every published
runtime exercises the same ordered multi-DEX loader path used for larger
future runtimes.
