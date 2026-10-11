package com.github.jing332.tts_server_android.ui.systts.speech_rule

import com.github.jing332.tts_server_android.R
import com.github.jing332.tts_server_android.constant.AppConst
import com.github.jing332.tts_server_android.constant.ConfigType
import com.github.jing332.tts_server_android.data.appDb
import com.github.jing332.tts_server_android.data.entities.SpeechRule
import com.github.jing332.tts_server_android.ui.base.import1.BaseImportConfigBottomSheetFragment
import com.github.jing332.tts_server_android.ui.base.import1.ConfigItemModel
import com.github.jing332.tts_server_android.service.systts.config.RuntimeConfigWriter
import com.github.jing332.tts_server_android.ui.view.AppDialogs.displayErrorDialog
import com.github.jing332.tts_server_android.utils.longToast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString

class ImportConfigBottomSheetFragment :
    BaseImportConfigBottomSheetFragment(R.string.speech_rule, ConfigType.SPEECH_RULE) {
    companion object {
        const val TAG = "ImportConfigBottomSheetFragment"
    }

    override fun onImport(json: String) {
        val list: List<SpeechRule> = AppConst.jsonBuilder.decodeFromString(json)
        displayListSelectDialog(list.map { ConfigItemModel(true, it.name, it.author, it) }) {
            val selected = it.map { rule -> rule as SpeechRule }.toTypedArray()
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    check(RuntimeConfigWriter.publish {
                        appDb.speechRule.insert(*selected)
                    }) { "runtime_publish_failed" }
                    withContext(Dispatchers.Main) {
                        longToast(getString(R.string.config_import_success_msg, selected.size))
                        dismiss()
                    }
                } catch (error: Throwable) {
                    withContext(Dispatchers.Main) { requireContext().displayErrorDialog(error) }
                }
            }
        }
    }
}
