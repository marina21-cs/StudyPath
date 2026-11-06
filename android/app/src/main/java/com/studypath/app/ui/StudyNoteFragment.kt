package com.studypath.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.studypath.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

class StudyNoteModel : ViewModel() {
    var note: PersonalMaterial?=null
    var dirty=false
    var busy=false
    var status=""
    var drafts:File?=null
    val revision=MutableStateFlow(0)
    fun changed() { revision.value++ }
    override fun onCleared() { drafts?.deleteRecursively() }
}

/** Native text/photo editing shared by personal materials and the isolated demo. */
class StudyNoteFragment : Fragment() {
    private lateinit var model:StudyNoteModel
    private var owner:PersonalStudyStore?=null
    private val demo get()=activity as? DemoActivity
    private lateinit var directory:File
    private lateinit var back:OnBackPressedCallback
    private val picker=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null && ::model.isInitialized && !model.busy) {
            val resolver=requireContext().contentResolver
            model.busy=true;model.status="Preparing photo…";model.changed()
            model.viewModelScope.launch {
                try {
                    val photo=withContext(Dispatchers.IO) {
                        val id=PersonalStudyStore.newId();val target=File(requireNotNull(model.drafts),"$id.jpg")
                        resolver.openInputStream(uri).use { stream -> requireNotNull(stream) { "Could not open this photo." }; importNotePhoto(stream,target) }
                        NotePhoto(id)
                    }
                    model.note=model.note!!.copy(photos=model.note!!.photos.orEmpty()+photo)
                    model.dirty=true;model.status="Photo added. You can add a caption below."
                } catch(e:Exception) { model.status=e.message ?: "Could not open the photo. Choose another image." }
                finally { model.busy=false;model.changed() }
            }
        }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val args=requireArguments()
        model=ViewModelProvider(requireActivity())["study-note-${args.getString("draft")}",StudyNoteModel::class.java]
        owner=if(demo==null)personalStore()else null
        directory=owner?.directory ?: demo!!.noteDirectory()
        if(model.note==null) {
            val data=owner?.read() ?: demo!!.noteWorkspace()
            val existing=data.materials.firstOrNull { it.id==args.getString("id") }
            model.note=existing ?: PersonalMaterial(PersonalStudyStore.newId(),args.getString("subject")!!,args.getString("title").orEmpty(),source=args.getString("source")?.let { NoteSource(it,args.getInt("page",1)) })
            model.drafts=File(requireContext().cacheDir,"note-drafts/${args.getString("draft")}").apply { mkdirs() }
            savedInstanceState?.getString("note")?.let { model.note=com.google.gson.Gson().fromJson(it,PersonalMaterial::class.java);model.dirty=true }
        }
        back=object:OnBackPressedCallback(true) { override fun handleOnBackPressed() {
            if(model.busy) { Toast.makeText(requireContext(),"Wait for the photo to finish.",Toast.LENGTH_SHORT).show();return }
            if(!model.dirty)finish()else MaterialAlertDialogBuilder(requireContext()).setTitle("Discard note changes?")
                .setMessage("Your last saved note stays unchanged.").setNegativeButton("Keep editing",null)
                .setPositiveButton("Discard") { _,_-> model.drafts?.deleteRecursively();model.note=null;finish() }.show()
        } }
        requireActivity().onBackPressedDispatcher.addCallback(this,back)
    }
    override fun onSaveInstanceState(out:Bundle) { out.putString("note",com.google.gson.Gson().toJson(model.note));super.onSaveInstanceState(out) }
    override fun onCreateView(inflater:LayoutInflater,container:ViewGroup?,state:Bundle?):View = requireContext().scrolling(requireContext().column().apply { tag="study_note_editor" })
    override fun onViewCreated(view:View,state:Bundle?) {
        viewLifecycleOwner.lifecycleScope.launch { model.revision.collect { render() } }
    }
    private fun finish() {
        back.isEnabled=false
        if(demo!=null)demo!!.finishNote()else parentFragmentManager.popBackStack()
    }
    private fun render() {
        val b=view?.findViewWithTag<LinearLayout>("study_note_editor") ?: return
        val note=model.note ?: return
        b.removeAllViews()
        b.label(if(demo!=null) "Demo changes last for this session." else "Saved on this phone. Available offline.",14f)
        note.source?.let { b.label("Attached to PDF page ${it.page}",14f) }
        fun field(label:String,value:String,limit:Int,tag:String,multiline:Boolean=false,change:(String)->Unit) {
            b.label(label,14f)
            b.field(label,InputType.TYPE_CLASS_TEXT or if(multiline)InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0).apply {
                (b.getChildAt(b.childCount-1) as com.google.android.material.textfield.TextInputLayout).isHintEnabled=false
                hint=label;contentDescription=label
                setPadding(context.dp(16),context.dp(12),context.dp(16),context.dp(12))
                this.tag=tag;filters=arrayOf(InputFilter.LengthFilter(limit));setText(value)
                if(multiline){minLines=3;maxLines=10};isEnabled=!model.busy
                doAfterTextChanged { change(it.toString());model.dirty=true }
            }
        }
        field("Title",note.title,120,"note_title") { model.note=model.note!!.copy(title=it) }
        field("Notes",note.text,20000,"note_text",true) { model.note=model.note!!.copy(text=it) }
        b.compactAction("Add photo") {
            if(model.note!!.photos.orEmpty().size>=StudyNotes.MAX_PHOTOS){model.status="Add up to six photos per note.";model.changed()}
            else runCatching { picker.launch(arrayOf("image/jpeg","image/png","image/webp")) }.onFailure { model.status="No image picker is available.";model.changed() }
        }.apply { tag="note_add_photo";isEnabled=!model.busy }
        for((index,photo) in note.photos.orEmpty().withIndex()) {
            val saved=StudyNotes.image(directory,photo.id)
            b.notePhoto(if(saved.exists())saved else File(model.drafts,"${photo.id}.jpg"),photo.caption,viewLifecycleOwner)
            field("Photo ${index+1} caption",photo.caption,500,"note_caption_$index") { caption ->
                model.note=model.note!!.copy(photos=model.note!!.photos.orEmpty().map { if(it.id==photo.id)it.copy(caption=caption)else it })
            }
            b.compactAction("Remove photo ${index+1}") {
                model.note=model.note!!.copy(photos=model.note!!.photos.orEmpty().filterNot { it.id==photo.id });model.dirty=true;model.changed()
            }.apply { tag="note_remove_photo_$index";isEnabled=!model.busy }
        }
        b.label("Practice question (optional)",20f)
        field("Question",note.question,1000,"note_question",true) { model.note=model.note!!.copy(question=it) }
        field("Answer",note.answer,4000,"note_answer",true) { model.note=model.note!!.copy(answer=it) }
        b.label(model.status,14f).apply { tag="note_status";accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE }
        b.action("Save note") {
            try {
                val updated=model.note!!.let { it.copy(title=it.title.trim(),text=it.text.trim(),question=it.question.trim(),answer=it.answer.trim()) }
                if(demo!=null)demo!!.saveStudyNote(updated,requireNotNull(model.drafts))else {
                    require(personalStore().directory==owner!!.directory) { "Sign back in to save this note." }
                    owner!!.saveNote(updated,requireNotNull(model.drafts))
                }
                model.drafts?.deleteRecursively();model.dirty=false;model.note=null;finish()
            } catch(e:Exception) { model.status=e.message ?: "Could not save. Your changes are still here.";model.changed() }
        }.apply { tag="note_save";isEnabled=!model.busy }
    }
    companion object {
        fun open(subject:String,id:String?=null,title:String="",source:NoteSource?=null)=StudyNoteFragment().apply { arguments=Bundle().apply {
            putString("subject",subject);putString("id",id);putString("title",title);putString("draft",PersonalStudyStore.newId())
            source?.let { putString("source",it.materialId);putInt("page",it.page) }
        } }
    }
}

