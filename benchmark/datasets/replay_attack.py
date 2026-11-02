"""Idiap REPLAY-ATTACK dataset loader.

**Corpus**: 1,300 videos (50 clients, 240 real + 1,000 attack) split
into `train/` / `devel/` / `test/` subsets. Attacks cover printed
photos, mobile replays, and tablet replays, each under two
`fixed`/`hand` capture conditions.

**Access**: EULA-gated download via
https://www.idiap.ch/en/dataset/replayattack

**Expected layout** under `root`:

    replayattack/
    ├── train/
    │   ├── real/
    │   └── attack/
    │       ├── fixed/
    │       └── hand/
    ├── devel/
    │   └── ...
    └── test/
        └── ...

The flat `__len__` here globs all three splits — a caller that wants
to score only the official `test/` protocol should construct the
loader with `root = /…/replayattack/test` instead of the full root.
This matches the Idiap-recommended evaluation protocol and keeps the
loader itself split-agnostic.

Citation: Chingovska, Anjos, Marcel. "On the Effectiveness of Local
Binary Patterns in Face Anti-Spoofing." BIOSIG 2012.
"""

from __future__ import annotations

from pathlib import Path

from ._base import _ResearchDataset, _RootSpec


class ReplayAttack(_ResearchDataset):
    name: str = "REPLAY-ATTACK"
    _SPEC = _RootSpec(
        # The attack/ tree is a nested fixed/hand split; we glob
        # recursively from the subclass to pick up both.
        live_subdir="real",
        attack_subdir="attack",
        video_globs=("**/*.mov", "**/*.mp4"),
        download_url="https://www.idiap.ch/en/dataset/replayattack",
        citation="Chingovska, Anjos, Marcel 2012 (BIOSIG)",
    )

    def __init__(self, root: Path | str) -> None:
        super().__init__(root)
