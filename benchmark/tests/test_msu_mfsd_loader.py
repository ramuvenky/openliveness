"""Tests for the MSU-MFSD `__iter__` wiring.

Synthesises a tiny MSU-MFSD-shaped directory tree in `tmp_path`:
one real + one attack video, each 90 frames at 30 fps (3 seconds)
with a hand-written `.face` sidecar annotating every frame. The
tests then iterate the loader and verify shape, label parsing, and
the one-frame-per-second cadence.

No real MSU data is read here — the fixtures use cv2.VideoWriter to
emit a dummy mp4 so CI boxes without the licensed corpus still get
full coverage of the parsing + cropping path.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
import pytest

cv2 = pytest.importorskip("cv2")

from benchmark.datasets.msu_mfsd import (  # noqa: E402  (importorskip gate)
    MSUMFSD,
    _read_face_file,
)


def _write_synthetic_video(path: Path, n_frames: int = 90, fps: int = 30) -> None:
    """Emit an mp4 with `n_frames` solid-grey frames at `fps`.

    Face content doesn't matter — the LBP classifier is downstream of
    the loader; the loader's job is just to decode + crop + yield. A
    constant grey frame keeps the test deterministic and ~5 KB on disk.
    """
    # mp4v is cv2's most reliable fourcc on macOS without ffmpeg plumbing.
    # Writer may silently fail if the codec isn't available; the resulting
    # file would be empty — the test's non-zero sample count assertion
    # catches that.
    fourcc = cv2.VideoWriter_fourcc(*"mp4v")
    writer = cv2.VideoWriter(str(path), fourcc, fps, (160, 120))
    assert writer.isOpened(), f"VideoWriter failed to open {path}"
    frame = np.full((120, 160, 3), 128, dtype=np.uint8)
    for _ in range(n_frames):
        writer.write(frame)
    writer.release()


def _write_face_sidecar(
    path: Path, n_frames: int, bbox: tuple[int, int, int, int] = (40, 20, 120, 100)
) -> None:
    """Annotate every frame with the same face bbox.

    Matches the PittPatt format MSU ships: one line per frame,
    `idx, l, t, r, b, lex, ley, rex, rey`. Eye coords are present
    but will be ignored by `_read_face_file` (bbox-only lookup).
    """
    lines = []
    for i in range(n_frames):
        # Dummy eye coords inside the bbox — only the first 5 numbers
        # are read, so these are just filler for format-fidelity.
        lines.append(f"{i}, {bbox[0]}, {bbox[1]}, {bbox[2]}, {bbox[3]}, 60, 50, 100, 50")
    path.write_text("\n".join(lines) + "\n")


@pytest.fixture
def fake_msu_root(tmp_path: Path) -> Path:
    """Build the MSU-MFSD-shaped layout: scene01/real + scene01/attack
    with one annotated video each. Both tests reuse this to avoid
    paying the VideoWriter cost twice."""
    real_dir = tmp_path / "scene01" / "real"
    attack_dir = tmp_path / "scene01" / "attack"
    real_dir.mkdir(parents=True)
    attack_dir.mkdir(parents=True)

    real_video = real_dir / "real_client001_android_SD_scene01.mp4"
    _write_synthetic_video(real_video, n_frames=90)
    _write_face_sidecar(real_video.with_suffix(".face"), n_frames=90)

    attack_video = attack_dir / "attack_client001_android_SD_printed_photo_scene01.mp4"
    _write_synthetic_video(attack_video, n_frames=90)
    _write_face_sidecar(attack_video.with_suffix(".face"), n_frames=90)

    return tmp_path


def test_iter_yields_both_classes(fake_msu_root: Path):
    dataset = MSUMFSD(fake_msu_root)
    samples = list(dataset)
    labels = [s.label for s in samples]
    # 1 Hz cadence × 3-second video = 3 Samples per video × 2 videos
    # = 6 Samples. Pin the exact count — a cadence regression (e.g.
    # someone switching to 2 Hz) would silently double the dataset
    # size and inflate the paper table's `n_samples`.
    assert len(samples) == 6
    assert labels.count(1) == 3
    assert labels.count(0) == 3


def test_iter_produces_80x80_grayscale_uint8(fake_msu_root: Path):
    # Pin the output shape — the mobile Layer-4 pipeline consumes
    # 80×80 uint8 crops, so the benchmark harness must emit the same
    # shape for the TFLite scorer to be a drop-in. A silent shape
    # change here would still "work" via lbp_histogram's internal
    # resize but would hide a mobile/benchmark drift.
    dataset = MSUMFSD(fake_msu_root)
    for sample in dataset:
        assert sample.frame.shape == (80, 80)
        assert sample.frame.dtype == np.uint8


def test_iter_source_tag_names_video_and_frame_index(fake_msu_root: Path):
    # Source format is `{video_filename}:{frame_idx}` so a failure
    # cluster can be traced back to a specific frame of a specific
    # video — important for debugging a per-video spoof-detection
    # miss. Pin the format so a future "cleanup" doesn't drop the
    # frame index.
    dataset = MSUMFSD(fake_msu_root)
    sources = [s.source for s in dataset]
    assert all(":" in src for src in sources)
    assert any("real_client001" in src for src in sources)
    assert any("attack_client001" in src for src in sources)
    # Verify at least one source references a non-zero frame index —
    # proves the sampler is actually walking across frames, not just
    # emitting frame 0 repeatedly.
    frame_indices = {int(s.source.split(":")[1]) for s in dataset}
    assert len(frame_indices) > 1


def test_iter_skips_videos_without_face_sidecar(tmp_path: Path):
    # A video without a `.face` sidecar must be silently skipped —
    # matches the mobile pipeline's "no face box → no score" rule
    # and prevents re-running face detection in Python.
    (tmp_path / "scene01" / "real").mkdir(parents=True)
    (tmp_path / "scene01" / "attack").mkdir(parents=True)
    video = tmp_path / "scene01" / "real" / "real_client001_android_SD_scene01.mp4"
    _write_synthetic_video(video, n_frames=30)
    # Deliberately no `.face` sidecar.
    dataset = MSUMFSD(tmp_path)
    assert list(dataset) == []


def test_read_face_file_ignores_malformed_lines(tmp_path: Path):
    # PittPatt occasionally emits blank lines or eye-only rows. The
    # parser must tolerate these without raising — missing frames
    # just get skipped at iteration time.
    face_path = tmp_path / "sample.face"
    face_path.write_text(
        "0, 10, 20, 100, 150, 30, 40, 80, 40\n"
        "\n"  # blank
        "not-a-row\n"  # garbage
        "2, 15, 25, 105, 155, 32, 42, 82, 42\n"
    )
    boxes = _read_face_file(face_path)
    assert set(boxes.keys()) == {0, 2}
    assert boxes[0] == (10, 20, 100, 150)
    assert boxes[2] == (15, 25, 105, 155)


def test_split_train_filters_to_train_clients(fake_msu_root: Path):
    # Add train/test sub-list files that match MSU's convention: one
    # client id per line. The fixture already has client001 videos;
    # putting client001 in train but not test means split='train'
    # yields the samples and split='test' yields none.
    (fake_msu_root / "train_sub_list.txt").write_text("1\n")
    (fake_msu_root / "test_sub_list.txt").write_text("2\n")

    train_samples = list(MSUMFSD(fake_msu_root, split="train"))
    test_samples = list(MSUMFSD(fake_msu_root, split="test"))
    all_samples = list(MSUMFSD(fake_msu_root, split="all"))

    assert len(train_samples) == len(all_samples) > 0
    assert len(test_samples) == 0


def test_split_test_filters_to_test_clients(fake_msu_root: Path):
    # Inverted: client001 is only in the test list.
    (fake_msu_root / "train_sub_list.txt").write_text("2\n")
    (fake_msu_root / "test_sub_list.txt").write_text("1\n")

    test_samples = list(MSUMFSD(fake_msu_root, split="test"))
    train_samples = list(MSUMFSD(fake_msu_root, split="train"))

    assert len(test_samples) > 0
    assert len(train_samples) == 0


def test_split_requires_sub_list_file(fake_msu_root: Path):
    # No sub-list files on disk → split="train" must fail loudly at
    # construction time, not silently return zero samples at iter time.
    # (Protects against a dev who typo'd a split name.)
    with pytest.raises(FileNotFoundError, match="train_sub_list.txt"):
        MSUMFSD(fake_msu_root, split="train")


def test_degenerate_bbox_crop_is_skipped(tmp_path: Path):
    # A bbox fully outside the frame produces a zero-area crop after
    # clamping. The loader must emit no Sample for that frame rather
    # than yielding an empty array — matches the mobile pipeline's
    # off-frame-face behaviour.
    (tmp_path / "scene01" / "real").mkdir(parents=True)
    (tmp_path / "scene01" / "attack").mkdir(parents=True)
    video = tmp_path / "scene01" / "real" / "real_client001_android_SD_scene01.mp4"
    _write_synthetic_video(video, n_frames=30)
    # Video is 160×120; a bbox at (500, 500, 600, 600) clamps to a
    # 0-area region.
    _write_face_sidecar(
        video.with_suffix(".face"), n_frames=30, bbox=(500, 500, 600, 600)
    )
    # Need an attack video too so `_ResearchDataset` doesn't look
    # oddly asymmetric; it just needs to exist.
    attack = tmp_path / "scene01" / "attack" / "attack_client001_android_SD_printed_photo_scene01.mp4"
    _write_synthetic_video(attack, n_frames=30)
    _write_face_sidecar(attack.with_suffix(".face"), n_frames=30, bbox=(500, 500, 600, 600))

    dataset = MSUMFSD(tmp_path)
    assert list(dataset) == []
