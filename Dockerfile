FROM debian:bookworm-slim AS downloader

ARG FRIGATE_VERSION=1.5.3

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /opt \
    && curl -fsSL \
       "https://github.com/sparrowwallet/frigate/releases/download/${FRIGATE_VERSION}/frigate-${FRIGATE_VERSION}-x86_64.tar.gz" \
       | tar -xz -C /opt \
    && test -x /opt/frigate/bin/frigate


FROM debian:bookworm-slim

# Required on Linux if Frigate falls back to/use OpenCL.
# NVIDIA CUDA itself does not require the CUDA toolkit in the container.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ocl-icd-libopencl1 \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /etc/OpenCL/vendors \
    && echo 'libnvidia-opencl.so.1' > /etc/OpenCL/vendors/nvidia.icd

COPY --from=downloader /opt/frigate /opt/frigate
COPY --chmod=755 docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh

ENV HOME=/var/lib/frigate

# No useradd/groupadd necessary.
# Numeric ownership works without passwd/group entries.
RUN mkdir -p /var/lib/frigate \
    && chown 1000:1000 /var/lib/frigate

USER 1000:1000

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
