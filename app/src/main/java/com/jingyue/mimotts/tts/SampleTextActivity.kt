package com.jingyue.mimotts.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

class SampleTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val language = intent.getStringExtra("language")
        setResult(RESULT_OK, Intent().putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, LocalePolicy.sampleText(language)))
        finish()
    }
}
