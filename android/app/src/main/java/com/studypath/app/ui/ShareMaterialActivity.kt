package com.studypath.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.studypath.app.StudyPathApplication
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/** External share is untrusted input. Require session and let the user review before any transfer. */
class ShareMaterialActivity:AppCompatActivity(){
    override fun onCreate(s:Bundle?){super.onCreate(s);lifecycleScope.launch{
        val repository=(application as StudyPathApplication).graph.repository
        val session=runCatching{repository.resumeSession()}.getOrNull()
        if(session==null){MaterialAlertDialogBuilder(this@ShareMaterialActivity).setTitle("Sign in to save materials").setMessage("Sign in to StudyPath, then share the PDF or link again.").setPositiveButton("Sign in"){_,_->startActivity(Intent(this@ShareMaterialActivity,com.studypath.app.ui.auth.WelcomeActivity::class.java));finish()}.setNegativeButton("Cancel"){_,_->finish()}.show();return@launch}
        val outgoing=Intent(this@ShareMaterialActivity,MainActivity::class.java)
        if(intent.action==Intent.ACTION_SEND && intent.type=="application/pdf"){
            @Suppress("DEPRECATION") val uri=intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if(uri?.scheme!="content"){finish();return@launch}
            outgoing.putExtra("sharedPdf",uri.toString())
            outgoing.clipData=android.content.ClipData.newRawUri("Shared PDF",uri)
            outgoing.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }else if(intent.action==Intent.ACTION_SEND && intent.type?.startsWith("text/")==true){
            val value=intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().take(4097)
            outgoing.putExtra("sharedPdfLink",value)
        }else{finish();return@launch}
        startActivity(outgoing);finish()
    }}
}
