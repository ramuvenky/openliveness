"""Dataset loaders for the ISO/IEC 30107-3 benchmark.

Each loader implements the `benchmark.harness.Dataset` protocol —
`name`, `__iter__`, `__len__` — so the harness is loader-agnostic.
None of the raw video corpora are shipped with this repo; the loader
docstrings point to the research-license download pages.
"""
