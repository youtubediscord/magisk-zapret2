# Owner metadata schema v6

Compatible packages declare the exact ordered metadata line
`owner_protocol|6|zapret2-firewall` immediately after
`schema|1|zapret2-runtime` in `zapret2/runtime-manifest.tsv`. Missing,
duplicate, reordered, or non-v6 protocol metadata makes the package
incompatible before install or update staging begins.

Schema v6 extends the ordered v5 owner record. The file remains root-owned,
regular, single-link, mode `0600`, and duplicate, missing, reordered, or unknown
keys are invalid.

The exact key order is:

`version`, `pid`, `starttime`, `argv_hex`, `qnum`, `exe`, `generation`,
`boot_id`, `phase`, `install_generation`, `install_archive_sha256`,
`firewall_tag`, `out_chain`, `in_chain`, `ports_tcp`, `ports_udp`,
`stun_ports`, `pkt_out`, `pkt_in`, `desync_mark`, `ipv4_active`,
`ipv6_active`, `ipv4_connbytes`, `ipv4_multiport`, `ipv4_mark`,
`ipv6_connbytes`, `ipv6_multiport`, `ipv6_mark`, `ipv4_rules`,
`ipv6_rules`, `ipv4_spec`, `ipv6_spec`, `firewall_fingerprint`.

`firewall_tag` is exactly ten ASCII alphanumeric characters. `out_chain` must
equal `Z2O_<firewall_tag>` and `in_chain` must equal
`Z2I_<firewall_tag>`. Both names are therefore generation-bound and within the
28-character basic iptables chain-name limit. The two family specs include the
tag and exact chain names, and `firewall_fingerprint` remains the lowercase
SHA-256 of the two newline-delimited specs.

Every NFQUEUE payload is isolated in a deterministic per-rule chain named
`Z2R_<firewall_tag>_O<ordinal>` or `Z2R_<firewall_tag>_I<ordinal>`. The main
output/input chains contain only the corresponding unique jumps. Health and
teardown require the complete relation `main jump -> one per-rule chain -> one
exact payload`; missing, duplicate, extra, or cross-referenced objects fail
closed. Teardown consumes the unique jump, exact payload, and exact subchain in
that order and never uses a mutable numeric rule position.

Schema v5 is read-only legacy evidence. Same-boot mutation of a v5 direct-rule
generation is rejected because it has no stable generation-bound kernel
identity. A clean different-boot recovery may retire it only after the existing
exact process and successful full-family absence proof.

Android parsers should accept v6 as the current writable schema, display v5 as
legacy/restart-required, and must not synthesize missing v6 identity fields.
