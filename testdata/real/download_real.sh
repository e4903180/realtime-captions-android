#!/usr/bin/env bash
# Downloads the real-speech test corpora (about 4 GB on disk) into datasets/ (git-ignored).
# Usage: bash testdata/real/download_real.sh datasets
# Licenses: AISHELL-1 Apache 2.0 (https://www.openslr.org/33/),
#           AliMeeting CC BY-SA 4.0 (https://www.openslr.org/119/).
set -euo pipefail
root="${1:-datasets}"
mkdir -p "$root/aishell1" "$root/alimeeting"

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
echo "done; now run: pip install pyarrow numpy && python testdata/real/prepare_real.py $root"
