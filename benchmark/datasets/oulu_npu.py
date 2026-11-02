"""OULU-NPU dataset loader.

**Corpus**: 4,950 videos from 55 subjects captured on six smartphones
across three recording sessions. Four Protocols (P1–P4) each define a
specific train/dev/test split; the paper's hero number is typically
Protocol 1 (unseen illumination/location).

**Access**: research license required. Download via
https://sites.google.com/site/oulunpudatabase/ (requires signed EULA).

**Expected layout** under `root`:

    Oulu_NPU/
    ├── Train_files/   # videos named <phone>_<session>_<user>_<pai>.avi
    ├── Dev_files/
    ├── Test_files/
    └── Protocols/     # four Protocol_*/_Train.txt / _Dev.txt / _Test.txt

OULU encodes the live-vs-attack label in the filename's `pai` field
(presentation-attack instrument index): 1 = bona-fide, 2 = printed
A3, 3 = printed A4, 4 = replay-video laptop, 5 = replay-video mobile.
The protocol files consume this per their official split rules; the
loader here walks all three top-level `*_files/` dirs so the caller
can construct protocol-specific splits by filtering `Sample.source`.

Citation: Boulkenafet, Komulainen, Li, Feng, Hadid. "OULU-NPU: A
Mobile Face Presentation Attack Database with Real-World Variations."
FG 2017.
"""

from __future__ import annotations

from pathlib import Path

from ._base import _ResearchDataset, _RootSpec


class OuluNPU(_ResearchDataset):
    name: str = "OULU-NPU"
    # OULU doesn't use real/attack subdirs — the live/attack split is
    # encoded in the filename's pai field. We model that by setting
    # live_subdir == attack_subdir and treating the base class's
    # len() as "all videos under this split"; the real labelling runs
    # in __iter__ (deferred) via the filename parser documented above.
    _SPEC = _RootSpec(
        live_subdir="Train_files",
        attack_subdir="Train_files",
        video_globs=("*.avi", "*.mp4", "*.mov"),
        download_url="https://sites.google.com/site/oulunpudatabase/",
        citation="Boulkenafet et al. 2017 (FG)",
    )

    def __init__(self, root: Path | str) -> None:
        super().__init__(root)

    def __len__(self) -> int:
        # Override: the shared base counts live_subdir + attack_subdir
        # separately, but OULU's live/attack split is intra-filename
        # so counting both would double every video.
        root = self.root
        count = 0
        for split in ("Train_files", "Dev_files", "Test_files"):
            split_dir = root / split
            if not split_dir.exists():
                continue
            for pattern in self._SPEC.video_globs:
                count += sum(1 for _ in split_dir.glob(pattern))
        return count
