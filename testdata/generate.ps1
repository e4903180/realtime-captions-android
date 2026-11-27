# Generates the synthetic multi-speaker test set on Windows.
#   1. Speaks every dialog turn in dialogs.json with Windows TTS (16 kHz mono 16-bit).
#   2. Speaks distractor sentences used to build babble / cafe noise.
#   3. Runs make_testdata.py to assemble dialogs, ground truth and noisy variants.
# Usage (from the repo root):  pwsh -File testdata/generate.ps1  (PowerShell 7; Windows PowerShell 5 cannot see the OneCore voices)
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$out = Join-Path $here "build/tts"
New-Item -ItemType Directory -Force $out | Out-Null

Add-Type -AssemblyName System.Speech
$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
$fmt = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000,
    [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,
    [System.Speech.AudioFormat.AudioChannel]::Mono)

function Speak-ToWav([string]$voice, [string]$text, [string]$path) {
    $synth.SelectVoice($voice)
    $synth.Rate = 0
    $synth.SetOutputToWaveFile($path, $fmt)
    $synth.Speak($text)
    $synth.SetOutputToNull()
}

$spec = Get-Content (Join-Path $here "dialogs.json") -Raw -Encoding UTF8 | ConvertFrom-Json
foreach ($d in $spec.dialogs) {
    for ($i = 0; $i -lt $d.turns.Count; $i++) {
        $t = $d.turns[$i]
        $voice = $spec.voices.($t.voice)
        Speak-ToWav $voice $t.text (Join-Path $out ("{0}_{1:D2}.wav" -f $d.id, $i))
    }
}

# Distractor speech for babble noise: every voice reads the unrelated sentences in dialogs.json.
$babble = $spec.babble_sentences
$voices = $spec.voices.PSObject.Properties.Value
$k = 0
foreach ($v in $voices) {
    foreach ($s in $babble) {
        Speak-ToWav $v $s (Join-Path $out ("babble_{0:D3}.wav" -f $k))
        $k++
    }
}

python (Join-Path $here "make_testdata.py")
