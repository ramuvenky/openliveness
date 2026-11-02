"""MSU Mobile Face Spoofing Database (MSU-MFSD) loader.

**Corpus**: 280 videos recorded on a MacBook Air (built-in 640×480
camera, ProRes/.mov) and a Google Nexus 5 (720×480 front-facing camera,
H.264/.mp4) across 35 subjects. For each subject: 2 bona-fide access
videos + 6 attack videos (iPad replay, iPhone replay, printed photo —
each captured by both cameras). All in `scene01`.

**Access**: no EULA required as of late 2026 — direct download from
co-author Hu Han's homepage. Historically EULA-gated at MSU's CSE
site (now dead). Our README cites Wen/Han/Jain 2015 (TIFS); we do
NOT redistribute the dataset itself from this repo.

**Expected layout** under `root`:

    MSU-MFSD-Publish/
    ├── scene01/
    │   ├── real/
    │   │   ├── real_client001_android_SD_scene01.mp4
    │   │   ├── real_client001_android_SD_scene01.face   # ← bbox sidecar
    │   │   └── …
    │   └── attack/
    │       ├── attack_client001_android_SD_ipad_video_scene01.mp4
    │       └── …
    ├── train_sub_list.txt
    ├── test_sub_list.txt
    └── ffmpeg/   (ignored — matlab-era decoder bundle)

**Filename convention** (per MSU README):
    {real|attack}_client{NNN}_{android|laptop}_{SD|HD}_
        [attack_type_]scene01.{mp4|mov}
Live/attack is prefix-encoded; the attack-type middle field is
preserved in `Sample.source` so a caller can group APCER by medium
(printed_photo / iphone_video / ipad_video) post-hoc.

**`.face` sidecars** (PittPatt 5.0.2 auto-detections, per-frame):
    frame_idx, left, top, right, bottom, lex, ley, rex, rey
~99% of frames annotated per README. Frames without annotations are
skipped — matches the mobile pipeline's "no face → no score" rule.

**Frame sampling**: one frame per second, matching the Android
pipeline's `FACE_CROP_INTERVAL_MS = 1000L` cadence in
`MediaPipeLandmarkStream`. A 12s video → ~12 Samples, so 280 videos
→ ~3,400 Samples total. Dense enough for stable APCER/BPCER without
holding every frame in memory.

Citation: Wen, Han, Jain. "Face Spoof Detection with Image Distortion
Analysis." TIFS 2015.
"""

from __future__ import annotations

import re
from pathlib import Path
from typing import Iterator, Literal

import numpy as np

from benchmark.harness import Sample

from ._base import _ResearchDataset, _RootSpec


# Pattern for extracting the client ID from any MSU-MFSD filename
# (both real_ and attack_ follow the same `client{NNN}` convention).
# Pulled to module scope so compile cost is paid once, not per-video.
_CLIENT_ID_RE = re.compile(r"client(\d+)")


# Sample cadence — 1 Hz matches the Android `FACE_CROP_INTERVAL_MS`
# in `MediaPipeLandmarkStream`. Changing this changes both the sample
# count and the per-video temporal correlation of the scores, so pin
# it as a class-level constant rather than a per-call kwarg.
_FRAME_INTERVAL_SECONDS: float = 1.0

# Output face-crop size — must match the mobile Layer-4 pipeline's
# `FaceCropExtractor.OUT_SIZE` and the TFLite classifier's input
# shape. `lbp_histogram` would resize anyway, but emitting the shape
# the mobile pipeline sees means the benchmark Sample.frame is a
# drop-in for a mobile face crop in a debugger.
_FACE_OUT_SIZE: int = 80


