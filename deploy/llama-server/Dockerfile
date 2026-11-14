# llama-server 的 CUDA 版。不用官方的 ghcr.io/ggml-org/llama.cpp:server-cuda：本機從 ghcr 拉太慢，
# 自己編反而快（數字見 docs/adr/0002）。EC2 用 gpu 時也在機器上 build 這份。
#
# 兩段式：build 段用 devel 映像檔（有 nvcc，好幾 GB）編譯，最後的映像檔只有 runtime 段，只帶 CUDA 函式庫和編好的執行檔。

# llama.cpp 跟 C:\llm\llama.cpp 的原生 build 同一版，比較原生和容器時，差異才只來自容器
ARG LLAMA_BUILD=b10964
# CUDA 13 要主機驅動 >= 580。GPU 機的驅動比較舊的話，改成 12.9.1 重 build
ARG CUDA_VERSION=13.0.1
ARG UBUNTU_VERSION=24.04

# ════════════════════════════════════════════════════════════════════
FROM nvidia/cuda:${CUDA_VERSION}-devel-ubuntu${UBUNTU_VERSION} AS build

ARG LLAMA_BUILD
# 只編 89（Ada：RTX 40 系列、雲端的 L4）。不指定的話每一代 GPU 都編一份，慢好幾倍。
# 換別的卡要改這個值重 build：T4 = 75、A10G = 86、A100 = 80、H100 = 90
ARG CUDA_ARCH=89

RUN apt-get update \
    && apt-get install -y --no-install-recommends build-essential cmake \
    && rm -rf /var/lib/apt/lists/*

# tarball 跟這串 SHA256 對不上就 build 失敗，被換掉或竄改的原始碼編不進來。
# 升級 LLAMA_BUILD 時一起換：下載新版 tarball，用 sha256sum 算出來貼上
ADD --checksum=sha256:4c96d72c40cefdacf621457d8a12fe5673b8de5b640ff7fe6d342ea1c579a9fe \
    https://github.com/ggml-org/llama.cpp/archive/refs/tags/${LLAMA_BUILD}.tar.gz /tmp/src.tar.gz
RUN mkdir /src && tar xzf /tmp/src.tar.gz -C /src --strip-components=1

WORKDIR /src
RUN cmake -B build \
        -DCMAKE_BUILD_TYPE=Release \
        -DGGML_CUDA=ON \
        -DCMAKE_CUDA_ARCHITECTURES=${CUDA_ARCH} \
        # 不針對編譯的這台 CPU 最佳化：用了這台才有的指令集，換到別台會 Illegal instruction 當掉
        -DGGML_NATIVE=OFF \
        # 函式庫都靜態連進 llama-server，runtime 段只要搬這一個檔案
        -DBUILD_SHARED_LIBS=OFF \
        # 預設會去 Hugging Face 下載網頁聊天介面；app 只呼叫 API，用不到
        -DLLAMA_USE_PREBUILT_UI=OFF \
        # HTTPS 用戶端只給 -hf 從網路下載模型用，這裡的模型是掛載進來的
        -DLLAMA_OPENSSL=OFF \
        -DLLAMA_BUILD_TESTS=OFF \
        -DLLAMA_BUILD_EXAMPLES=OFF \
        # build 的環境沒有 GPU 驅動，libcuda.so 只有 stub，連結時先不檢查；執行時由主機的驅動經 nvidia runtime 提供
        -DCMAKE_EXE_LINKER_FLAGS=-Wl,--allow-shlib-undefined \
    && cmake --build build --target llama-server -j"$(nproc)"

# ════════════════════════════════════════════════════════════════════
FROM nvidia/cuda:${CUDA_VERSION}-runtime-ubuntu${UBUNTU_VERSION} AS runtime

# libgomp1：CPU 那部分用 OpenMP，runtime 映像檔沒附。curl：compose 的 healthcheck 要用
RUN apt-get update \
    && apt-get install -y --no-install-recommends libgomp1 curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /src/build/bin/llama-server /usr/local/bin/llama-server

# ubuntu 映像檔內建的一般使用者（uid 1000）。容器被入侵時拿到的不是 root
USER ubuntu

EXPOSE 8080

# compose 的 command 只給參數
ENTRYPOINT ["llama-server"]
