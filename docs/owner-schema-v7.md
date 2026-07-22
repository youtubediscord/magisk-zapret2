# Owner metadata schema v7

Compatible packages declare `owner_protocol|7|zapret2-firewall` immediately
after `schema|1|zapret2-runtime` in `zapret2/runtime-manifest.tsv`.

Schema v7 keeps the 33-field v6 firewall contract but replaces the unbounded
`argv_hex` payload with `argv_sha256`, the lowercase SHA-256 of the raw
NUL-separated `/proc/PID/cmdline` bytes. Before lifecycle mutation or process
termination, the module recomputes that digest and also verifies PID, process
start time, boot ID, exact argv0, executable path and NFQUEUE number.

The exact key order is:

`version`, `pid`, `starttime`, `argv_sha256`, `qnum`, `exe`, `generation`,
`boot_id`, `phase`, `install_generation`, `install_archive_sha256`,
`firewall_tag`, `out_chain`, `in_chain`, `ports_tcp`, `ports_udp`,
`stun_ports`, `pkt_out`, `pkt_in`, `desync_mark`, `ipv4_active`,
`ipv6_active`, `ipv4_connbytes`, `ipv4_multiport`, `ipv4_mark`,
`ipv6_connbytes`, `ipv6_multiport`, `ipv6_mark`, `ipv4_rules`,
`ipv6_rules`, `ipv4_spec`, `ipv6_spec`, `firewall_fingerprint`.

Current v7 records are limited to 64 KiB and normal records remain below 4
KiB. A separate 1 MiB read envelope exists only to classify legacy schemas
that duplicated a bounded command as hexadecimal. Android may use a valid
same-boot v6 record for read-only status display; shell lifecycle mutations
require v7 and migrate through an explicit restart/update boundary.

All owner files remain root-owned regular single-link files with mode `0600`.
Duplicate, missing, reordered, unknown or malformed fields fail closed.