class MSUMFSD(_ResearchDataset):
    name: str = "MSU-MFSD"
    _SPEC = _RootSpec(
        # MSU's archive nests both subdirs under scene01/. The base
        # class joins `root / live_subdir` so a two-segment relative
        # path works unchanged.
        live_subdir="scene01/real",
        attack_subdir="scene01/attack",
        # Mixed container formats: Nexus 5 → .mp4 (H.264); MacBook
        # Air → .mov (ProRes 444). Uppercase variants cover a few
        # pilot-distribution outliers MSU shipped with capitalised
        # extensions.
        video_globs=("*.mp4", "*.mov", "*.MOV", "*.MP4"),
        download_url="https://sites.google.com/site/huhanhomepage/datasetcode",
        citation="Wen, Han, Jain 2015 (TIFS)",
    )

    def __init__(
        self,
        root: Path | str,
        split: Literal["all", "train", "test"] = "all",
    ) -> None:
        """`split`: filter videos by MSU's canonical train/test client
        partition. `"train"` keeps only clients in `train_sub_list.txt`
        (15 clients); `"test"` keeps clients in `test_sub_list.txt`
        (20 clients); `"all"` disables filtering. Clients are disjoint
        between the two lists — this is the published protocol, used
        to avoid identity leakage when training on this corpus."""
        super().__init__(root)
        self._split = split
        self._allowed_clients = _load_split_clients(self.root, split)

    def __len__(self) -> int:
        """Video count (not frame count) honouring the split filter.

        Overrides the base's unfiltered count so the harness's
        `len(dataset) == 0` guard reflects reality on a split that
        happens to contain zero videos (e.g. a user who typo'd the
        sub-list file into empty).
        """
        return sum(
            1
            for subdir in (self._live_dir, self._attack_dir)
            for path in self._video_paths(subdir)
            if _video_belongs_to_split(path, self._allowed_clients)
        )

    def __iter__(self) -> Iterator[Sample]:
        # Live first, then attacks — matches the harness's downstream
        # label-ordering assumptions in no way (it collects both before
        # scoring) but keeps the output stream readable in debuggers.
        yield from self._iter_split(self._live_dir, label=1)
        yield from self._iter_split(self._attack_dir, label=0)

    def _iter_split(self, split_dir: Path, label: int) -> Iterator[Sample]:
        for video_path in self._video_paths(split_dir):
            if not _video_belongs_to_split(video_path, self._allowed_clients):
                continue
            face_path = video_path.with_suffix(".face")
            # No `.face` sidecar → PittPatt didn't annotate this video.
            # Skipping is the right call: feeding an unannotated frame
            # would require re-running face detection in Python, which
            # we deliberately avoid (the mobile pipeline uses the same
            # "no face box → no score" rule).
            if not face_path.exists():
                continue
            face_boxes = _read_face_file(face_path)
            yield from _iter_video(video_path, face_boxes, label)


def _load_split_clients(
    root: Path, split: Literal["all", "train", "test"]
) -> set[int] | None:
    """Return the set of client IDs to keep, or `None` for "all clients".

    Returning `None` for the all-case lets `_video_belongs_to_split`
    short-circuit without the per-video regex match — important
    because the all-split run processes all 280 videos and the hot
    path should pay zero cost per video for "no filter".
    """
    if split == "all":
        return None
    filename = {"train": "train_sub_list.txt", "test": "test_sub_list.txt"}[split]
    list_path = root / filename
    if not list_path.exists():
        # Loud failure — a user who typo'd `split="test"` on a corpus
        # that doesn't ship the subject-list files gets a clear
        # error, not a silently-empty iteration.
        raise FileNotFoundError(
            f"MSU-MFSD: {filename} not found at {list_path!r}. "
            "The sub-list files ship with the official corpus — if your "
            "copy is missing them, re-download or pass split='all'."
        )
    # One client ID per line, zero-padded in the file but we store ints
    # so `_video_belongs_to_split` doesn't have to format the lookup key.
    return {int(line.strip()) for line in list_path.read_text().splitlines() if line.strip()}


def _video_belongs_to_split(video_path: Path, allowed: set[int] | None) -> bool:
    if allowed is None:
        return True
    match = _CLIENT_ID_RE.search(video_path.name)
    if match is None:
        # Filename doesn't fit the MSU convention — defensive skip
        # rather than include. The convention is pinned in the module
        # docstring; a non-conforming file is almost certainly stray.
        return False
    return int(match.group(1)) in allowed


