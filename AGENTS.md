# AGENTS.md

**Read [`HANDOVER.md`](HANDOVER.md) before doing anything in this repository.** It is the single
live handover file: device safety gates (only one phone may be touched), the verification discipline
(`installed ≠ loaded ≠ loaded-new`), the architectural invariants, what is proven vs. still waiting
on HyperOS 3 field reports, and what the owner has decided but **not yet approved for
implementation**. [`CLAUDE.md`](CLAUDE.md) mirrors the code map; `MODIFICATIONS.md` is the GPL-3.0
§5a change log that every delivery round must append to.

Rules that are not in the code and get people fired: local commits only (no pushing), never touch
the second connected device, never route the signing passphrase through an agent, ask before any
irreversible action on the owner's daily-driver phone, and never claim a fix without the three
positive proofs described in `HANDOVER.md` §2.
