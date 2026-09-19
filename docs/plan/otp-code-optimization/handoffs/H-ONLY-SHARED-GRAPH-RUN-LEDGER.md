# H-only shared-graph run ledger

All runs were executed on 2026-09-15 on the host described in
`H-ONLY-SHARED-GRAPH-RESULT.md`. `A` is upstream baseline and `H` is the H-only worktree.
The shared graph SHA for every row is
`178375c8ebed47178d9ec3c09c45e526bc483775aabab0043a62c7dc1b6515d0`.

## S4 performance/RSS matrix

Each row is one fresh JVM, `-Xmx8g`, `-XX:+UseCompactObjectHeaders`, `-p md -n 4 -i 3 -0`.
Worker and Total are means of the four SpeedTest samples. p50/p95 are nearest-rank values
from the final sample's 408 `Total times(ms)` values. RSS is actual JVM PID sampled at about
0.5 s; it is a whole-process sampled peak. Swap deltas were zero in every row.

| Pair | Order | Exit | Expected matches | Worker mean (ms) | Total mean (ms) | Case p50/p95 (ms) | RSS peak (KiB) | RSS median (KiB) | RSS samples / max gap |
| ---: | :---: | ---: | :---: | ---: | ---: | :---: | ---: | ---: | :---: |
| 01 | A | 0 | 233/408 | 48.00 | 88.50 | 78 / 168 | 5,483,888 | 3,436,144 | 286 / 574 ms |
| 01 | H | 0 | 233/408 | 53.50 | 93.25 | 78.5 / 187 | 5,106,448 | 3,534,112 | 299 / 632 ms |
| 02 | H | 0 | 233/408 | 56.75 | 97.00 | 89 / 241 | 5,239,136 | 3,422,256 | 309 / 697 ms |
| 02 | A | 0 | 233/408 | 51.75 | 94.25 | 80 / 171 | 5,237,920 | 3,526,592 | 303 / 572 ms |
| 03 | A | 0 | 233/408 | 52.25 | 94.25 | 81 / 177 | 5,452,176 | 3,437,808 | 303 / 563 ms |
| 03 | H | 0 | 233/408 | 55.25 | 94.75 | 82 / 180 | 5,300,336 | 3,526,912 | 304 / 621 ms |
| 04 | H | 0 | 233/408 | 57.00 | 96.00 | 81 / 187 | 5,294,736 | 3,508,032 | 307 / 631 ms |
| 04 | A | 0 | 233/408 | 51.00 | 92.75 | 80 / 173 | 4,568,640 | 3,394,592 | 298 / 598 ms |
| 05 | A | 0 | 233/408 | 53.00 | 95.50 | 82 / 177 | 5,305,312 | 3,573,928 | 306 / 587 ms |
| 05 | H | 0 | 233/408 | 55.00 | 94.50 | 81 / 185 | 5,282,000 | 3,494,560 | 303 / 565 ms |
| 06 | H | 0 | 233/408 | 53.50 | 92.25 | 79.5 / 185 | 5,282,048 | 3,502,768 | 296 / 616 ms |
| 06 | A | 0 | 233/408 | 51.75 | 95.25 | 80 / 175 | 5,275,328 | 3,507,456 | 305 / 557 ms |

Raw stdout/stderr, metadata, RSS TSV, swap logs, and result CSV are under
`/private/tmp/otp-h-only-shared-graph-matrix-wQdlIL/logs` and `.../results`. Every result CSV
has 1,207 data rows and SHA-256
`f417493103bba636a2ab69f06c1042e7cadf08b0ebc9498334d5b0ff96f41b43`.

## S5 JFR diagnosis matrix

JFR used `settings=profile`, `duration=190s`, and the same graph/flags/SpeedTest arguments.
These runs are separate from S4 and are not included in its latency averages.

| Label | Order | Exit | Wall (s) | JFR bytes | Allocation samples | GCs | GC pause sum / max (ms) | JFR SHA-256 |
| :--- | :--- | ---: | ---: | ---: | ---: | ---: | ---: | :--- |
| jfr-01-A | A | 0 | 167.189 | 6,993,168 | 47,630 | 113 | 1,664.6 / 209.4 | `c2ea07282093b025247707021fbe2bce6ffd108113b4d269e6d8e11dc34cd9c8` |
| jfr-01-H | H | 0 | 161.183 | 6,804,192 | 46,419 | 104 | 1,540.9 / 195.2 | `1547a610b892ee763061176b49bf823938f83665a367285b774858f01c532fb1` |
| jfr-02-H | H | 0 | 171.499 | 7,032,876 | 49,062 | 114 | 1,616.8 / 208.3 | `6a82f8f530cc82502c77308c6a6ba8b919e06f04f05c430621ce5937c2742a0f` |
| jfr-02-A | A | 0 | 167.701 | 6,977,503 | 47,867 | 113 | 1,680.0 / 221.9 | `48903876c9cfbe34e1e0ae695c9c68183a8ab651698d0cea7e8943d059660d60` |

Raw JFR and metadata are under `/private/tmp/otp-h-only-shared-graph-jfr-1sW27u/jfr` and
`.../logs`. All four JFR result CSVs have the same result hash as S4 and the same 233/408
official expected-match count.

## Artifact and command pointers

- S4 metadata: `/private/tmp/otp-h-only-shared-graph-matrix-wQdlIL/logs/pair-*-meta.txt`.
- S4 raw logs: `/private/tmp/otp-h-only-shared-graph-matrix-wQdlIL/logs/pair-*.log`.
- S4 RSS/swap: `/private/tmp/otp-h-only-shared-graph-matrix-wQdlIL/logs/pair-*-rss.tsv` and
  `pair-*-swap.log`.
- S5 metadata/logs: `/private/tmp/otp-h-only-shared-graph-jfr-1sW27u/logs`.
- Exact Maven-generated dependency classpath:
  `/private/tmp/otp-h-only-classpath.txt`, SHA-256
  `3fffddf2ed4137e1e2497aca9a1b27fe80231035b08c14c24eda1b5177438916`.

The temporary directories are not a durable public artifact store. Copy required raw files to
an approved persistent location before linking them from an upstream issue or pull request.