def _read_face_file(path: Path) -> dict[int, tuple[int, int, int, int]]:
    """Parse a PittPatt `.face` sidecar into {frame_idx: (l, t, r, b)}.

    The format is one line per annotated frame:
        frame_idx, left, top, right, bottom, lex, ley, rex, rey
    Eye coordinates are preserved in the file but dropped here — the
    LBP branch only needs the face rectangle. If a future ablation
    wants the eyes (e.g., for geometric normalisation), extend the
    tuple without changing the lookup key.
    """
    boxes: dict[int, tuple[int, int, int, int]] = {}
    for line in path.read_text().splitlines():
        parts = line.split(",")
        if len(parts) < 5:
            continue
        try:
            idx = int(parts[0].strip())
            left = int(parts[1].strip())
            top = int(parts[2].strip())
            right = int(parts[3].strip())
            bottom = int(parts[4].strip())
        except ValueError:
            # Blank line, header comment, or a float-only eye coord row
            # — skip without erroring. PittPatt occasionally emits
            # mixed-type rows for frames where only eyes were detected.
            continue
        boxes[idx] = (left, top, right, bottom)
    return boxes


def _iter_video(
    video_path: Path,
    face_boxes: dict[int, tuple[int, int, int, int]],
    label: int,
) -> Iterator[Sample]:
    """Stream Samples out of one video — one per `_FRAME_INTERVAL_SECONDS`.

    Local cv2 import so a user without opencv-python installed gets a
    clean RuntimeError when they try to iterate (same guard pattern as
    `scorers.py`); the module itself stays importable for tests that
    don't touch real video.
    """
    try:
        import cv2  # type: ignore
    except ImportError as e:  # pragma: no cover
        raise RuntimeError(
            "MSU-MFSD __iter__ requires opencv-python; "
            "install opencv-python-headless before running the benchmark CLI."
        ) from e

    cap = cv2.VideoCapture(str(video_path))
    try:
        if not cap.isOpened():
            # One corrupted .mov shouldn't take down the whole benchmark
            # run — skip silently and let the harness count the rest.
            # The source name is in Sample.source for downstream videos
            # so any statistical oddity is traceable.
            return
        fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
        step = max(1, int(round(fps * _FRAME_INTERVAL_SECONDS)))

        idx = 0
        while True:
            ok, frame_bgr = cap.read()
            if not ok:
                break
            if idx % step == 0:
                bbox = face_boxes.get(idx)
                # `.face` annotates ~99% of frames per MSU README; the
                # 1% unannotated are skipped (consistent with the "no
                # face → no score" mobile contract). No logging — a
                # per-frame miss is normal, not an error.
                if bbox is not None:
                    crop = _crop_face_grayscale(frame_bgr, bbox, _FACE_OUT_SIZE)
                    if crop is not None:
                        yield Sample(
                            frame=crop,
                            label=label,
                            # idx — not seconds — so a reviewer can
                            # replay the exact frame in a .face lookup
                            # without re-deriving fps.
                            source=f"{video_path.name}:{idx}",
                        )
            idx += 1
    finally:
        cap.release()


def _crop_face_grayscale(
    frame_bgr: np.ndarray,
    bbox: tuple[int, int, int, int],
    out_size: int,
) -> np.ndarray | None:
    """BGR frame + PittPatt bbox → 80×80 uint8 grayscale face crop.

    Returns None for degenerate boxes (zero-area after clamping),
    which happens occasionally when PittPatt's rectangle sits partly
    outside the frame on panned shots. Caller skips those — matches
    the mobile `FaceCropExtractor` behaviour on an off-frame face.
    """
    import cv2  # type: ignore

    left, top, right, bottom = bbox
    h, w = frame_bgr.shape[:2]
    left = max(0, left)
    top = max(0, top)
    right = min(w, right)
    bottom = min(h, bottom)
    if right <= left or bottom <= top:
        return None
    crop = frame_bgr[top:bottom, left:right]
    gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
    return cv2.resize(gray, (out_size, out_size))
