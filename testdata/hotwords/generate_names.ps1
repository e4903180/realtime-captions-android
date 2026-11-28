# Speaks testdata/hotwords/names.json with Windows TTS into datasets/hotwords_names (wavs/ + truth/),
# one sentence per file, each voice reading every sentence. Audio stays out of git (datasets/ is ignored).
# Usage (from the repo root):  pwsh -File testdata/hotwords/generate_names.ps1
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = if ($env:CAPTIONS_REPO) { $env:CAPTIONS_REPO } else { Resolve-Path (Join-Path $here "../..") }
$out = Join-Path $root "datasets/hotwords_names"
New-Item -ItemType Directory -Force (Join-Path $out "wavs"), (Join-Path $out "truth") | Out-Null

Add-Type -AssemblyName System.Speech
$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
$fmt = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000,
    [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,
    [System.Speech.AudioFormat.AudioChannel]::Mono)

$spec = Get-Content (Join-Path $here "names.json") -Raw -Encoding UTF8 | ConvertFrom-Json
$v = 0
foreach ($voice in $spec.voices) {
    for ($i = 0; $i -lt $spec.sentences.Count; $i++) {
        $id = "names_v{0}_{1:D2}" -f $v, $i
        $wav = Join-Path $out "wavs/$id.wav"
        $synth.SelectVoice($voice)
        $synth.Rate = 0
        $synth.SetOutputToWaveFile($wav, $fmt)
        $synth.Speak($spec.sentences[$i])
        $synth.SetOutputToNull()
        $seconds = ((Get-Item $wav).Length - 44) / 32000.0
        $truth = [ordered]@{
            id = $id; dialog = "names"; language = "zh-TW"; noise = "clean"; snr_db = $null; duration = $seconds
            segments = @([ordered]@{ start = 0.0; end = $seconds; speaker = "A"; text = $spec.sentences[$i] })
        }
        $truth | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $out "truth/$id.json") -Encoding UTF8
    }
    $v++
}
($spec.vocabulary -join "`n") | Set-Content (Join-Path $out "vocabulary.txt") -Encoding UTF8
Write-Host "wrote $($spec.voices.Count * $spec.sentences.Count) files to $out"
