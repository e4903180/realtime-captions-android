#!/usr/bin/env bash
# Downloads the real-speech test corpora (about 4 GB on disk) into datasets/ (git-ignored).
# Usage: bash testdata/real/download_real.sh datasets
# Licenses: Common Voice CC0 (https://commonvoice.mozilla.org/datasets),
#           AISHELL-1 Apache 2.0 (https://www.openslr.org/33/),
#           AliMeeting CC BY-SA 4.0 (https://www.openslr.org/119/).
set -euo pipefail
root="${1:-datasets}"
mkdir -p "$root/aishell1" "$root/alimeeting"

# Common Voice 19.0 zh-TW test split (CC0, 137 MB), Taiwan accent, from a non-gated Hugging Face mirror.
mkdir -p "$root/cv_zh_tw"
[ -s "$root/cv_zh_tw/test-00000.parquet" ] || curl -fsSL --retry 4 -o "$root/cv_zh_tw/test-00000.parquet"   https://huggingface.co/datasets/JacobLinCool/common_voice_19_0_zh-TW/resolve/main/data/test-00000-of-00001.parquet

# NTU ML 2021 lectures (MIT), two shortest lectures (66 MB), Taiwan accent.
mkdir -p "$root/ml2021"
for i in 00000 00001; do
  [ -s "$root/ml2021/test-$i.parquet" ] || curl -fsSL --retry 4 -o "$root/ml2021/test-$i.parquet"     "https://huggingface.co/datasets/generative-fusion-decoding/ml-lecture-2021-long/resolve/main/data/test-$i-of-00014.parquet"
done

# AISHELL-1: transcript + one test-set shard (2307 utterances, 364 MB) from the Hugging Face mirror.
cd "$root/aishell1"
[ -s aishell_transcript_v0.8.txt ] || curl -fsSL --retry 4 -o aishell_transcript_v0.8.txt   https://huggingface.co/datasets/AISHELL/AISHELL-1/resolve/main/data_aishell/transcript/aishell_transcript_v0.8.txt
[ -s test-00000.parquet ] || curl -fsSL --retry 4 -o test-00000.parquet   https://huggingface.co/datasets/AudioLLMs/aishell_1_zh_test/resolve/main/data/test-00000-of-00003.parquet
cd - >/dev/null

# AliMeeting Eval (3.4 GB): keep only the far-field array audio and the transcripts.
cd "$root/alimeeting"
if ! ls Eval_Ali/Eval_Ali_far/audio_dir/*.wav >/dev/null 2>&1; then
  [ -s Eval_Ali.tar.gz ] || curl -fsSL --retry 4 -o Eval_Ali.tar.gz \
    https://speech-lab-share-data.oss-cn-shanghai.aliyuncs.com/AliMeeting/openlr/Eval_Ali.tar.gz
  tar xzf Eval_Ali.tar.gz --wildcards '*Eval_Ali_far*'
  rm Eval_Ali.tar.gz
fi
echo "done; now run:"
echo "  pip install pyarrow numpy soundfile soxr yt-dlp av"
echo "  python testdata/real/prepare_real.py $root"
echo "  python testdata/real/fetch_youtube.py $root   # CC-licensed Taiwan clips with human subtitles"
