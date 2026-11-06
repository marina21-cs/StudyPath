package com.studypath.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.ViewGroup
import android.widget.LinearLayout
import com.studypath.app.data.PersonalSubject
import com.studypath.app.R

/** Real buttons remain accessible; edges connect prerequisite nodes to the dependent course. */
class PrerequisiteMapView(context:Context,private val subjects:List<PersonalSubject>,onSelect:(PersonalSubject)->Unit):ViewGroup(context){
    private val levels=mutableMapOf<String,Int>()
    private val positions=mutableMapOf<String,Pair<Int,Int>>()
    private val pen=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=context.tint(R.color.sp_muted);strokeWidth=context.dp(2).toFloat();style=Paint.Style.STROKE}
    private val nodeWidth=context.dp(164);private val gap=context.dp(24);private var rowHeight=context.dp(118)
    private var columns=1;private var rows=1
    init{
        setWillNotDraw(false)
        val byId=subjects.associateBy{it.id}
        fun depth(s:PersonalSubject,seen:Set<String> = emptySet()):Int{
            levels[s.id]?.let{return it};if(s.id in seen)return 0
            val d=s.prerequisiteIds.orEmpty().mapNotNull{byId[it]}.maxOfOrNull{depth(it,seen+s.id)+1}?:0;levels[s.id]=d;return d
        }
        subjects.forEach{depth(it)}
        val groups=subjects.groupBy{levels[it.id]?:0};columns=groups.values.maxOfOrNull{it.size}?:1;rows=(groups.keys.maxOrNull()?:0)+1
        groups.forEach{(level,items)->items.forEachIndexed{col,s->positions[s.id]=col to level}}
        subjects.forEach{s->val holder=context.column(0)
            val button=holder.action(s.title+"\n"+(if(s.prerequisiteIds.isNullOrEmpty())"No links added" else "Requires all linked courses")){onSelect(s)}.quietAction()
            button.backgroundTintList=android.content.res.ColorStateList.valueOf(context.tint(R.color.sp_cream));button.strokeWidth=0;button.textSize=14f;button.maxLines=5;button.contentDescription="${s.title}. ${s.prerequisiteIds.orEmpty().size} prerequisites. Policy unreviewed. Open details."
            holder.removeView(button);addView(button)
        }
    }
    override fun onMeasure(w:Int,h:Int){
        rowHeight=context.dp(118)
        for(i in 0 until childCount){getChildAt(i).measure(MeasureSpec.makeMeasureSpec(nodeWidth,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));rowHeight=maxOf(rowHeight,getChildAt(i).measuredHeight+gap*2)}
        setMeasuredDimension(columns*(nodeWidth+gap),rows*rowHeight+gap)
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){subjects.forEachIndexed{i,s->val(c,row)=positions.getValue(s.id);val child=getChildAt(i);val x=c*(nodeWidth+gap);val y=row*rowHeight+gap;child.layout(x,y,x+nodeWidth,y+child.measuredHeight)}}
    override fun onDraw(canvas:Canvas){super.onDraw(canvas);val indexed=subjects.mapIndexed{i,s->s.id to getChildAt(i)}.toMap()
        subjects.forEach{s->val to=indexed.getValue(s.id);s.prerequisiteIds.orEmpty().forEach{id->val from=indexed[id]?:return@forEach;val x1=(from.left+from.width/2).toFloat();val y1=from.bottom.toFloat();val x2=(to.left+to.width/2).toFloat();val y2=to.top.toFloat();val mid=(y1+y2)/2
            canvas.drawPath(Path().apply{moveTo(x1,y1);lineTo(x1,mid);lineTo(x2,mid);lineTo(x2,y2)},pen)
            val a=context.dp(5).toFloat();canvas.drawLine(x2-a,y2-a,x2,y2,pen);canvas.drawLine(x2+a,y2-a,x2,y2,pen)
        }}
    }
}
