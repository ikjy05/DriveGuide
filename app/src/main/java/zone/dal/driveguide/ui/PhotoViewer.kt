package zone.dal.driveguide.ui

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import zone.dal.driveguide.R
import kotlinx.coroutines.CoroutineScope

/**
 * 사진 전체 보기. 앱 창 전체를 덮고 사진을 자르지 않고 보여준다.
 * 좌우로 밀어 사진을 넘기고(3장 이상이면 끝에서 처음으로 이어짐), 탭·X·뒤로 키로 닫는다. 닫을 때 마지막으로 본 사진 번호를 [onClose]로 돌려준다.
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

        val v = LayoutInflater.from(activity).inflate(R.layout.view_photo_viewer, null)
        activity.addContentView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view = v
        v.findViewById<View>(R.id.closeButton).setOnClickListener { close() }
        v.findViewById<PhotoPager>(R.id.photoPager).apply {
            onTap = ::close
            onPageChanged = { i ->
                this@PhotoViewer.index = i
                bindLabels()
            }
            setImages(scope, images, this@PhotoViewer.index)
        }
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(180).start()
    }

    fun close() {
        val v = view ?: return
        view = null
        v.animate().alpha(0f).setDuration(150).withEndAction { (v.parent as? ViewGroup)?.removeView(v) }.start()
        onClose?.invoke(index)
    }

    private fun bindLabels() {
        val v = view ?: return
        v.findViewById<TextView>(R.id.counter).apply {
            visibility = if (images.size > 1) View.VISIBLE else View.GONE
            text = "${index + 1} / ${images.size}"
        }
        v.findViewById<TextView>(R.id.caption).text =
            if (images.size > 1) "$title · 좌우로 밀어 사진 넘기기" else title
    }
}
