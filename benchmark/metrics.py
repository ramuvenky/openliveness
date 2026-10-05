"""ISO/IEC 30107-3 metrics: APCER, BPCER, HTER, AUC."""


def compute_metrics(scores, labels, threshold=0.5):
    """TODO Phase 5: compute APCER/BPCER/HTER/AUC at the given threshold.

    Args:
        scores: list[float] in [0.0, 1.0]; higher = more likely live
        labels: list[int]; 1 = live (bona fide), 0 = attack
        threshold: decision threshold

    Returns:
        {'apcer': float, 'bpcer': float, 'hter': float, 'auc': float}
    """
    raise NotImplementedError