/** Decode at a bounded size, honor EXIF rotation and strip metadata from the stored JPEG. */
internal fun importNotePhoto(input:java.io.InputStream,target:File) {
    val raw=File(target.parentFile,"${target.name}.import")
    try {
        raw.outputStream().use { out -> val buffer=ByteArray(8192);var total=0L;while(true){val n=input.read(buffer);if(n<0)break;total+=n;require(total<=12L*1024*1024){"Choose a photo smaller than 12 MB."};out.write(buffer,0,n)} }
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true };BitmapFactory.decodeFile(raw.path,bounds)
        require(bounds.outWidth>0 && bounds.outHeight>0 && bounds.outWidth.toLong()*bounds.outHeight<=80_000_000) { "Choose a valid JPEG, PNG or WebP photo." }
        var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>1600)sample*=2
        val decoded=requireNotNull(BitmapFactory.decodeFile(raw.path,BitmapFactory.Options().apply { inSampleSize=sample })) { "Could not read this photo." }
        try {
            val orientation=runCatching { ExifInterface(raw.path).getAttributeInt(ExifInterface.TAG_ORIENTATION,1) }.getOrDefault(1)
            val matrix=Matrix().apply { when(orientation){2->setScale(-1f,1f);3->setRotate(180f);4->setScale(1f,-1f);5->{setRotate(90f);postScale(-1f,1f)};6->setRotate(90f);7->{setRotate(270f);postScale(-1f,1f)};8->setRotate(270f)} }
            val rotated=Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,matrix,true)
            try { target.outputStream().use { check(rotated.compress(Bitmap.CompressFormat.JPEG,85,it)) };require(target.length() in 1..StudyNotes.MAX_IMAGE_BYTES) { "This photo is too detailed. Choose a smaller image." } }
            finally { if(rotated!==decoded)rotated.recycle() }
        } finally { decoded.recycle() }
    } catch(e:Exception){target.delete();throw e} finally { raw.delete() }
}

internal fun LinearLayout.notePhoto(file:File,caption:String,owner:LifecycleOwner) {
    val image=ImageView(context).apply { adjustViewBounds=true;scaleType=ImageView.ScaleType.FIT_CENTER;contentDescription=caption.ifBlank { "Study note photo" };layoutParams=LinearLayout.LayoutParams(-1,context.dp(220));tag="note_photo" }
    addView(image)
    owner.lifecycleScope.launch {
        val bitmap=withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply { inSampleSize=2 }) }
        if(bitmap!=null)image.setImageBitmap(bitmap)else { image.contentDescription="Photo unavailable";label("Photo unavailable. Edit this note to choose it again.",14f) }
    }
    if(caption.isNotBlank())label(caption,14f)
}
