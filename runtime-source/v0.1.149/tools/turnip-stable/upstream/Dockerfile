# SPDX-License-Identifier: MIT
FROM ubuntu:24.04@sha256:33ceb71981b602c1a7443a53469e4dba065f7503eab3078a2d7a57a2ab987517

ENV DEBIAN_FRONTEND=noninteractive \
    ANDROID_NDK_HOME=/opt/android-ndk-r29 \
    API_LEVEL=34 \
    PATH=/opt/venv/bin:/opt/android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH

RUN apt-get update && apt-get install -y --no-install-recommends \
    bison \
    build-essential \
    ca-certificates \
    clang \
    curl \
    file \
    flex \
    git \
    glslang-tools \
    libdrm-dev \
    libelf-dev \
    libexpat1-dev \
    lld \
    meson \
    ninja-build \
    patchelf \
    pkg-config \
    python3 \
    python3-mako \
    python3-packaging \
    python3-ply \
    python3-pip \
    python3-venv \
    unzip \
    wget \
    zip \
    zlib1g-dev \
    && rm -rf /var/lib/apt/lists/*

RUN python3 -m venv --system-site-packages /opt/venv \
    && /opt/venv/bin/python -m pip install --no-cache-dir \
        'meson>=1.4.0,<2' \
        'PyYAML==6.0.2' \
    && /opt/venv/bin/python -c 'import yaml; print("PyYAML", yaml.__version__)' \
    && /opt/venv/bin/meson --version

WORKDIR /opt

RUN curl -fsSL \
      https://dl.google.com/android/repository/android-ndk-r29-linux.zip \
      -o /tmp/android-ndk.zip \
    && echo '87e2bb7e9be5d6a1c6cdf5ec40dd4e0c6d07c30b  /tmp/android-ndk.zip' | sha1sum -c - \
    && unzip -q /tmp/android-ndk.zip -d /opt \
    && rm -f /tmp/android-ndk.zip

COPY build.sh /opt/build.sh
RUN chmod 0755 /opt/build.sh

ENTRYPOINT ["/opt/build.sh"]
