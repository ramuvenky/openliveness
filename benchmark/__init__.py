"""Benchmark harness for the openLiveness anti-spoofing pipeline.

ISO/IEC 30107-3 metrics (APCER, BPCER, HTER, AUC) plus a dataset-
agnostic driver that scores an iterable of samples and reports the
HTER-minimising threshold. The research datasets referenced in the
paper (MSU-MFSD, REPLAY-ATTACK, OULU-NPU) are not shipped with the
repo — see `benchmark/datasets/*.py` for the per-dataset layout and
download pointers.
"""
