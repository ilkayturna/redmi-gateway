package com.ilkay.redmigateway.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.text.TextUtils
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.AbsListView
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.ilkay.redmigateway.R
import java.util.Locale

/** Rows, avatars and headers shared by the phone screens. */
internal class PhoneViews(private val context: Context) {
    class Avatar(val root: FrameLayout, val image: ImageView, val initial: TextView)

    class Row(
        val root: LinearLayout,
        val avatar: Avatar,
        val title: TextView,
        val subtitleIcon: ImageView,
        val subtitle: TextView,
        val trailing: TextView,
        val action: ImageButton
    )

    private val density = context.resources.displayMetrics.density
    private val photos = LruCache<String, Bitmap>(96)
    private val missingPhotos = HashSet<String>()
    private val turkish = Locale("tr", "TR")
    private val palette = intArrayOf(
        0xFF3B6FB6.toInt(), 0xFF2E8B6E.toInt(), 0xFF8A5CB8.toInt(), 0xFFC0662B.toInt(),
        0xFF4A7F2A.toInt(), 0xFFB5475F.toInt(), 0xFF2F8A9C.toInt(), 0xFF7C6A36.toInt()
    )

    fun dp(value: Int) = (value * density).toInt()

    fun color(id: Int) = ContextCompat.getColor(context, id)

    fun selectable(borderless: Boolean = false): Drawable? {
        val attr = if (borderless) android.R.attr.selectableItemBackgroundBorderless else android.R.attr.selectableItemBackground
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return ContextCompat.getDrawable(context, value.resourceId)
    }

    fun avatar(): Avatar {
        val root = FrameLayout(context).apply {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
            }
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val initial = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        }
        val image = ImageView(context)
        root.addView(initial, FrameLayout.LayoutParams(-1, -1))
        root.addView(image, FrameLayout.LayoutParams(-1, -1))
        return Avatar(root, image, initial)
    }

    /** Photo when the contact has one, else the first letter on a colour of its own, else a person. */
    fun bindAvatar(avatar: Avatar, name: String?, photoUri: String?) {
        val bitmap = photoUri?.let(::photo)
        val letter = name?.trim()?.firstOrNull { it.isLetterOrDigit() }
        when {
            bitmap != null -> {
                avatar.root.background = ColorDrawable(color(R.color.pb_surface_variant))
                avatar.initial.text = ""
                avatar.image.visibility = View.VISIBLE
                avatar.image.imageTintList = null
                avatar.image.setPadding(0, 0, 0, 0)
                avatar.image.scaleType = ImageView.ScaleType.CENTER_CROP
                avatar.image.setImageBitmap(bitmap)
            }
            letter != null && letter.isLetter() -> {
                avatar.root.background = ColorDrawable(palette[(name.hashCode() and 0x7fffffff) % palette.size])
                avatar.initial.text = letter.toString().uppercase(turkish)
                avatar.image.visibility = View.GONE
                avatar.image.setImageDrawable(null)
            }
            else -> {
                avatar.root.background = ColorDrawable(color(R.color.text_muted))
                avatar.initial.text = ""
                avatar.image.visibility = View.VISIBLE
                avatar.image.scaleType = ImageView.ScaleType.FIT_CENTER
                avatar.image.setPadding(dp(10), dp(10), dp(10), dp(10))
                avatar.image.setImageResource(R.drawable.ic_pb_person)
                avatar.image.imageTintList = ColorStateList.valueOf(Color.WHITE)
            }
        }
    }

    private fun photo(uri: String): Bitmap? {
        photos.get(uri)?.let { return it }
        if (uri in missingPhotos) return null
        val bitmap = runCatching {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use(BitmapFactory::decodeStream)
        }.getOrNull()
        if (bitmap == null) missingPhotos.add(uri) else photos.put(uri, bitmap)
        return bitmap
    }

    fun row(): Row {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(72)
            setPadding(dp(16), dp(8), dp(8), dp(8))
            background = selectable()
            layoutParams = AbsListView.LayoutParams(-1, -2)
        }
        val avatar = avatar()
        root.addView(avatar.root, LinearLayout.LayoutParams(dp(44), dp(44)))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(8), 0)
        }
        val title = TextView(context).apply {
            textSize = 16f
            setTextColor(color(R.color.text_primary))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        texts.addView(title)
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = ImageView(context).apply { visibility = View.GONE }
        line.addView(icon, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(4) })
        val subtitle = TextView(context).apply {
            textSize = 14f
            setTextColor(color(R.color.text_secondary))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        line.addView(subtitle, LinearLayout.LayoutParams(0, -2, 1f))
        texts.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        root.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val trailing = TextView(context).apply {
            textSize = 13f
            setTextColor(color(R.color.text_secondary))
            visibility = View.GONE
        }
        root.addView(trailing, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        val action = ImageButton(context).apply {
            background = selectable(borderless = true)
            imageTintList = ColorStateList.valueOf(color(R.color.accent))
            isFocusable = false
            visibility = View.GONE
        }
        root.addView(action, LinearLayout.LayoutParams(dp(48), dp(48)))
        return Row(root, avatar, title, icon, subtitle, trailing, action)
    }

    fun header(): TextView = TextView(context).apply {
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(color(R.color.text_secondary))
        setPadding(dp(16), dp(18), dp(16), dp(6))
        layoutParams = AbsListView.LayoutParams(-1, -2)
        isFocusable = false
    }
}
