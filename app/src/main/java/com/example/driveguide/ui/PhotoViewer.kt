package com.example.driveguide.ui

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.example.driveguide.R
import kotlinx.coroutines.CoroutineScope

/**
 * 사진 전체 보기. 앱 창 전체를 덮고 사진을 자르지 않고 보여준다.
 * 좌우 스와이프로 사진을 넘기고, 탭·X·뒤로 키로 닫는다. 닫을 때 마지막으로 본 사진 번호를 [onClose]로 돌려준다.
 */
class PhotoViewer(
    private val activity: Activity,
    private val scope: CoroutineScope,
) {
    private var view: View? = null
    private var images: List<String> = emptyList()
    private var index = 0
    private var title = ""
    private var onClose: ((Int) -> Unit)? = null

    val isShowing get() = view != null

    fun show(images: List<String>, startIndex: Int, title: String, onClose: (Int) -> Unit) {
        if (images.isEmpty()) return
        this.images = images
        this.index = startIndex.coerceIn(0, images.lastIndex)
        this.title = title
        this.onClose = onClose

        val v = LayoutInflater.from(activity).inflate(R.layout.view_photo_viewer, null) as SwipeFrameLayout
        activity.addContentView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view = v
        v.onSwipe = { step -> go(step) }
        v.canSwipe = { step -> index + step in images.indices }
        v.onTap = ::close
        v.tapExcludedViews = listOf(v.findViewById(R.id.closeButton))
        v.findViewById<View>(R.id.closeButton).setOnClickListener { close() }
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(180).start()
        bind()
    }

    fun close() {
        val v = view ?: return
        view = null
        v.animate().alpha(0f).setDuration(150).withEndAction { (v.parent as? ViewGroup)?.removeView(v) }.start()
        onClose?.invoke(index)
    }

    private fun go(step: Int) {
        val v = view ?: return
        val target = index + step
        if (target !in images.indices) return
        index = target
        // 밀려 나간 반대쪽에서 새 사진이 들어오는 느낌만 주고 화면은 제자리로 돌린다.
        v.translationX = step * v.width * 0.15f
        v.animate().translationX(0f).setDuration(200).start()
        bind()
    }

    private fun bind() {
        val v = view ?: return
        val photo = v.findViewById<ImageView>(R.id.photo)
        photo.scaleType = ImageView.ScaleType.FIT_CENTER
        ImageLoader.load(scope, photo, images[index], activity.resources.displayMetrics.widthPixels) {
            photo.scaleType = ImageView.ScaleType.CENTER
            photo.setImageResource(R.drawable.ic_place_large)
        }
        v.findViewById<TextView>(R.id.counter).apply {
            visibility = if (images.size > 1) View.VISIBLE else View.GONE
            text = "${index + 1} / ${images.size}"
        }
        v.findViewById<TextView>(R.id.caption).text =
            if (images.size > 1) "$title · 좌우로 밀어 사진 넘기기" else title
    }
}
