# Build from repo root:
#   docker build -f core/Dockerfile -t openliveness-core .
# Dev shell with repo bind-mounted (edits in your editor reflect inside):
#   docker run --rm -it -v "$PWD":/workspace openliveness-core
# Run tests:
#   docker run --rm -v "$PWD":/workspace openliveness-core pytest core

FROM python:3.11-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

# Runtime libs: libgl1 + libglib2.0 are OpenCV/MediaPipe needs; ffmpeg provides
# video codecs the benchmark harness uses to read MSU-MFSD / Replay-Attack clips.
RUN apt-get update && apt-get install -y --no-install-recommends \
        libgl1 \
        libglib2.0-0 \
        libsm6 \
        libxext6 \
        libxrender1 \
        ffmpeg \
        git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace

COPY core/requirements.txt /tmp/requirements.txt
RUN pip install -r /tmp/requirements.txt

CMD ["bash"]
