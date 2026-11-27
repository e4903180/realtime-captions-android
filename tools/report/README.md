# Listening report

Builds a web page with short clips from every test set and what each engine wrote for them.

```bash
python tools/report/build_clips.py                    # cut clips into build/report (needs datasets/ and testdata/wavs)
./gradlew :desktop:run --args="--models <dir> --clips build/report/clips --out build/report/xasr.json"
tools/whisper-bench/.venv/Scripts/python tools/report/run_engines.py   # Breeze, turbo (GPU), Google, Azure (a few cents)
tools/whisper-bench/.venv/Scripts/python tools/report/make_page.py     # build/report/site/index.html + audio/
```

Audio and outputs stay in `build/` (not in git).

## PDF with charts

```bash
tools/whisper-bench/.venv/Scripts/python tools/report/make_charts.py   # needs matplotlib
tools/whisper-bench/.venv/Scripts/python tools/report/make_pdf.py      # headless Chrome -> build/report/*.pdf
```
