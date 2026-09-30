# 自己 build 的 llama-server（CUDA 版）。
#
# 為什麼不用官方的 ghcr.io/ggml-org/llama.cpp:server-cuda：本機連 ghcr 實測只有 0.17 MB/s，
# 2.6GB 要拉 3.5 小時；Docker Hub 有 10 MB/s、GitHub 原始碼 6 MB/s，自己編反而快。
# 上雲端也是這個做法：自己 build、推到自己同區的 registry，GPU 機冷啟動才不會卡在拉 image。
#
# 兩段式（multi-stage）：
#   build   —— devel 映像檔，有 nvcc 編譯器和標頭檔，好幾 GB，只用來編譯
#   runtime —— runtime 映像檔，只有 CUDA 函式庫，把編好的那一支執行檔搬過去
# 最後的 image 只有 runtime 那段，編譯器不會跟著上雲端。

# ── 版本參數：全部集中在這裡，改版本只動這幾行 ─────────────────────────
# llama.cpp 跟 C:\llm\llama.cpp 的原生 build 同一版，原生 vs 容器才比得出「容器」本身的差異
ARG LLAMA_BUILD=b10964
# CUDA 13 跟原生的 cudart64_13 同一代。注意：CUDA 13 要主機驅動 >= 580，
# 本機是 610 沒問題；雲端 GPU 機的驅動如果比較舊，改成 12.9.1 重 build。
ARG CUDA_VERSION=13.0.1
ARG UBUNTU_VERSION=24.04

# ════════════════════════════════════════════════════════════════════
FROM nvidia/cuda:${CUDA_VERSION}-devel-ubuntu${UBUNTU_VERSION} AS build

ARG LLAMA_BUILD
# 只編這一種 GPU 架構：89 = Ada（RTX 40 系列、雲端的 L4）。
# 預設 llama.cpp 會把每一代 GPU 都編一份，慢好幾倍。代價是換別的卡要改這個值重 build：
# T4 = 75、A10G = 86、A100 = 80、H100 = 90。
ARG CUDA_ARCH=89

RUN apt-get update \
    && apt-get install -y --no-install-recommends build-essential cmake \
    && rm -rf /var/lib/apt/lists/*

# 原生碼直接從 GitHub 抓指定版本的 tarball。
# --checksum：內容跟這串 SHA256 對不上就 build 失敗——確保每次、每台機器編的都是同一份原始碼，
# 不會被中途竄改或悄悄換掉（供應鏈安全的最基本一步）。
# 升級 LLAMA_BUILD 時這串要一起換：先下載新版 tarball，用 sha256sum 算出來貼上。
ADD --checksum=sha256:4c96d72c40cefdacf621457d8a12fe5673b8de5b640ff7fe6d342ea1c579a9fe \
    https://github.com/ggml-org/llama.cpp/archive/refs/tags/${LLAMA_BUILD}.tar.gz /tmp/src.tar.gz
RUN mkdir /src && tar xzf /tmp/src.tar.gz -C /src --strip-components=1

WORKDIR /src
RUN cmake -B build \
        -DCMAKE_BUILD_TYPE=Release \
        -DGGML_CUDA=ON \
        -DCMAKE_CUDA_ARCHITECTURES=${CUDA_ARCH} \
        # CPU 那一半不要針對「編譯這台機器」最佳化：雲端的 CPU 不一定跟這台一樣，
        # 用了這台才有的指令集，換台機器會直接 Illegal instruction 當掉。
        -DGGML_NATIVE=OFF \
        # 編成單一靜態執行檔：ggml/llama 的函式庫都塞進 llama-server 裡，
        # runtime 階段只要搬一個檔案，不用追一堆 .so。
        -DBUILD_SHARED_LIBS=OFF \
        # 不內嵌網頁聊天介面（預設會去 Hugging Face 下載 UI 檔）。app 只打 API，用不到那個頁面。
        -DLLAMA_USE_PREBUILT_UI=OFF \
        # 不需要 HTTPS 用戶端功能（那是給 -hf 從網路下載模型用的），模型是掛載進來的。
        -DLLAMA_OPENSSL=OFF \
        -DLLAMA_BUILD_TESTS=OFF \
        -DLLAMA_BUILD_EXAMPLES=OFF \
        # build 機器上沒有 GPU 驅動，libcuda.so 只有空殼（stub）。允許連結時先不管它，
        # 執行時由主機的 NVIDIA 驅動透過 nvidia runtime 提供真的那份。
        -DCMAKE_EXE_LINKER_FLAGS=-Wl,--allow-shlib-undefined \
    && cmake --build build --target llama-server -j"$(nproc)"

# ════════════════════════════════════════════════════════════════════
FROM nvidia/cuda:${CUDA_VERSION}-runtime-ubuntu${UBUNTU_VERSION} AS runtime

# libgomp1：llama.cpp 的 CPU 部分用 OpenMP 開多執行緒，runtime 映像檔沒附。
# curl：給 compose 的 healthcheck 打 /health 用。
RUN apt-get update \
    && apt-get install -y --no-install-recommends libgomp1 curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /src/build/bin/llama-server /usr/local/bin/llama-server

# 預設不跑 root：容器被打穿時，攻擊者拿到的只是一般使用者。
# ubuntu 映像檔內建一個 uid 1000 的 ubuntu 使用者，直接用。
USER ubuntu

# 只是文件（告訴讀的人預設聽哪個埠），真正對外開埠是 compose 的 ports。
EXPOSE 8080

# ENTRYPOINT 固定是 llama-server，compose 的 command 只給參數（-m、--port…）。
ENTRYPOINT ["llama-server"]
